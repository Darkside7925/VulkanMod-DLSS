package net.kaiten;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Live in-frame DLSS Super Resolution (DLAA mode: nativeâ†'native AI antialiasing, the
 * least-invasive SR variant â€" no render-resolution change). Runs the proven DLSS evaluate
 * on the rendered frame and composites the result back. Gated by {@code -Dmcdlss.dlss}
 * (default OFF), so it cannot affect the default rendering path.
 *
 * <p>Injected at the end of the main pass (color is a render target, depth is readable).
 * Note: motion vectors are a zero buffer for now (no live depth-sampled MV pass yet), so
 * this is DLAA without motion compensation â€" expect ghosting under motion until the MV pass
 * is wired. The point of this stage is to get DLSS running on real frames end-to-end.
 */
public final class DlssSuperResolution {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod-DLSS");
    private DlssSuperResolution() {}

    public static boolean enabled = Boolean.getBoolean("mcdlss.dlss");
    private static boolean failed = false;

    private static VulkanImage output;   // DLSS output at display resolution
    private static VulkanImage mvZero;   // zero MV: at render resolution for upscale, display for DLAA
    private static int width, height;    // display resolution
    private static int renderW, renderH; // render (input) resolution for upscale
    private static long framesRun = 0;
    private static int lastTransientErr = 0;
    private static final float[] consts = new float[40];

    /** Run DLSS on the rendered color (using depth), composite the result back into color. */
    public static void render(VkCommandBuffer cmd, VulkanImage color, VulkanImage depth, int w, int h) {
        if (!enabled || failed) {
            if (framesRun == 0 && !enabled) LOGGER.warn("[DLSS-SR] render skipped: enabled={} failed={}", enabled, failed);
            return;
        }
        if (!NativeBridge.isStreamlineInitialized() || !NativeBridge.dlssSupported) {
            if (framesRun == 0) LOGGER.warn("[DLSS-SR] render skipped: slInit={} dlssSupported={}", NativeBridge.isStreamlineInitialized(), NativeBridge.dlssSupported);
            return;
        }
        if (!DlssFrameState.hasPreviousFrame()) {
            if (framesRun == 0) LOGGER.info("[DLSS-SR] render waiting for previous frame... hasPrev={}", DlssFrameState.hasPreviousFrame());
            return;
        }

        // Read quality mode from Kaiten profile.
        int mode = NativeBridge.DLSS_DLAA;
        int renderW = w, renderH = h;
        try { var p = net.kaiten.config.KaitenConfig.INSTANCE.getActiveProfile(); if (p != null) {
            int desiredMode = p.dlssMode;
            if (desiredMode != NativeBridge.DLSS_DLAA && net.kaiten.KaitenRenderState.isUpscaling()) {
                mode = desiredMode;
                renderW = net.kaiten.KaitenRenderState.renderWidth();
                renderH = net.kaiten.KaitenRenderState.renderHeight();
                LOGGER.info("[DLSS-SR] upscaling mode={} {}x{} -> {}x{}", modeName(mode), renderW, renderH, w, h);
            } else if (desiredMode != NativeBridge.DLSS_DLAA) {
                if (framesRun == 0 || framesRun % 600 == 0)
                    LOGGER.info("[DLSS-SR] profile mode={} - using DLAA (upscaling not active)", modeName(desiredMode));
            }
        }} catch (Throwable ignored) {}

        try {
            ensureTargets(w, h, color.format);

            try (MemoryStack stack = stackPush()) {
                color.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                depth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                mvZero.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                output.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_GENERAL);
            }

            DlssFrameState.fillSrConstants(consts, 0f, 0f, 1f, 1f);

            long[] handles = {
                    color.getId(), color.getImageView(),
                    output.getId(), output.getImageView(),
                    depth.getId(), depth.getImageView(),
                    mvZero.getId(), mvZero.getImageView()
            };
            int[] layouts = { VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };
            int[] formats = { color.format, color.format, depth.format, VK_FORMAT_R16G16_SFLOAT };

            int r = NativeBridge.slDlssEvaluateNative(0, (int) DlssFrameState.frameCounter(), cmd.address(),
                    mode, w, h, renderW, renderH, handles, layouts, formats, consts);

            if (r != 0) {
                int errCode = r;
                // Transient errors: skip frame, retry next. Do NOT permanently disable.
                // 15=eErrorNGXFailed (resize/mode switch causes 10x10 swapchain)
                // 27=eErrorDuplicateConstants (SR+FG both set constants in same frame)
                // 5 =eErrorInvalidParameter (temporary during pipeline transitions)
                if (errCode == 15 || errCode == 27 || errCode == 5) {
                    if (errCode != lastTransientErr || framesRun % 300 == 0)
                        LOGGER.warn("[DLSS-SR] evaluate transient err={} frame={} - retrying", errCode, DlssFrameState.frameCounter());
                    lastTransientErr = errCode;
                    return;
                }
                failed = true;
                LOGGER.error("[DLSS-SR] evaluate failed ({}) - disabling in-frame DLSS.",
                        safeName(r));
                return;
            }

            // Composite the upscaled output back into the swapchain color image.
            try (MemoryStack stack = stackPush()) {
                output.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                color.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

                VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
                region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.extent().set(w, h, 1);
                vkCmdCopyImage(cmd, output.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        color.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            }

            if (framesRun++ == 0) {
                LOGGER.info("[DLSS-SR] DLAA running at native {}x{} (AI anti-aliasing)", w, h);
            } else if (framesRun % 600 == 0) {
                LOGGER.info("[DLSS-SR] DLAA frames: {} ({}x{})", framesRun, w, h);
            }
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("[DLSS-SR] in-frame DLSS failed - disabling: {}", t.toString());
        }
    }

    private static void ensureTargets(int w, int h, int colorFormat) {
        if (output != null && width == w && height == h) return;
        freeTargets();
        width = w; height = h;
        int outUsage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        output = VulkanImage.builder(w, h).setFormat(colorFormat).setUsage(outUsage)
                .setLinearFiltering(false).setClamp(true).createVulkanImage();
        int mvUsage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        mvZero = VulkanImage.builder(w, h).setFormat(VK_FORMAT_R16G16_SFLOAT).setUsage(mvUsage)
                .setLinearFiltering(false).setClamp(true).createVulkanImage();
    }

    public static void freeTargets() {
        if (output != null) { try { output.free(); } catch (Throwable ignored) {} output = null; }
        if (mvZero != null) { try { mvZero.free(); } catch (Throwable ignored) {} mvZero = null; }
    }

    private static String safeName(int r) {
        try { return NativeBridge.slResultNameNative(r); } catch (Throwable t) { return "code=" + r; }
    }

    private static String modeName(int m) {
        return switch (m) { case 4->"UltraPerf"; case 1->"Perf"; case 2->"Balanced"; case 3->"Quality"; case 5->"UltraQuality"; default->"DLAA"; };
    }

    /** Called from DefaultMainPass.end() when KaitenRenderState.isUpscaling(). */
    public static void renderUpscale(VkCommandBuffer cmd,
            VulkanImage lowResColor, VulkanImage lowResDepth,
            int renderW, int renderH,
            VulkanImage outputColor, int displayW, int displayH) {
        if (!enabled || failed) return;
        if (!NativeBridge.isStreamlineInitialized() || !NativeBridge.dlssSupported) return;
        if (!DlssFrameState.hasPreviousFrame()) return;

        int mode = NativeBridge.DLSS_DLAA;
        try { var p = net.kaiten.config.KaitenConfig.INSTANCE.getActiveProfile(); if (p != null) mode = p.dlssMode; } catch (Throwable ignored) {}
        if (mode == NativeBridge.DLSS_DLAA) { render(cmd, outputColor, lowResDepth, displayW, displayH); return; }

        try {
            ensureUpscaleTargets(displayW, displayH, renderW, renderH, outputColor.format);

            try (MemoryStack stack = stackPush()) {
                lowResColor.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                lowResDepth.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                mvZero.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                output.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_GENERAL);
            }

            DlssFrameState.fillSrConstants(consts, 0f, 0f, 1f, 1f);

            long[] handles = { lowResColor.getId(), lowResColor.getImageView(),
                    output.getId(), output.getImageView(),
                    lowResDepth.getId(), lowResDepth.getImageView(),
                    mvZero.getId(), mvZero.getImageView() };
            int[] layouts = { VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL };
            int[] formats = { lowResColor.format, outputColor.format, lowResDepth.format, VK_FORMAT_R16G16_SFLOAT };

            int r = NativeBridge.slDlssEvaluateNative(0, (int) DlssFrameState.frameCounter(), cmd.address(),
                    mode, displayW, displayH, renderW, renderH, handles, layouts, formats, consts);

            if (r != 0) {
                int errCode = r;
                if (errCode == 15 || errCode == 27 || errCode == 5) { // transient errors
                    if (errCode != lastTransientErr || framesRun % 300 == 0)
                        LOGGER.warn("[DLSS-SR] upscale transient err={} - retrying", errCode);
                    lastTransientErr = errCode;
                    return;
                }
                failed = true; LOGGER.error("[DLSS-SR] upscale failed ({})", safeName(r)); return; }

            try (MemoryStack stack = stackPush()) {
                output.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                outputColor.transitionImageLayout(stack, cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
                region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.extent().set(displayW, displayH, 1);
                vkCmdCopyImage(cmd, output.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        outputColor.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            }

            if (framesRun++ == 0) LOGGER.info("[DLSS-SR] {} upscaling: {}x{} -> {}x{}", modeName(mode), renderW, renderH, displayW, displayH);
            else if (framesRun % 300 == 0) LOGGER.info("[DLSS-SR] {} frames: {} ({}x{}->{}x{})", modeName(mode), framesRun, renderW, renderH, displayW, displayH);
        } catch (Throwable t) { failed = true; LOGGER.error("[DLSS-SR] upscale failed: {}", t.toString()); }
    }

    private static void ensureUpscaleTargets(int dw, int dh, int rw, int rh, int colorFormat) {
        if (output != null && width == dw && height == dh && renderW == rw && renderH == rh) return;
        freeTargets();
        width = dw; height = dh; renderW = rw; renderH = rh;
        int outUsage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        output = VulkanImage.builder(dw, dh).setFormat(colorFormat).setUsage(outUsage)
                .setLinearFiltering(false).setClamp(true).createVulkanImage();
        // MV buffer MUST match the input (render) resolution, not display resolution.
        // DLSS reads one MV per input pixel to reproject into the output.
        int mvUsage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        mvZero = VulkanImage.builder(rw, rh).setFormat(VK_FORMAT_R16G16_SFLOAT).setUsage(mvUsage)
                .setLinearFiltering(false).setClamp(true).createVulkanImage();
    }
}
