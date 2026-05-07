package com.moulberry.flashback.mixin.compat.vulkanmod;

import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod replaces Minecraft's OpenGL renderer with Vulkan.
 *
 * Critically, VulkanMod's MinecraftMixin does:
 *   1. @Inject beginFrame() at HEAD of runTick → acquires swapchain image, starts recording
 *   2. @Redirect blitToScreen()                → NO-OP (removed entirely)
 *   3. @Redirect glfwSwapBuffers               → endFrame() = submit commands + present
 *
 * This means BEFORE/AFTER blitToScreen inject points are useless — blitToScreen
 * never runs. VulkanMod renders directly to its Vulkan swapchain image, bypassing
 * mainRenderTarget for final output. ImGui rendering to mainRenderTarget is invisible.
 *
 * FIX: Hook Renderer.endFrame() at HEAD — VulkanMod's command buffer is still
 * recording at this point. Our Blaze3D RenderPass calls (ImGuiVulkanRenderer)
 * get recorded into the same command buffer and appear in the final frame.
 */
@IfModLoaded("vulkanmod")
@Pseudo
@Mixin(targets = "net.vulkanmod.vulkan.Renderer", remap = false)
public class MixinVulkanModRenderSystem {

    /**
     * Render the Flashback ImGui overlay just before VulkanMod submits
     * and presents the frame. require=0 so a future VulkanMod rename
     * silently skips this rather than crashing.
     */
    @Inject(method = "endFrame", at = @At("HEAD"), require = 0)
    public void flashback$onEndFrame(CallbackInfo ci) {
        ReplayUI.drawOverlay();
    }
}
