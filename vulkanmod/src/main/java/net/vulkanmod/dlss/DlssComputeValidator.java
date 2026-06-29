package net.vulkanmod.dlss;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.ComputeQueue;
import net.vulkanmod.vulkan.shader.SPIRVUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Autonomous GPU validation of the motion-vector reprojection — no screen, no eyes.
 *
 * Runs the {@code motion_vectors_cs.comp} compute shader over a synthetic per-pixel depth
 * buffer (storage buffer in), reads the motion-vector output back (storage buffer out), and
 * compares every pixel to {@link MotionVectorMath} (the CPU formula proven by DlssSelfTest).
 * If they match, the GPU executes the reprojection correctly with real per-pixel depth.
 */
public final class DlssComputeValidator {
    private static final Logger LOGGER = LogManager.getLogger("VulkanMod-DLSS");
    private DlssComputeValidator() {}

    private static final int W = 32, H = 32, N = W * H;

    /** @return true if the GPU output matches the CPU reference within tolerance. */
    public static boolean run() {
        VkDevice device = Vulkan.getVkDevice();

        // --- Test matrices (same shape as DlssSelfTest): camera rotates +5 degrees between frames ---
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), (float) W / H, 0.05f, 1000f, true);
        Matrix4f curVP  = new Matrix4f(proj).mul(view(0f));
        Matrix4f prevVP = new Matrix4f(proj).mul(view(5f));
        Matrix4f invCur = new Matrix4f(curVP).invert();

        // --- Synthetic per-pixel depth: a gradient in [0.2, 0.8] (avoids near/far singularities) ---
        Buffer depthBuf = new Buffer("dlss_depth_in", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, MemoryTypes.HOST_MEM);
        Buffer motionBuf = new Buffer("dlss_motion_out", VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, MemoryTypes.HOST_MEM);
        depthBuf.createBuffer((long) N * Float.BYTES);
        motionBuf.createBuffer((long) N * 2 * Float.BYTES);

        float[] depth = new float[N];
        long dPtr = depthBuf.getDataPtr();
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int idx = y * W + x;
                float d = 0.2f + 0.6f * (x / (float) (W - 1));
                depth[idx] = d;
                MemoryUtil.memPutFloat(dPtr + (long) idx * Float.BYTES, d);
            }
        }

        long shaderModule = 0, dsLayout = 0, pipeLayout = 0, pipeline = 0, descPool = 0;
        boolean ok = false;
        try (MemoryStack stack = stackPush()) {
            // --- Shader module ---
            String src = readResource("/assets/vulkanmod/shaders/dlss/motion_vectors_cs.comp");
            SPIRVUtils.SPIRV spirv = SPIRVUtils.compileShader("motion_vectors_cs.comp", src, SPIRVUtils.ShaderKind.COMPUTE_SHADER);
            VkShaderModuleCreateInfo smInfo = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv.bytecode());
            long[] pMod = new long[1];
            check(vkCreateShaderModule(device, smInfo, null, pMod), "shader module");
            shaderModule = pMod[0];

            // --- Descriptor set layout: 2 storage buffers, compute stage ---
            VkDescriptorSetLayoutBinding.Buffer binds = VkDescriptorSetLayoutBinding.calloc(2, stack);
            for (int i = 0; i < 2; i++) {
                binds.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            VkDescriptorSetLayoutCreateInfo dslInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(binds);
            long[] p = new long[1];
            check(vkCreateDescriptorSetLayout(device, dslInfo, null, p), "ds layout");
            dsLayout = p[0];

            // --- Pipeline layout with push constants (2 mat4 + 2 uint = 136 bytes) ---
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT).offset(0).size(136);
            VkPipelineLayoutCreateInfo plInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsLayout)).pPushConstantRanges(pcr);
            check(vkCreatePipelineLayout(device, plInfo, null, p), "pipeline layout");
            pipeLayout = p[0];

            // --- Compute pipeline ---
            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack).sType$Default()
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT).module(shaderModule).pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpInfo = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default();
            cpInfo.get(0).stage(stage).layout(pipeLayout);
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, cpInfo, null, p), "compute pipeline");
            pipeline = p[0];

            // --- Descriptor pool + set ---
            VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
            poolSize.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(2);
            VkDescriptorPoolCreateInfo dpInfo = VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().pPoolSizes(poolSize).maxSets(1);
            check(vkCreateDescriptorPool(device, dpInfo, null, p), "descriptor pool");
            descPool = p[0];

            VkDescriptorSetAllocateInfo dsAlloc = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(descPool).pSetLayouts(stack.longs(dsLayout));
            long[] pSet = new long[1];
            check(vkAllocateDescriptorSets(device, dsAlloc, pSet), "alloc descriptor set");
            long descSet = pSet[0];

            VkDescriptorBufferInfo.Buffer dbi = VkDescriptorBufferInfo.calloc(2, stack);
            dbi.get(0).buffer(depthBuf.getId()).offset(0).range(VK_WHOLE_SIZE);
            dbi.get(1).buffer(motionBuf.getId()).offset(0).range(VK_WHOLE_SIZE);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            for (int i = 0; i < 2; i++) {
                writes.get(i).sType$Default().dstSet(descSet).dstBinding(i).descriptorCount(1)
                        .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                        .pBufferInfo(VkDescriptorBufferInfo.create(dbi.get(i).address(), 1));
            }
            vkUpdateDescriptorSets(device, writes, null);

            // --- Push constants: invVP (col-major), prevVP, width, height ---
            java.nio.ByteBuffer pc = stack.malloc(136);
            invCur.get(0, pc);
            prevVP.get(64, pc);
            pc.putInt(128, W);
            pc.putInt(132, H);

            // --- Record + submit on the compute queue ---
            ComputeQueue cq = DeviceManager.getComputeQueue();
            CommandPool.CommandBuffer cb = cq.beginCommands();
            VkCommandBuffer cmd = cb.getHandle();
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeLayout, 0, stack.longs(descSet), null);
            vkCmdPushConstants(cmd, pipeLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);
            vkCmdDispatch(cmd, (W + 7) / 8, (H + 7) / 8, 1);
            long fence = cq.submitCommands(cb);
            vkWaitForFences(device, fence, true, Long.MAX_VALUE);

            // --- Read back + compare to MotionVectorMath ---
            long mPtr = motionBuf.getDataPtr();
            int mismatches = 0;
            float maxErr = 0f;
            Vector2f expected = new Vector2f();
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int idx = y * W + x;
                    float gx = MemoryUtil.memGetFloat(mPtr + (long) idx * 8);
                    float gy = MemoryUtil.memGetFloat(mPtr + (long) idx * 8 + 4);
                    float u = (x + 0.5f) / W, v = (y + 0.5f) / H;
                    MotionVectorMath.computeMotionVectorUV(u, v, depth[idx], invCur, prevVP, expected);
                    float e = Math.abs(gx - expected.x) + Math.abs(gy - expected.y);
                    maxErr = Math.max(maxErr, e);
                    // Tolerance = float32 inverse-VP round-trip precision (GPU FMA vs CPU
                    // rounding diverge by ~1e-4 UV — sub-pixel), same bound as DlssSelfTest.
                    if (e > 1e-3f) mismatches++;
                }
            }
            ok = (mismatches == 0);
            LOGGER.info("[DLSS ComputeValidator] {}  GPU vs CPU over {} px: mismatches={} maxErr={}",
                    ok ? "PASS" : "FAIL", N, mismatches, String.format("%.2e", maxErr));
        } catch (Throwable t) {
            LOGGER.error("[DLSS ComputeValidator] FAIL — {}", t.toString());
        } finally {
            if (pipeline != 0) vkDestroyPipeline(device, pipeline, null);
            if (descPool != 0) vkDestroyDescriptorPool(device, descPool, null);
            if (pipeLayout != 0) vkDestroyPipelineLayout(device, pipeLayout, null);
            if (dsLayout != 0) vkDestroyDescriptorSetLayout(device, dsLayout, null);
            if (shaderModule != 0) vkDestroyShaderModule(device, shaderModule, null);
            depthBuf.scheduleFree();
            motionBuf.scheduleFree();
        }
        return ok;
    }

    private static Matrix4f view(float yawDeg) {
        return new Matrix4f().rotateX((float) Math.toRadians(15.0)).rotateY((float) Math.toRadians(yawDeg))
                .translate(-8f, -72f, -16f);
    }

    private static void check(int res, String what) {
        if (res != VK_SUCCESS) throw new RuntimeException(what + " failed: " + res);
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = DlssComputeValidator.class.getResourceAsStream(path)) {
            if (in == null) throw new RuntimeException("missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
