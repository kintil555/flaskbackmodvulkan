package com.moulberry.flashback.mixin.compat.vulkanmod;

import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod replaces Minecraft's OpenGL renderer with Vulkan.
 *
 * VulkanMod's MinecraftMixin does:
 *   1. @Inject preInitFrame() at HEAD of runTick → resource uploads/reset
 *   2. @Inject beginFrame() at HEAD of runTick   → acquires swapchain image, starts recording
 *   3. @Redirect blitToScreen()                  → VulkanMod's own blit: copies mainRenderTarget → swapchain
 *   4. @Redirect glfwSwapBuffers                 → endFrame() = submit commands + present
 *
 * The correct hook point is BEFORE blitToScreen() — at that moment:
 *   - The game has fully rendered to mainRenderTarget
 *   - VulkanMod hasn't yet copied mainRenderTarget to the swapchain
 *
 * By rendering ImGui into mainRenderTarget at this point, VulkanMod's blit
 * automatically carries our ImGui overlay to the final swapchain image.
 *
 * Hooking endFrame() is too late — the frame is already submitted/presented.
 */
@IfModLoaded("vulkanmod")
@Mixin(value = Minecraft.class, priority = 900)
public class MixinVulkanModRenderSystem {

    /**
     * Render the Flashback ImGui overlay into mainRenderTarget just before
     * VulkanMod's blitToScreen redirect copies mainRenderTarget → swapchain.
     * This ensures ImGui is included in the final presented frame.
     */
    @Inject(
        method = "runTick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen()V",
            shift = At.Shift.BEFORE
        ),
        require = 0
    )
    public void flashback$beforeBlitToScreen(boolean bl, CallbackInfo ci) {
        if (!net.minecraft.client.renderer.RenderSystem.isOnRenderThread()) return;
        ReplayUI.drawOverlay();
    }
}
