package net.vulkanmod.vulkan.pass;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.framebuffer.Framebuffer;
import net.vulkanmod.vulkan.framebuffer.RenderPass;
import net.vulkanmod.vulkan.framebuffer.SwapChain;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRect2D;

import java.util.function.IntSupplier;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

public class DefaultMainPass implements MainPass {

    public static DefaultMainPass create() {
        return new DefaultMainPass();
    }

    private Framebuffer mainFramebuffer;

    private RenderPass mainRenderPass;
    private RenderPass auxRenderPass;

    private GpuTexture[] colorAttachmentTextures;
    private GpuTextureView[] colorAttachmentTextureViews;
    IntSupplier imageIdxSupplier;
    private GpuTexture depthAttachmentTexture;

    DefaultMainPass() {
        createResources();
    }

    private void createResources() {
        if (this.mainFramebuffer != null) {
            if (this.mainFramebuffer != Renderer.getInstance()
                                                .getSwapChain()) {
                this.mainFramebuffer.cleanUp(true);
            }

            this.mainRenderPass.cleanUp();
            this.auxRenderPass.cleanUp();
        }

        Framebuffer framebuffer;
        if (Renderer.getInstance().getSwapChain().hasImages()) {
            framebuffer = Renderer.getInstance().getSwapChain();
        }
        else {
            framebuffer = Framebuffer.builder(10, 10, 1, true)
                                     .build();
        }

        this.mainFramebuffer = framebuffer;

        createRenderPasses();
        createAttachmentTextures();
    }

    private void createRenderPasses() {
        RenderPass.Builder builder = RenderPass.builder(this.mainFramebuffer);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_DONT_CARE, VK_ATTACHMENT_STORE_OP_STORE);

        this.mainRenderPass = builder.build();

        // Create an auxiliary RenderPass needed in case of main target rebinding
        builder = RenderPass.builder(this.mainFramebuffer);
        builder.getColorAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getDepthAttachmentInfo().setOps(VK_ATTACHMENT_LOAD_OP_LOAD, VK_ATTACHMENT_STORE_OP_STORE);
        builder.getColorAttachmentInfo().setFinalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        this.auxRenderPass = builder.build();
    }

    @Override
    public void begin(VkCommandBuffer commandBuffer, MemoryStack stack) {
        Framebuffer framebuffer = this.mainFramebuffer;
        // When DLSS/FSR upscaling is active, render the world to the low-res
        // framebuffer instead of the swapchain. The upscale happens in end().
        if (net.kaiten.KaitenRenderState.isUpscaling()) {
            framebuffer = net.kaiten.KaitenRenderState.getRenderFramebuffer(this.mainFramebuffer);
        }

        VulkanImage colorAttachment = framebuffer.getColorAttachment();
        colorAttachment.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

        Renderer.getInstance().beginRenderPass(this.mainRenderPass, framebuffer);

        Renderer.setViewport(0, 0, framebuffer.getWidth(), framebuffer.getHeight(), stack);

        VkRect2D.Buffer pScissor = framebuffer.scissor(stack);
        vkCmdSetScissor(commandBuffer, 0, pScissor);
    }

    @Override
    public void end(VkCommandBuffer commandBuffer) {
        Renderer.getInstance().endRenderPass(commandBuffer);
        // --- DLSS/FSR upscaling: switch to swapchain for DLSS + present ---
        if (net.kaiten.KaitenRenderState.isUpscaling()) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VulkanImage swapColor = this.mainFramebuffer.getColorAttachment();
                swapColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                Renderer.getInstance().beginRenderPass(this.mainRenderPass, this.mainFramebuffer);
                Renderer.setViewport(0, 0, this.mainFramebuffer.getWidth(), this.mainFramebuffer.getHeight(), stack);
            }
        }

        if (this.mainFramebuffer == Renderer.getInstance().getSwapChain() || net.kaiten.KaitenRenderState.isUpscaling()) {
            VulkanImage color = this.mainFramebuffer.getColorAttachment();
            VulkanImage depth = this.mainFramebuffer.getDepthAttachment();
            int w = this.mainFramebuffer.getWidth();
            int h = this.mainFramebuffer.getHeight();

            // DLSS-SR (DLAA) / FSR upscaling
            // gated -Dmcdlss.dlss or via FSR backend selection
            try {
                String backend = net.kaiten.KaitenRenderState.getBackend();
                if ("fsr".equals(backend) && net.kaiten.KaitenFSR.enabled) {
                    VulkanImage lowResColor = net.kaiten.KaitenRenderState.getLowResColor();
                    if (lowResColor != null) {
                        try (MemoryStack stack = MemoryStack.stackPush()) {
                            lowResColor.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_GENERAL);
                            color.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_GENERAL);
                        }
                        net.kaiten.KaitenFSR.upscale(commandBuffer, lowResColor, color,
                                net.kaiten.KaitenRenderState.renderWidth(),
                                net.kaiten.KaitenRenderState.renderHeight(), w, h);
                        // FSR output is in GENERAL; done here, leave for FG/present below
                    }
                } else {
                    net.kaiten.DlssSuperResolution.render(commandBuffer, color, depth, w, h);
                }
            } catch (Throwable t) {
                net.kaiten.NativeBridge.LOGGER.warn("DLSS-SR/FSR stage error: {}", t.toString());
            }

            // DLSS Frame Generation, gated -Dmcdlss.fg: AI-interpolates frames. FG must
            // run AFTER SR (if both are active SR upscales first) and BEFORE the present
            // layout transition. When FG is active, the Streamline interposer owns the
            // present call â€” we only transition to GENERAL (not PRESENT_SRC_KHR).
            boolean fgActive = false;
            try {
                net.kaiten.DlssFrameGeneration.render(commandBuffer, color, depth, null, w, h);
                fgActive = net.kaiten.NativeBridge.frameGenActive;
            } catch (Throwable t) {
                net.kaiten.NativeBridge.LOGGER.warn("DLSS-FG stage error: {}", t.toString());
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                if (fgActive) {
                    // FG writes the interpolated frame to the backbuffer; leave in GENERAL
                    // so the interposer can read it during present. SL handles the final
                    // transition to PRESENT_SRC_KHR internally.
                    color.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_GENERAL);
                } else {
                    color.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
                }
            }
        }

        int result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer:" + result);
        }
    }

    @Override
    public void cleanUp() {
        this.mainRenderPass.cleanUp();
        this.auxRenderPass.cleanUp();
    }

    @Override
    public void onResize() {
        createResources();
    }

    public void rebindMainTarget() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        // Do not rebind if the framebuffer is already bound
        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        if (boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
            return;

        Renderer.getInstance().endRenderPass(commandBuffer);
        Renderer.getInstance().beginRenderPass(this.auxRenderPass, this.mainFramebuffer);
    }

    @Override
    public void bindAsTexture() {
        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

        // Check if render pass is using the framebuffer
        RenderPass boundRenderPass = Renderer.getInstance().getBoundRenderPass();
        if (boundRenderPass == this.mainRenderPass || boundRenderPass == this.auxRenderPass)
            Renderer.getInstance().endRenderPass(commandBuffer);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            this.mainFramebuffer.getColorAttachment().transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }

        VTextureSelector.bindTexture(this.mainFramebuffer.getColorAttachment());
    }

    @Override
    public Framebuffer getMainFramebuffer() {
        return mainFramebuffer;
    }

    @Override
    public GpuTexture getColorAttachment() {
        return this.colorAttachmentTextures[this.imageIdxSupplier.getAsInt()];
    }

    @Override
    public GpuTextureView getColorAttachmentView() {
        return this.colorAttachmentTextureViews[this.imageIdxSupplier.getAsInt()];
    }

    @Override
    public GpuTexture getDepthAttachment() {
        return this.depthAttachmentTexture;
    }

    private void createAttachmentTextures() {
        VkGpuDevice device = (VkGpuDevice) RenderSystem.getDevice();

        SwapChain swapChain = Renderer.getInstance().getSwapChain();
        if (this.mainFramebuffer == swapChain) {
            var swapChainImages = swapChain.getImages();

            int imageCount = swapChainImages.size();
            this.colorAttachmentTextures = new GpuTexture[imageCount];
            this.colorAttachmentTextureViews = new GpuTextureView[imageCount];

            for (int i = 0; i < imageCount; ++i) {
                VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(swapChainImages.get(i));
                GpuTextureView attachmentTextureView = device.createTextureView(attachmentTexture);
                this.colorAttachmentTextures[i] = attachmentTexture;
                this.colorAttachmentTextureViews[i] = attachmentTextureView;
            }

            this.imageIdxSupplier = Renderer::getCurrentImage;
        }
        else {
            this.colorAttachmentTextures = new GpuTexture[1];
            this.colorAttachmentTextureViews = new GpuTextureView[1];

            VkGpuTexture attachmentTexture = device.gpuTextureFromVulkanImage(this.mainFramebuffer.getColorAttachment());
            GpuTextureView attachmentTextureView = device.createTextureView(attachmentTexture);
            this.colorAttachmentTextures[0] = attachmentTexture;
            this.colorAttachmentTextureViews[0] = attachmentTextureView;

            // Always return idx 0 as there's only 1 image
            this.imageIdxSupplier = () -> 0;
        }

        this.depthAttachmentTexture = device.gpuTextureFromVulkanImage(this.mainFramebuffer.getDepthAttachment());
    }
}
