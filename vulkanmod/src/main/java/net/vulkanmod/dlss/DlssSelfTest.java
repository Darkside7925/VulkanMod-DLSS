package net.vulkanmod.dlss;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;

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

    private static boolean approx(float a, float b, float eps) { return Math.abs(a - b) <= eps; }

    private static void check(String name, boolean ok) {
        if (ok) { passed++; LOGGER.info("[DLSS SelfTest]   PASS  {}", name); }
        else    { failed++; LOGGER.error("[DLSS SelfTest]   FAIL  {}", name); }
    }
}
