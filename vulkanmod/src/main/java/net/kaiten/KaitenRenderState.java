package net.kaiten;

import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.texture.VulkanImage;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Manages the low-res render target and optimal render resolution for DLSS/FSR upscaling.
 * When upscaling is active, the world is rendered to a smaller framebuffer and then
 * AI-upscaled to the swapchain. This gives the "FPS goes up" payoff.
 */
public final class KaitenRenderState {
    private KaitenRenderState() {}

    private static Framebuffer lowResFB;
    private static int renderWidth, renderHeight;
    private static int displayWidth, displayHeight;
    private static boolean upscaling = false;
    private static int currentMode = NativeBridge.DLSS_DLAA;
    private static String currentBackend = "dlss";  // "dlss" or "fsr"

    /** Whether true upscaling (low-res render → upscale → native) is active. */
    public static boolean isUpscaling() { return upscaling; }

    /** Which backend is active. */
    public static String getBackend() { return currentBackend; }

    public static int renderWidth()  { return renderWidth; }
    public static int renderHeight() { return renderHeight; }
    public static int displayWidth()  { return displayWidth; }
    public static int displayHeight() { return displayHeight; }

    /** Call when the window resizes or upscaling settings change. */
    public static void update(int displayW, int displayH, int dlssMode, String backend) {
        displayWidth = displayW;
        displayHeight = displayH;
        currentMode = dlssMode;
        currentBackend = backend != null ? backend : "dlss";

        if ("fsr".equals(currentBackend)) {
            // FSR: use quality preset scale factors (same as DLSS modes)
            double scale = getFsrScale(dlssMode);
            if (scale >= 1.0) {
                upscaling = false;
                renderWidth = displayW;
                renderHeight = displayH;
                freeLowResFB();
                return;
            }
            renderWidth = (int) Math.max(1, displayW * scale);
            renderHeight = (int) Math.max(1, displayH * scale);
            upscaling = true;
            ensureLowResFB();
            return;
        }

        // DLSS path
        if (dlssMode == NativeBridge.DLSS_DLAA || dlssMode <= 0) {
            upscaling = false;
            renderWidth = displayW;
            renderHeight = displayH;
            freeLowResFB();
            return;
        }

        // Query DLSS for the optimal render resolution at this quality mode.
        try {
            String opt = NativeBridge.slDlssOptimalSettingsNative(displayW, displayH, dlssMode);
            if (opt != null && opt.contains("render ")) {
                String[] parts = opt.split("render ")[1].split("x");
                int rw = Integer.parseInt(parts[0].trim());
                int rh = Integer.parseInt(parts[1].split(" ")[0].trim());
                if (rw > 0 && rh > 0 && rw < displayW) {
                    renderWidth = rw;
                    renderHeight = rh;
                    upscaling = true;
                    ensureLowResFB();
                    return;
                }
            }
        } catch (Throwable ignored) {}

        // Fallback: DLAA mode
        upscaling = false;
        renderWidth = displayW;
        renderHeight = displayH;
        freeLowResFB();
    }

    /** FSR 1.0 quality preset → render scale factor. */
    private static double getFsrScale(int mode) {
        return switch (mode) {
            case NativeBridge.DLSS_ULTRA_PERF -> 1.0 / 3.0;    // 33% → 3x upscale
            case NativeBridge.DLSS_PERF -> 0.5;                  // 50%
            case NativeBridge.DLSS_BALANCED -> 0.5858;           // ~59%
            case NativeBridge.DLSS_QUALITY -> 0.6667;            // ~67%
            case NativeBridge.DLSS_ULTRA_QUALITY -> 0.7692;      // ~77%
            default -> 1.0;                                      // DLAA = native
        };
    }

    /** Returns the framebuffer to render the world into (low-res or native). */
    public static Framebuffer getRenderFramebuffer(Framebuffer swapchain) {
        if (upscaling && lowResFB != null) return lowResFB;
        return swapchain;
    }

    /** Returns the low-res color image for DLSS input (null if not upscaling). */
    public static VulkanImage getLowResColor() {
        return (upscaling && lowResFB != null) ? lowResFB.getColorAttachment() : null;
    }

    /** Returns the low-res depth image for DLSS input (null if not upscaling). */
    public static VulkanImage getLowResDepth() {
        return (upscaling && lowResFB != null) ? lowResFB.getDepthAttachment() : null;
    }

    private static void ensureLowResFB() {
        if (lowResFB != null && lowResFB.getWidth() == renderWidth && lowResFB.getHeight() == renderHeight)
            return;
        freeLowResFB();
        // Use same format as swapchain (RGBA8/BGRA8). Create a simple color+depth framebuffer.
        lowResFB = Framebuffer.builder(renderWidth, renderHeight, 1, true).build();
        NativeBridge.LOGGER.info("[Kaiten] Low-res framebuffer: {}x{} (upscaling to {}x{})",
                renderWidth, renderHeight, displayWidth, displayHeight);
    }

    private static void freeLowResFB() {
        if (lowResFB != null) {
            try { lowResFB.cleanUp(true); } catch (Throwable ignored) {}
            lowResFB = null;
        }
    }

    /** Call on shutdown. */
    public static void destroy() { freeLowResFB(); }

    /**
     * Returns the jitter offset in NDC space (range [-0.5,0.5] mapped to [-1/renderW, +1/renderW]).
     * DLSS needs sub-pixel jitter applied to the projection matrix.
     */
    public static float jitterNdcX(float pixelJitterX) { return pixelJitterX / (float)renderWidth; }
    public static float jitterNdcY(float pixelJitterY) { return pixelJitterY / (float)renderHeight; }
}
