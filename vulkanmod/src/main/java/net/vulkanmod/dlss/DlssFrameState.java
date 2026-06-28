package net.vulkanmod.dlss;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
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

    /** Master switch — when false (default for now) nothing here affects rendering. */
    public static boolean enabled = false;
    /** Whether the computed jitter should actually be applied to the projection (Phase 3+). */
    public static boolean applyJitter = false;

    private static final Logger LOGGER = LogManager.getLogger("VulkanMod-DLSS");
    private static final boolean DEBUG = Boolean.getBoolean("mcdlss.debug");

    /**
     * Call once at the start of each frame, before the world's view-projection is set.
     * Rolls current→previous and advances the jitter phase.
     */
    public static void beginFrame() {
        frameCounter++;

        // Roll the view-projection: this frame's "previous" is last frame's "current".
        if (currentSetThisFrame) {
            previousViewProj.set(currentViewProj);
            hasPrevious = true;
        }
        currentSetThisFrame = false;

        Window w = Minecraft.getInstance().getWindow();
        renderWidth = w.getWidth();
        renderHeight = w.getHeight();

        // Advance Halton jitter. Index 0 of Halton is 0; start at 1.
        jitterIndex = (jitterIndex + 1) % jitterPhaseCount;
        int h = jitterIndex + 1;
        jitterX = halton(h, 2) - 0.5f;   // [-0.5, 0.5] pixels
        jitterY = halton(h, 3) - 0.5f;

        if (DEBUG && (frameCounter <= 6 || frameCounter % 600 == 0)) {
            LOGGER.info("[DLSS Phase2] frame={} {}x{} jitterIdx={}/{} jitter=({}, {}) px  hasPrev={}",
                    frameCounter, renderWidth, renderHeight, jitterIndex, jitterPhaseCount,
                    String.format("%+.4f", jitterX), String.format("%+.4f", jitterY), hasPrevious);
        }
    }

    /**
     * Provide the world's view (model-view) and projection matrices for this frame.
     * Called from the matrix-upload path; idempotent within a frame (same camera VP).
     */
    public static void setViewProjection(Matrix4f modelView, Matrix4f projection) {
        // VP = P * MV
        currentViewProj.set(projection).mul(modelView);
        currentSetThisFrame = true;
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
