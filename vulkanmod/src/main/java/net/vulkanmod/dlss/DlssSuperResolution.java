package net.vulkanmod.dlss;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageCopy;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Live in-frame DLSS Super Resolution (DLAA mode: native→native AI antialiasing, the
 * least-invasive SR variant — no render-resolution change). Runs the proven DLSS evaluate
 * on the rendered frame and composites the result back. Gated by {@code -Dmcdlss.dlss}
 * (default OFF), so it cannot affect the default rendering path.
 *
 * <p>Injected at the end of the main pass (color is a render target, depth is readable).
 * Note: motion vectors are a zero buffer for now (no live depth-sampled MV pass yet), so
 * this is DLAA without motion compensation — expect ghosting under motion until the MV pass
 * is wired. The point of this stage is to get DLSS running on real frames end-to-end.
 */
public final class DlssSuperResolution {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod-DLSS");
    private DlssSuperResolution() {}

    public static boolean enabled = Boolean.getBoolean("mcdlss.dlss");
    private static boolean failed = false;

    private static VulkanImage output;   // native-res DLSS output (UAV)
    private static VulkanImage mvZero;    // native-res motion vectors (zero for now)
    private static int width, height;
    private static long framesRun = 0;
    private static final float[] consts = new float[40];

    /** Run DLSS on the rendered color (using depth), composite the result back into color. */
    public static void render(VkCommandBuffer cmd, VulkanImage color, VulkanImage depth, int w, int h) {
        if (!enabled || failed) return;
        if (!NativeBridge.isStreamlineInitialized() || !NativeBridge.dlssSupported) return;
        if (!DlssFrameState.hasPreviousFrame()) return;

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
                    NativeBridge.DLSS_DLAA, w, h, w, h, handles, layouts, formats, consts);

            if (r != 0) {
                failed = true;
                LOGGER.error("[DLSS-SR] evaluate failed ({}) — disabling in-frame DLSS.",
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

            if (framesRun++ == 0) LOGGER.info("[DLSS-SR] DLAA running in-frame at {}x{}", w, h);
            else if (framesRun % 600 == 0) LOGGER.info("[DLSS-SR] DLAA frames run: {}", framesRun);
        } catch (Throwable t) {
            failed = true;
            LOGGER.error("[DLSS-SR] in-frame DLSS failed — disabling: {}", t.toString());
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
}
