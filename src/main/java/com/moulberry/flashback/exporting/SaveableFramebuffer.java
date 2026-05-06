package com.moulberry.flashback.exporting;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.fabricmc.loader.api.FabricLoader;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class SaveableFramebuffer implements AutoCloseable {

    /**
     * When VulkanMod is present, raw GL PBO calls (glGenBuffers, glReadPixels,
     * glMapBuffer) will crash because VulkanMod replaces the OpenGL backend.
     * We detect it once at class-load time and use a GPU-abstraction path instead.
     */
    private static final boolean VULKANMOD_PRESENT =
        FabricLoader.getInstance().isModLoaded("vulkanmod");

    // OpenGL PBO path (vanilla / Sodium)
    private int pboId = -1;

    // Vulkan-safe path: GPU buffer abstraction provided by blaze3d
    private @Nullable GpuBuffer gpuDownloadBuffer;

    public @Nullable FloatBuffer audioBuffer;
    private boolean isDownloading = false;

    public SaveableFramebuffer() {}

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    public void startDownload(GpuTexture gpuTexture, int width, int height) {
        if (this.isDownloading) {
            throw new IllegalStateException("Can't start downloading while already downloading");
        }
        this.isDownloading = true;

        if (VULKANMOD_PRESENT) {
            startDownloadVulkan(gpuTexture, width, height);
        } else {
            startDownloadGL(gpuTexture, width, height);
        }
    }

    public NativeImage finishDownload(int width, int height) {
        if (!this.isDownloading) {
            throw new IllegalStateException("Can't finish downloading before download has started");
        }
        this.isDownloading = false;

        if (VULKANMOD_PRESENT) {
            return finishDownloadVulkan(width, height);
        } else {
            return finishDownloadGL(width, height);
        }
    }

    @Override
    public void close() {
        if (this.pboId != -1) {
            GL30C.glDeleteBuffers(this.pboId);
            this.pboId = -1;
        }
        if (this.gpuDownloadBuffer != null) {
            this.gpuDownloadBuffer.close();
            this.gpuDownloadBuffer = null;
        }
    }

    // ------------------------------------------------------------------
    // OpenGL PBO path (vanilla / Sodium / Iris)
    // ------------------------------------------------------------------

    private void startDownloadGL(GpuTexture gpuTexture, int width, int height) {
        if (this.pboId == -1) {
            this.pboId = GL30C.glGenBuffers();
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.pboId);
            GL30C.glBufferData(GL30C.GL_PIXEL_PACK_BUFFER, (long) width * height * 4, GL30C.GL_STREAM_READ);
            GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);
        }

        int fbo = ((GlTexture) gpuTexture).getFbo(((GlDevice) RenderSystem.getDevice()).directStateAccess(), null);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.pboId);
        GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
        GlStateManager._pixelStore(GL11.GL_PACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_PACK_SKIP_ROWS, 0);
        GL30C.glReadPixels(0, 0, width, height, GL30C.GL_RGBA, GL30C.GL_UNSIGNED_BYTE, 0);
        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private NativeImage finishDownloadGL(int width, int height) {
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);

        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.pboId);
        ByteBuffer buffer = GL30C.glMapBuffer(GL30C.GL_PIXEL_PACK_BUFFER, GL30C.GL_READ_ONLY);

        if (buffer == null) {
            throw new IllegalStateException("OpenGL error occurred while mapping buffer");
        }

        MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), nativeImage.pixels, nativeImage.size);

        GL30C.glUnmapBuffer(GL30C.GL_PIXEL_PACK_BUFFER);
        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);

        return nativeImage;
    }

    // ------------------------------------------------------------------
    // Vulkan-safe path
    // Uses blaze3d GpuBuffer which VulkanMod routes through its own
    // Vulkan staging-buffer abstraction — no raw GL_PIXEL_PACK_BUFFER.
    // ------------------------------------------------------------------

    private void startDownloadVulkan(GpuTexture gpuTexture, int width, int height) {
        int size = width * height * 4;

        // Re-allocate if the buffer is too small
        if (this.gpuDownloadBuffer == null || this.gpuDownloadBuffer.size() < size) {
            if (this.gpuDownloadBuffer != null) {
                this.gpuDownloadBuffer.close();
            }
            // USAGE_COPY_DST: target of copyTextureToBuffer
            // USAGE_MAP_READ: so we can map it for CPU readback
            this.gpuDownloadBuffer = RenderSystem.getDevice().createBuffer(
                () -> "flashback pixel download",
                GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_READ,
                (long) size
            );
        }

        // Async copy: texture → buffer via device command encoder
        RenderSystem.getDevice().createCommandEncoder()
            .copyTextureToBuffer(gpuTexture, this.gpuDownloadBuffer, 0L, () -> {}, 0, 0, 0, width, height);
    }

    private NativeImage finishDownloadVulkan(int width, int height) {
        NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, width, height, false);

        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView view = encoder.mapBuffer(this.gpuDownloadBuffer, true, false)) {
            ByteBuffer data = view.data();
            if (data == null) {
                throw new IllegalStateException("Failed to map GPU download buffer (Vulkan path)");
            }
            MemoryUtil.memCopy(MemoryUtil.memAddress(data), nativeImage.pixels, nativeImage.size);
        }

        return nativeImage;
    }
}
