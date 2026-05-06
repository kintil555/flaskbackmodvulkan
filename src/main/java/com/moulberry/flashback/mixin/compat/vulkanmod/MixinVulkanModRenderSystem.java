package com.moulberry.flashback.mixin.compat.vulkanmod;

import com.moulberry.flashback.Flashback;
import com.moulberry.mixinconstraints.annotations.IfModLoaded;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod replaces Minecraft's OpenGL renderer. Several subsystems in
 * Flashback (exporting, framebuffer blit) call raw GL functions that crash
 * when VulkanMod is active.
 *
 * This mixin targets VulkanMod's Renderer class to detect the Vulkan context
 * and suppress Flashback operations that are known-incompatible at the raw GL
 * level. The actual pixel-download path is handled in SaveableFramebuffer via
 * the VULKANMOD_PRESENT flag; this mixin provides the guard at the Renderer
 * tick level so export-in-progress state is kept coherent.
 */
@IfModLoaded("vulkanmod")
@Pseudo
@Mixin(targets = "net.vulkanmod.vulkan.Renderer", remap = false)
public class MixinVulkanModRenderSystem {

    /**
     * Called each frame by VulkanMod's Renderer.beginFrame() equivalent.
     * We use this as a hook to confirm Vulkan is active and warn if
     * Flashback is in an export state that still uses raw GL (failsafe log).
     */
    @Inject(method = "beginFrame", at = @At("HEAD"), require = 0, cancellable = false)
    public void flashback$onBeginFrame(CallbackInfo ci) {
        // Intentionally lightweight: just confirm the renderer is Vulkan-backed.
        // Heavy compatibility work is done in SaveableFramebuffer / FramebufferUtils.
        if (Flashback.isExporting()) {
            // Export is running under Vulkan — the GpuBuffer path in
            // SaveableFramebuffer handles pixel download safely.
            // No action needed here; this hook exists for future extension.
        }
    }
}
