package net.kaiten;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.vulkan.VK10.*;

/**
 * AMD FidelityFX Super Resolution 1.0 — spatial upscaler that works on any GPU.
 *
 * Two-pass compute:
 *   1. EASU (Edge-Adaptive Spatial Upsampling) — upscales low-res → display-res
 *   2. RCAS (Robust Contrast-Adaptive Sharpening) — sharpens the display-res result
 *
 * Uses the same low-res framebuffer infrastructure as DLSS (KaitenRenderState),
 * providing an alternative upscaling backend for non-NVIDIA GPUs.
 */
public final class KaitenFSR {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod-DLSS");
    private KaitenFSR() {}

    public static boolean enabled = false;

    // --- Vulkan objects ---
    private static long easuPipeline      = VK_NULL_HANDLE;
    private static long easuPipeLayout    = VK_NULL_HANDLE;
    private static long easuDSLayout      = VK_NULL_HANDLE;

    private static long rcasPipeline      = VK_NULL_HANDLE;
    private static long rcasPipeLayout    = VK_NULL_HANDLE;
    private static long rcasDSLayout      = VK_NULL_HANDLE;

    private static long descPool          = VK_NULL_HANDLE;
    private static long easuDescSet       = VK_NULL_HANDLE;
    private static long rcasDescSet       = VK_NULL_HANDLE;

    // Intermediate images
    private static VulkanImage easuOutput;  // EASU upscaled result → RCAS input
    private static VulkanImage rcasOutput;  // RCAS sharpened result → copy to display
    private static int lastDisplayW, lastDisplayH;

    // --- Cached bound state for descriptor update check ---
    private static long boundEasuInput   = 0;
    private static long boundEasuOutput  = 0;
    private static long boundRcasInput   = 0;
    private static long boundRcasOutput  = 0;

    private static final int WORKGROUP = 16;

    /** Call once during device init to compile FSR shaders and create pipelines. */
    public static void init() {
        if (easuPipeline != VK_NULL_HANDLE) return; // already initialized
        try {
            VkDevice device = Vulkan.getVkDevice();

            // ---- EASU ----
            String easuSrc = readResource("/assets/vulkanmod/shaders/kaiten/fsr_easu.comp");
            SPIRVUtils.SPIRV easuSpv = SPIRVUtils.compileShader("fsr_easu.comp", easuSrc, SPIRVUtils.ShaderKind.COMPUTE_SHADER);
            long easuModule = createShaderModule(device, easuSpv.bytecode());

            // 2 storage images
            easuDSLayout = createDescriptorSetLayout_2StorageImages(device);
            easuPipeLayout = createPipelineLayout(device, easuDSLayout, 16); // 16 bytes push constants
            easuPipeline = createComputePipeline(device, easuModule, easuPipeLayout);
            vkDestroyShaderModule(device, easuModule, null);

            // ---- RCAS ----
            String rcasSrc = readResource("/assets/vulkanmod/shaders/kaiten/fsr_rcas.comp");
            SPIRVUtils.SPIRV rcasSpv = SPIRVUtils.compileShader("fsr_rcas.comp", rcasSrc, SPIRVUtils.ShaderKind.COMPUTE_SHADER);
            long rcasModule = createShaderModule(device, rcasSpv.bytecode());

            rcasDSLayout = createDescriptorSetLayout_2StorageImages(device);
            rcasPipeLayout = createPipelineLayout(device, rcasDSLayout, 8); // 8 bytes push constants
            rcasPipeline = createComputePipeline(device, rcasModule, rcasPipeLayout);
            vkDestroyShaderModule(device, rcasModule, null);

            // ---- Descriptor pool + sets ----
            createDescriptorPoolAndSets(device);

            LOGGER.info("[FSR] Compute pipelines compiled (EASU + RCAS)");
        } catch (Throwable t) {
            LOGGER.warn("[FSR] Initialization failed: {}", t.toString());
            destroy();
        }
    }

    /**
     * Upscale low-res color to display resolution using FSR 1.0.
     * Called from within an actively recording graphics command buffer.
     *
     * @param cmd          recording command buffer
     * @param lowResColor  low-res input (renderW x renderH), must be in GENERAL layout
     * @param displayImage output image (displayW x displayH), must be in GENERAL layout
     * @param renderW      input width
     * @param renderH      input height
     * @param displayW     output width
     * @param displayH     output height
     */
    public static void upscale(VkCommandBuffer cmd, VulkanImage lowResColor, VulkanImage displayImage,
                               int renderW, int renderH, int displayW, int displayH) {
        if (!enabled || easuPipeline == VK_NULL_HANDLE) return;
        try {
            ensureTargets(displayW, displayH, displayImage.format);

            VkDevice device = Vulkan.getVkDevice();

            // --- Pass 1: EASU (low-res → intermediate) ---
            updateEasuDescriptorSet(device, lowResColor, easuOutput);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, easuPipeline);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, easuPipeLayout,
                        0, stack.longs(easuDescSet), null);
                ByteBuffer pc = stack.malloc(16);
                pc.putInt(0, renderW).putInt(4, renderH).putInt(8, displayW).putInt(12, displayH);
                vkCmdPushConstants(cmd, easuPipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            }
            vkCmdDispatch(cmd, (displayW + WORKGROUP - 1) / WORKGROUP,
                    (displayH + WORKGROUP - 1) / WORKGROUP, 1);

            // Barrier between passes
            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, null, null);

            // --- Pass 2: RCAS (intermediate → rcasOutput, sharpened) ---
            updateRcasDescriptorSet(device, easuOutput, rcasOutput);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, rcasPipeline);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, rcasPipeLayout,
                        0, stack.longs(rcasDescSet), null);
                ByteBuffer pc = stack.malloc(8);
                pc.putInt(0, displayW).putInt(4, displayH);
                vkCmdPushConstants(cmd, rcasPipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            }
            vkCmdDispatch(cmd, (displayW + WORKGROUP - 1) / WORKGROUP,
                    (displayH + WORKGROUP - 1) / WORKGROUP, 1);

            // Barrier: compute writes → transfer reads
            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, null, null, null);

            // --- Copy: rcasOutput → display (swapchain may not support storage) ---
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
                region.get(0).srcSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.get(0).dstSubresource()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
                region.get(0).extent().set(displayW, displayH, 1);
                vkCmdCopyImage(cmd, rcasOutput.getId(), VK_IMAGE_LAYOUT_GENERAL,
                        displayImage.getId(), VK_IMAGE_LAYOUT_GENERAL, region);
            }

        } catch (Throwable t) {
            LOGGER.error("[FSR] Upscale failed: {}", t.toString());
            enabled = false;
        }
    }

    // ========== Internals ==========

    private static void ensureTargets(int displayW, int displayH, int format) {
        if (easuOutput != null && lastDisplayW == displayW && lastDisplayH == displayH) return;
        if (easuOutput != null) { try { easuOutput.free(); } catch (Throwable ignored) {} }
        if (rcasOutput != null) { try { rcasOutput.free(); } catch (Throwable ignored) {} }
        lastDisplayW = displayW; lastDisplayH = displayH;
        int usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        easuOutput = VulkanImage.builder(displayW, displayH).setFormat(format)
                .setUsage(usage).setLinearFiltering(false).setClamp(true).createVulkanImage();
        rcasOutput = VulkanImage.builder(displayW, displayH).setFormat(format)
                .setUsage(usage).setLinearFiltering(false).setClamp(true).createVulkanImage();
    }

    private static void createDescriptorPoolAndSets(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Pool: 4 storage images (2 for EASU, 2 for RCAS)
            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(4);

            VkDescriptorPoolCreateInfo dpInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType$Default().pPoolSizes(poolSize).maxSets(2);

            long[] p = new long[1];
            check(vkCreateDescriptorPool(device, dpInfo, null, p), "FSR descriptor pool");
            descPool = p[0];

            // Allocate sets
            VkDescriptorSetAllocateInfo allocInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType$Default().descriptorPool(descPool)
                    .pSetLayouts(stack.longs(easuDSLayout, rcasDSLayout));
            long[] sets = new long[2];
            check(vkAllocateDescriptorSets(device, allocInfo, sets), "FSR descriptor sets");
            easuDescSet = sets[0];
            rcasDescSet = sets[1];
        }
    }

    private static void updateEasuDescriptorSet(VkDevice device, VulkanImage input, VulkanImage output) {
        long inView = input.getImageView();
        long outView = output.getImageView();
        if (boundEasuInput == inView && boundEasuOutput == outView) return;
        boundEasuInput = inView; boundEasuOutput = outView;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(2, stack);
            imgInfo.get(0).imageView(inView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            imgInfo.get(1).imageView(outView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType$Default().dstSet(easuDescSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(imgInfo.get(0).address(), 1));
            writes.get(1).sType$Default().dstSet(easuDescSet).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(imgInfo.get(1).address(), 1));

            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    private static void updateRcasDescriptorSet(VkDevice device, VulkanImage input, VulkanImage output) {
        long inView = input.getImageView();
        long outView = output.getImageView();
        if (boundRcasInput == inView && boundRcasOutput == outView) return;
        boundRcasInput = inView; boundRcasOutput = outView;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(2, stack);
            imgInfo.get(0).imageView(inView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            imgInfo.get(1).imageView(outView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);

            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType$Default().dstSet(rcasDescSet).dstBinding(0)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(imgInfo.get(0).address(), 1));
            writes.get(1).sType$Default().dstSet(rcasDescSet).dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    .descriptorCount(1)
                    .pImageInfo(VkDescriptorImageInfo.create(imgInfo.get(1).address(), 1));

            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    private static long createDescriptorSetLayout_2StorageImages(VkDevice device) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
            for (int i = 0; i < 2; i++) {
                binds.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(binds);
            long[] p = new long[1];
            check(vkCreateDescriptorSetLayout(device, info, null, p), "FSR DS layout");
            return p[0];
        }
    }

    private static long createPipelineLayout(VkDevice device, long dsLayout, int pushSize) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(pushSize);

            VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default().pSetLayouts(stack.longs(dsLayout)).pPushConstantRanges(pcr);

            long[] p = new long[1];
            check(vkCreatePipelineLayout(device, info, null, p), "FSR pipeline layout");
            return p[0];
        }
    }

    private static long createComputePipeline(VkDevice device, long module, long layout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(module).pName(stack.UTF8("main"));

            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack)
                    .sType$Default();
            info.get(0).stage(stage).layout(layout);

            long[] p = new long[1];
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, info, null, p), "FSR compute pipeline");
            return p[0];
        }
    }

    private static long createShaderModule(VkDevice device, ByteBuffer spirv) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default().pCode(spirv);
            long[] p = new long[1];
            check(vkCreateShaderModule(device, info, null, p), "FSR shader module");
            return p[0];
        }
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = KaitenFSR.class.getResourceAsStream(path)) {
            if (in == null) throw new RuntimeException("missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void check(int res, String what) {
        if (res != VK_SUCCESS) throw new RuntimeException(what + " failed: " + res);
    }

    public static void destroy() {
        VkDevice device = Vulkan.getVkDevice();
        if (easuPipeline != VK_NULL_HANDLE) { vkDestroyPipeline(device, easuPipeline, null); easuPipeline = VK_NULL_HANDLE; }
        if (rcasPipeline != VK_NULL_HANDLE) { vkDestroyPipeline(device, rcasPipeline, null); rcasPipeline = VK_NULL_HANDLE; }
        if (easuPipeLayout != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device, easuPipeLayout, null); easuPipeLayout = VK_NULL_HANDLE; }
        if (rcasPipeLayout != VK_NULL_HANDLE) { vkDestroyPipelineLayout(device, rcasPipeLayout, null); rcasPipeLayout = VK_NULL_HANDLE; }
        if (easuDSLayout != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(device, easuDSLayout, null); easuDSLayout = VK_NULL_HANDLE; }
        if (rcasDSLayout != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(device, rcasDSLayout, null); rcasDSLayout = VK_NULL_HANDLE; }
        if (descPool != VK_NULL_HANDLE) { vkDestroyDescriptorPool(device, descPool, null); descPool = VK_NULL_HANDLE; }
        if (easuOutput != null) { try { easuOutput.free(); } catch (Throwable ignored) {} easuOutput = null; }
        if (rcasOutput != null) { try { rcasOutput.free(); } catch (Throwable ignored) {} rcasOutput = null; }
        boundEasuInput = boundEasuOutput = boundRcasInput = boundRcasOutput = 0;
    }
}
