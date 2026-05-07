package com.moulberry.flashback.mixin.compat.vulkanmod;

import com.mojang.blaze3d.systems.RenderSystem;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod frame lifecycle (from source analysis):
 *
 *   runTick():
 *     preInitFrame()  → buffer resets, chunk uploads
 *     beginFrame()    → vkAcquireNextImageKHR, vkBeginCommandBuffer, mainPass.begin() [Renderer.java:288]
 *     ... game renders INSIDE this render pass (VulkanMod routes all draw calls here) ...
 *     blitToScreen()  → runs via GL compat (no real effect in Vulkan path)
 *   glfwSwapBuffers() → @Redirect → Renderer.endFrame():
 *     [319] mainPass.end()    ← end the render pass
 *     [321] final uploads
 *     [338] submit command buffer
 *     [354] vkQueuePresentKHR
 *
 * ImGui MUST be rendered INSIDE the active VulkanMod render pass,
 * i.e. AFTER beginFrame() and BEFORE endFrame() line 319.
 *
 * Hook: @Inject into Renderer.endFrame() at HEAD — the render pass is
 * still open at this point. VulkanMod's GL compatibility layer intercepts
 * all GL draw calls (glDrawElements, glBindTexture, etc.) and routes them
 * into the active Vulkan command buffer. CustomImGuiImplGl3 works correctly.
 */
@IfModLoaded("vulkanmod")
@Pseudo
@Mixin(targets = "net.vulkanmod.vulkan.Renderer", remap = false)
public class MixinVulkanModRenderSystem {

    // drawOverlay() for the Vulkan path is now triggered from MixinGui.render_vulkanDrawOverlay()
    // (at Gui.render() RETURN) so that it runs inside an active render pass with
    // GuiGraphics available. This hook is intentionally disabled.
    //
    // @Inject(method = "endFrame", at = @At("HEAD"), require = 0)
    // public void flashback$onEndFrame(CallbackInfo ci) { ... }
}
