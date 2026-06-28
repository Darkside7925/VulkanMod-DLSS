package net.vulkanmod.dlss;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;

/**
 * Deterministic, headless validation of {@link DlssFrameState} — the temporal basis the
 * motion-vector pass and DLSS-SR jitter depend on. Runs synthetic camera matrices through
 * the exact production methods and asserts:
 *   1. Halton(2,3) jitter produces the expected sub-pixel offsets.
 *   2. Static camera → frame-to-frame view-projection delta is ~0 (no motion vectors).
 *   3. Rotating camera → view-projection delta is clearly non-zero (motion present).
 *   4. The current→previous roll actually advances each frame.
 *
 * Gated by {@code -Dmcdlss.selftest=true}; logs PASS/FAIL per check.
 */
public final class DlssSelfTest {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod-DLSS");
    private DlssSelfTest() {}

    private static int passed = 0, failed = 0;

    public static boolean run() {
        passed = 0; failed = 0;
        LOGGER.info("[DLSS SelfTest] ==== begin ====");

        testHaltonJitter();
        testStaticCameraZeroMotion();
        testRotatingCameraHasMotion();
        testFrameRoll();
        testMotionVectorMath();
        testShadersCompile();

        LOGGER.info("[DLSS SelfTest] ==== {} ({} passed, {} failed) ====",
                failed == 0 ? "ALL PASS" : "FAILURES", passed, failed);
        return failed == 0;
    }

    // --- A realistic Vulkan-style perspective (matches the renderer: zZeroToOne) ---
    private static Matrix4f proj() {
        return new Matrix4f().perspective((float) Math.toRadians(70.0), 16f / 9f, 0.05f, 1000f, true);
    }

    // View matrix for a camera at the origin with the given yaw (degrees).
    private static Matrix4f view(float yawDeg) {
        return new Matrix4f()
                .rotateX((float) Math.toRadians(15.0))
                .rotateY((float) Math.toRadians(yawDeg))
                .translate(-8f, -72f, -16f);
    }

    private static void testHaltonJitter() {
        DlssFrameState.reset();
        Matrix4f p = proj(), v = view(0);

        DlssFrameState.beginFrame(1920, 1080);
        DlssFrameState.setViewProjection(v, p);
        check("Halton f1 X ≈ -0.2500", approx(DlssFrameState.jitterPixelsX(), -0.25f, 1e-4f));
        check("Halton f1 Y ≈ +0.1667", approx(DlssFrameState.jitterPixelsY(),  0.16667f, 1e-4f));

        DlssFrameState.beginFrame(1920, 1080);
        DlssFrameState.setViewProjection(v, p);
        check("Halton f2 X ≈ +0.2500", approx(DlssFrameState.jitterPixelsX(),  0.25f, 1e-4f));
        check("Halton f2 Y ≈ -0.3889", approx(DlssFrameState.jitterPixelsY(), -0.38889f, 1e-4f));

        boolean inRange = true;
        for (int i = 0; i < 64; i++) {
            DlssFrameState.beginFrame(1920, 1080);
            float jx = DlssFrameState.jitterPixelsX(), jy = DlssFrameState.jitterPixelsY();
            if (jx < -0.5f || jx > 0.5f || jy < -0.5f || jy > 0.5f) inRange = false;
        }
        check("Jitter always within [-0.5, 0.5] px over 64 frames", inRange);
    }

    private static void testStaticCameraZeroMotion() {
        DlssFrameState.reset();
        Matrix4f p = proj(), v = view(0);

        DlssFrameState.beginFrame(1920, 1080);
        DlssFrameState.setViewProjection(v, p);       // frame 1: no previous yet
        DlssFrameState.beginFrame(1920, 1080);
        DlssFrameState.setViewProjection(v, p);       // frame 2: identical camera

        check("Static: hasPrevious becomes true", DlssFrameState.hasPreviousFrame());
        float d = DlssFrameState.lastViewProjectionDelta();
        check("Static: view-projection delta ≈ 0 (was " + d + ")", d < 1e-3f);
    }

    private static void testRotatingCameraHasMotion() {
        DlssFrameState.reset();
        Matrix4f p = proj();

        DlssFrameState.beginFrame(1920, 1080);
        DlssFrameState.setViewProjection(view(0f), p);   // frame 1
        DlssFrameState.beginFrame(1920, 1080);
        DlssFrameState.setViewProjection(view(5f), p);   // frame 2: yaw +5°

        float d = DlssFrameState.lastViewProjectionDelta();
        check("Rotating: view-projection delta > 0.1 (was " + d + ")", d > 0.1f);
    }

    private static void testFrameRoll() {
        DlssFrameState.reset();
        Matrix4f p = proj();
        Matrix4f v0 = view(0f), v1 = view(10f);

        DlssFrameState.beginFrame(1920, 1080);
        DlssFrameState.setViewProjection(v0, p);
        Matrix4f vpFrame1 = new Matrix4f(DlssFrameState.currentViewProjection());

        DlssFrameState.beginFrame(1920, 1080);          // rolls current(f1) → previous
        DlssFrameState.setViewProjection(v1, p);

        check("Roll: previous == last frame's current",
                DlssFrameState.previousViewProjection().equals(vpFrame1, 1e-5f));
        check("Roll: current == this frame's VP",
                DlssFrameState.currentViewProjection().equals(new Matrix4f(p).mul(v1), 1e-5f));
    }

    private static void testMotionVectorMath() {
        Matrix4f p = proj();
        Matrix4f curVP  = new Matrix4f(p).mul(view(0f));
        Matrix4f prevVP = new Matrix4f(p).mul(view(5f));   // camera rotated +5° last→this frame
        Matrix4f invCur = new Matrix4f(curVP).invert();

        // Sample pixels across the screen at varied depths.
        float[][] samples = {
                {0.50f, 0.50f, 0.30f}, {0.25f, 0.75f, 0.50f},
                {0.80f, 0.20f, 0.70f}, {0.10f, 0.40f, 0.90f}
        };

        // 1) Round-trip: reconstruct world from (uv,depth) then re-project with curVP → recover (uv,depth).
        boolean roundTripOk = true;
        float maxErr = 0f;
        float[] out = new float[3];
        for (float[] s : samples) {
            Vector4f world = MotionVectorMath.reconstructWorld(s[0], s[1], s[2], invCur, new Vector4f());
            MotionVectorMath.projectToScreen(world, curVP, out);
            float e = Math.abs(out[0] - s[0]) + Math.abs(out[1] - s[1]) + Math.abs(out[2] - s[2]);
            maxErr = Math.max(maxErr, e);
            if (e > 1e-3f) roundTripOk = false;
        }
        check("MV: reconstruct↔project round-trip (max err " + String.format("%.2e", maxErr) + ")", roundTripOk);

        // 2) Static camera invariant: prevVP == curVP → motion vector is ~0 for every pixel.
        // Tolerance reflects float32 inverse-VP round-trip precision (~1e-4 UV = sub-pixel),
        // the same source bounded by the round-trip test above — not a tunable fudge factor.
        final float STATIC_EPS = 1e-3f;
        boolean staticZero = true;
        float maxStatic = 0f;
        Vector2f mv = new Vector2f();
        for (float[] s : samples) {
            MotionVectorMath.computeMotionVectorUV(s[0], s[1], s[2], invCur, curVP, mv);
            maxStatic = Math.max(maxStatic, mv.length());
            if (mv.length() > STATIC_EPS) staticZero = false;
        }
        check("MV: static camera → motion ≈ 0 within float precision (max "
                + String.format("%.2e", maxStatic) + " UV)", staticZero);

        // 3) Consistency: computed MV equals analytic (prevUV − curUV) from the same world point.
        boolean consistent = true;
        boolean anyMotion = false;
        for (float[] s : samples) {
            Vector4f world = MotionVectorMath.reconstructWorld(s[0], s[1], s[2], invCur, new Vector4f());
            MotionVectorMath.projectToScreen(world, prevVP, out);
            float expU = out[0] - s[0], expV = out[1] - s[1];
            MotionVectorMath.computeMotionVectorUV(s[0], s[1], s[2], invCur, prevVP, mv);
            if (Math.abs(mv.x - expU) > 1e-4f || Math.abs(mv.y - expV) > 1e-4f) consistent = false;
            if (mv.length() > 1e-3f) anyMotion = true;
        }
        check("MV: matches analytic reprojection under camera motion", consistent);
        check("MV: camera rotation produces non-zero motion", anyMotion);
    }

    private static void testShadersCompile() {
        compileOne("motion_vectors.vsh", net.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind.VERTEX_SHADER);
        compileOne("motion_vectors.fsh", net.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind.FRAGMENT_SHADER);
    }

    private static void compileOne(String name, net.vulkanmod.vulkan.shader.SPIRVUtils.ShaderKind kind) {
        String res = "/assets/vulkanmod/shaders/dlss/" + name;
        try (java.io.InputStream in = DlssSelfTest.class.getResourceAsStream(res)) {
            if (in == null) { check("Shader resource present: " + name, false); return; }
            String src = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            var spirv = net.vulkanmod.vulkan.shader.SPIRVUtils.compileShader(name, src, kind);
            boolean ok = spirv != null && spirv.bytecode() != null && spirv.bytecode().remaining() > 0;
            check("Shader compiles to SPIR-V: " + name, ok);
            if (spirv != null) spirv.free();
        } catch (Throwable t) {
            check("Shader compiles to SPIR-V: " + name + " (" + t + ")", false);
        }
    }

    private static boolean approx(float a, float b, float eps) { return Math.abs(a - b) <= eps; }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; LOGGER.info("[DLSS SelfTest]   PASS  {}", name); }
        else    { failed++; LOGGER.error("[DLSS SelfTest]   FAIL  {}", name); }
    }
}
