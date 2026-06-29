package net.vulkanmod.dlss;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static org.lwjgl.vulkan.VK10.*;

/**
 * On-device checks that need a live Vulkan device (run after device creation, gated by
 * {@code -Dmcdlss.gputest}). Validates the building blocks of the motion-vector pass on the
 * actual GPU before they're wired into the live frame.
 */
public final class DlssGpuTest {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod-DLSS");
    private DlssGpuTest() {}

    public static void run() {
        LOGGER.info("[DLSS GpuTest] ==== begin ====");
        int pass = 0, fail = 0;

        // 1) Allocate the RG16F motion-vector render target on the GPU.
        try {
            VulkanImage img = VulkanImage.builder(256, 256)
                    .setFormat(VK_FORMAT_R16G16_SFLOAT)
                    .setUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                            | VK_IMAGE_USAGE_SAMPLED_BIT
                            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                    .setLinearFiltering(false)
                    .setClamp(true)
                    .createVulkanImage();
            long image = img.getId();
            long view = img.getImageView();
            boolean ok = image != 0L && view != 0L;
            LOGGER.info("[DLSS GpuTest]   {}  RG16F 256x256 target: image=0x{} view=0x{}",
                    ok ? "PASS" : "FAIL", Long.toHexString(image), Long.toHexString(view));
            if (ok) pass++; else fail++;
            img.free();
        } catch (Throwable t) {
            fail++;
            LOGGER.error("[DLSS GpuTest]   FAIL  RG16F target allocation: {}", t.toString());
        }

        // 2) Validate the GPU reprojection against the CPU reference (compute + readback).
        try {
            if (DlssComputeValidator.run()) pass++; else fail++;
        } catch (Throwable t) {
            fail++;
            LOGGER.error("[DLSS GpuTest]   FAIL  compute validator: {}", t.toString());
        }

        LOGGER.info("[DLSS GpuTest] ==== {} ({} passed, {} failed) ====",
                fail == 0 ? "ALL PASS" : "FAILURES", pass, fail);
    }
}
