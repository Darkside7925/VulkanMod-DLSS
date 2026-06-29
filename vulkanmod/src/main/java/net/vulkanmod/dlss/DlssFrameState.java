package net.vulkanmod.dlss;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.vulkanmod.vulkan.util.MappedBuffer;
import org.joml.Matrix4f;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Per-frame temporal state DLSS needs: the current and previous frame's view-projection
 * matrices (for motion-vector reprojection) and a sub-pixel Halton jitter offset (for
 * DLSS Super Resolution).
 *
 * <p>Phase 2 foundation: this captures and computes the data but does NOT yet apply jitter
 * to the projection used for rasterization, nor build the motion-vector buffer. Those are
 * wired once the DLSS-SR evaluate path exists (Phase 3), so by default this is invisible
 * and changes nothing about how the frame looks.
 *
 * <p>Depth convention for this renderer (verified): clip-space Z in [0,1], near→0 far→1,
 * NOT reversed-Z, infinite far plane. → Streamline {@code depthInverted = false}.
 *
 * <p>All access is on the render thread; no synchronization needed.
 */
public final class DlssFrameState {

    private DlssFrameState() {}

    // --- Matrices (row-major when handed to Streamline; JOML stores column-major, convert on export) ---
    private static final Matrix4f currentViewProj  = new Matrix4f();
    private static final Matrix4f previousViewProj  = new Matrix4f();
    private static final Matrix4f currentProjection = new Matrix4f();   // P alone (cameraViewToClip)
    private static final Matrix4f invCurVpTmp = new Matrix4f();
    private static final Matrix4f clipToPrevClipTmp = new Matrix4f();
    private static boolean hasPrevious = false;
    private static boolean currentSetThisFrame = false;

    // --- Jitter (Halton(2,3)), offset in pixels in [-0.5, 0.5] ---
    /** Number of distinct jitter phases. DLSS recommends 8*(native/render)^2; 16 is a safe default. */
    private static int jitterPhaseCount = 16;
    private static int jitterIndex = 0;
    private static float jitterX = 0.0f;   // pixels
    private static float jitterY = 0.0f;   // pixels

    private static int renderWidth = 0;
    private static int renderHeight = 0;
    private static long frameCounter = 0;

    // Debug: L1 norm of (currentVP - previousVP) — ~0 when camera is still, >0 when it moves.
    private static float lastVpDelta = 0.0f;
    private static final float[] tmpCur = new float[16];
    private static final float[] tmpPrev = new float[16];

    // GPU-facing copies for the UBO suppliers (mat4 = 64 bytes, vec2 = 8 bytes).
    public static final MappedBuffer invCurrentVPBuf = new MappedBuffer(16 * 4);
    public static final MappedBuffer previousVPBuf   = new MappedBuffer(16 * 4);
    public static final MappedBuffer mvScaleBuf      = new MappedBuffer(2 * 4);
    private static final Matrix4f invTmp = new Matrix4f();

    static {
        // Initialize to identity / unit scale so the overlay never reads garbage before frame 1.
        new Matrix4f().get(invCurrentVPBuf.buffer.asFloatBuffer());
        new Matrix4f().get(previousVPBuf.buffer.asFloatBuffer());
        mvScaleBuf.putFloat(0, 1.0f);
        mvScaleBuf.putFloat(4, 1.0f);
    }

    /** Master switch — when false (default for now) nothing here affects rendering. */
    public static boolean enabled = false;
    /** Whether the computed jitter should actually be applied to the projection (Phase 3+). */
    public static boolean applyJitter = false;

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod-DLSS");
    private static final boolean DEBUG = Boolean.getBoolean("mcdlss.debug");
    private static final boolean GPU_TEST = Boolean.getBoolean("mcdlss.gputest");
    private static final boolean EVAL_TEST = Boolean.getBoolean("mcdlss.evaltest");
    private static boolean gpuTestDone = false;
    private static boolean evalTestDone = false;

    /**
     * Call once at the start of each frame, before the world's view-projection is set.
     * Rolls current→previous and advances the jitter phase.
     */
    public static void beginFrame() {
        Window w = Minecraft.getInstance().getWindow();
        beginFrame(w.getWidth(), w.getHeight());
    }

    /** Pure frame-roll (no Minecraft dependency) — directly exercised by DlssSelfTest. */
    public static void beginFrame(int width, int height) {
        frameCounter++;

        // Phase 4: Reflex sleep + simulation-start marker at the very top of the frame.
        NativeBridge.reflexFrameStart((int) frameCounter);

        // Roll the view-projection: this frame's "previous" is last frame's "current".
        if (currentSetThisFrame) {
            previousViewProj.set(currentViewProj);
            previousViewProj.get(previousVPBuf.buffer.asFloatBuffer());   // GPU copy
            hasPrevious = true;
        }
        currentSetThisFrame = false;

        renderWidth = width;
        renderHeight = height;

        // One-shot on-device test, once the renderer (MemoryManager, etc.) is fully up.
        if (GPU_TEST && !gpuTestDone && frameCounter >= 3) {
            gpuTestDone = true;
            DlssGpuTest.run();
        }
        if (EVAL_TEST && !evalTestDone && frameCounter >= 5) {
            evalTestDone = true;
            DlssEvaluateValidator.run();
        }

        // Advance Halton jitter. Index 0 of Halton is 0; start at 1.
        jitterIndex = (jitterIndex + 1) % jitterPhaseCount;
        int h = jitterIndex + 1;
        jitterX = halton(h, 2) - 0.5f;   // [-0.5, 0.5] pixels
        jitterY = halton(h, 3) - 0.5f;

        if (DEBUG && (frameCounter <= 6 || (hasPrevious && frameCounter % 30 == 0) || frameCounter % 600 == 0)) {
            LOGGER.info("[DLSS Phase2] frame={} {}x{} jitter=({}, {}) px  hasPrev={}  vpDelta={}",
                    frameCounter, renderWidth, renderHeight,
                    String.format("%+.4f", jitterX), String.format("%+.4f", jitterY),
                    hasPrevious, String.format("%.5f", lastVpDelta));
        }
    }

    /**
     * Provide the world's view (model-view) and projection matrices for this frame.
     * Called from the matrix-upload path; idempotent within a frame (same camera VP).
     */
    public static void setViewProjection(Matrix4f modelView, Matrix4f projection) {
        // VP = P * MV
        currentViewProj.set(projection).mul(modelView);
        currentProjection.set(projection);
        currentSetThisFrame = true;

        // GPU copy: inverse of the current view-projection (for world reconstruction).
        invTmp.set(currentViewProj).invert();
        invTmp.get(invCurrentVPBuf.buffer.asFloatBuffer());

        if (hasPrevious) {
            currentViewProj.get(tmpCur);
            previousViewProj.get(tmpPrev);
            float d = 0.0f;
            for (int i = 0; i < 16; i++) d += Math.abs(tmpCur[i] - tmpPrev[i]);
            lastVpDelta = d;
        }
    }

    /** L1 norm of (currentVP − previousVP) for the latest frame; ~0 when the camera is still. */
    public static float lastViewProjectionDelta() { return lastVpDelta; }

    /**
     * Fills the 40-float constants array the DLSS evaluate expects:
     * [0..15]=cameraViewToClip (row-major), [16..31]=clipToPrevClip (row-major),
     * [32,33]=jitter px, [34,35]=mvScale, [36]=near, [37]=far, [38]=fov, [39]=aspect.
     */
    public static synchronized void fillSrConstants(float[] out, float jitterPxX, float jitterPxY,
                                                    float mvScaleX, float mvScaleY) {
        rowMajor(currentProjection, out, 0);
        // clipToPrevClip = previousVP * inverse(currentVP)
        invCurVpTmp.set(currentViewProj).invert();
        clipToPrevClipTmp.set(previousViewProj).mul(invCurVpTmp);
        rowMajor(clipToPrevClipTmp, out, 16);
        out[32] = jitterPxX; out[33] = jitterPxY;
        out[34] = mvScaleX;  out[35] = mvScaleY;
        out[36] = 0.05f;     out[37] = 10000.0f;
        float fov = currentProjection.perspectiveFov();
        out[38] = (Float.isNaN(fov) || fov <= 0f) ? (float) Math.toRadians(70.0) : fov;
        out[39] = renderHeight != 0 ? (float) renderWidth / renderHeight : 1.7778f;
    }

    private static void rowMajor(Matrix4f m, float[] o, int off) {
        o[off]=m.m00(); o[off+1]=m.m10(); o[off+2]=m.m20(); o[off+3]=m.m30();
        o[off+4]=m.m01(); o[off+5]=m.m11(); o[off+6]=m.m21(); o[off+7]=m.m31();
        o[off+8]=m.m02(); o[off+9]=m.m12(); o[off+10]=m.m22(); o[off+11]=m.m32();
        o[off+12]=m.m03(); o[off+13]=m.m13(); o[off+14]=m.m23(); o[off+15]=m.m33();
    }

    /** Van der Corput / Halton sequence value for the given (1-based) index and base. */
    private static float halton(int index, int base) {
        float f = 1.0f;
        float r = 0.0f;
        int i = index;
        while (i > 0) {
            f /= base;
            r += f * (i % base);
            i /= base;
        }
        return r;
    }

    // --- Accessors (used by the MV pass / SR evaluate path in later phases) ---

    public static boolean hasPreviousFrame()      { return hasPrevious; }
    public static Matrix4f currentViewProjection() { return currentViewProj; }
    public static Matrix4f previousViewProjection() { return previousViewProj; }

    /** Jitter offset in pixels, x. Range [-0.5, 0.5]. */
    public static float jitterPixelsX() { return jitterX; }
    /** Jitter offset in pixels, y. Range [-0.5, 0.5]. */
    public static float jitterPixelsY() { return jitterY; }

    public static int renderWidth()  { return renderWidth; }
    public static int renderHeight() { return renderHeight; }
    public static long frameCounter() { return frameCounter; }
    public static int jitterPhaseCount() { return jitterPhaseCount; }

    public static void setJitterPhaseCount(int n) {
        if (n >= 1 && n <= 64) jitterPhaseCount = n;
    }

    public static void reset() {
        hasPrevious = false;
        currentSetThisFrame = false;
        jitterIndex = 0;
        jitterX = jitterY = 0.0f;
    }
}
