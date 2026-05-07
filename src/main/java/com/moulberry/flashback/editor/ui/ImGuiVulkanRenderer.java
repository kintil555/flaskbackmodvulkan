package com.moulberry.flashback.editor.ui;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.moulberry.flashback.visuals.ShaderManager;
import imgui.moulberry90.ImDrawData;
import imgui.moulberry90.ImFontAtlas;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.ImVec4;
import imgui.moulberry90.type.ImInt;
import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.OptionalInt;

/**
 * Vulkan-safe ImGui renderer for Flashback.
 *
 * ROOT CAUSE OF THE BUG:
 *   When VulkanMod is active, there is NO active OpenGL context.
 *   CustomImGuiImplGl3 calls glGenTextures() → returns 0 (null texture),
 *   and renderDrawData() calls glDrawElements() → no-op.
 *   Result: every ImGui window (Timeline, Visuals, Export…) produces zero pixels.
 *
 * FIX:
 *   Use Blaze3D GpuDevice abstraction (RenderSystem.getDevice()) for everything.
 *   VulkanMod routes these calls to Vulkan; vanilla routes them to OpenGL.
 *
 * VERTEX FORMAT:
 *   ImDrawVert = 20 bytes: [pos.xy:8][uv.xy:8][col:4]
 *   DefaultVertexFormat.POSITION_TEX_COLOR = 24 bytes: [pos.xyz:12][uv.xy:8][col:4]
 *   We expand on the CPU by inserting pos.z=0.0f at offset 8 of each vertex
 *   before uploading to GpuBuffer — no custom vertex format needed.
 *
 * API notes (verified against this codebase):
 *   GpuDevice.createBuffer(label, flags, ByteBuffer)  — create+fill in one call
 *   getCmdListCmdBufferClipRect(ImVec4 dst, listIdx, cmdIdx) — fills dst.x/y/z/w
 *   getSamplerCache().getClampToEdge(FilterMode)       — correct sampler method
 */
public class ImGuiVulkanRenderer {

    // ── Font texture ──────────────────────────────────────────────────────────
    private GpuTexture     fontTexture;
    private GpuTextureView fontTextureView;

    // ── Reusable CPU staging buffer for vertex expansion ──────────────────────
    private ByteBuffer stagingBuffer;

    // ── Clip-rect scratch object (reused per draw-command) ────────────────────
    private final ImVec4 clipRect = new ImVec4();

    /** ImDrawVert size in bytes. */
    private static final int IMGUI_VTX = 20;
    /** DefaultVertexFormat.POSITION_TEX_COLOR size in bytes. */
    private static final int MC_VTX    = 24;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Upload the ImGui font atlas to a Blaze3D GpuTexture.
     * Must be called after {@link ImFontAtlas#build()}.
     */
    public void createFontsTexture() {
        destroyFontsTexture();

        ImFontAtlas fonts = ImGui.getIO().getFonts();
        ImInt widthOut  = new ImInt();
        ImInt heightOut = new ImInt();
        ByteBuffer pixels = fonts.getTexDataAsRGBA32(widthOut, heightOut);
        int w = widthOut.get();
        int h = heightOut.get();

        // USAGE_COPY_DST          → allow writeToTexture()
        // USAGE_RENDER_ATTACHMENT → required for textures bound in a RenderPass
        fontTexture = RenderSystem.getDevice().createTexture(
            () -> "imgui_font_atlas",
            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT,
            TextureFormat.RGBA8,
            w, h,
            1, // depthOrLayers
            1  // mipLevels
        );

        pixels.limit(w * h * 4).position(0);
        RenderSystem.getDevice().createCommandEncoder()
            .writeToTexture(fontTexture, pixels, NativeImage.Format.RGBA, 0, 0, 0, 0, w, h);

        fontTextureView = RenderSystem.getDevice().createTextureView(fontTexture);

        // Sentinel ID=1 — renderDrawData() maps all texture IDs to fontTextureView.
        fonts.setTexID(1);
    }

    public void destroyFontsTexture() {
        if (fontTextureView != null) { fontTextureView.close(); fontTextureView = null; }
        if (fontTexture     != null) { fontTexture.close();     fontTexture     = null; }
    }

    /**
     * Render all ImGui draw-data for this frame via Blaze3D RenderPass.
     * Call on the render thread after {@link ImGui#render()}.
     */
    public void renderDrawData(ImDrawData drawData) {
        if (drawData == null || !drawData.getValid()) return;
        if (fontTextureView == null) return;

        int cmdListsCount = drawData.getCmdListsCount();
        if (cmdListsCount == 0) return;

        float fbScaleX = drawData.getFramebufferScaleX();
        float fbScaleY = drawData.getFramebufferScaleY();
        float displayW = drawData.getDisplaySizeX() * fbScaleX;
        float displayH = drawData.getDisplaySizeY() * fbScaleY;
        if (displayW <= 0 || displayH <= 0) return;

        float clipOffX = drawData.getDisplayPosX();
        float clipOffY = drawData.getDisplayPosY();

        // Orthographic projection: ImGui uses top-left = (0,0).
        // ortho(L, R, Bottom, Top) — Bottom > Top flips the Y axis.
        Matrix4f ortho = new Matrix4f().ortho(
            clipOffX,
            clipOffX + drawData.getDisplaySizeX(),
            clipOffY + drawData.getDisplaySizeY(),
            clipOffY,
            -1.0f, 1.0f
        );

        var renderTarget = Minecraft.getInstance().getMainRenderTarget();

        for (int listIdx = 0; listIdx < cmdListsCount; listIdx++) {

            // ── Build expanded vertex buffer ──────────────────────────────────
            int vtxCount = drawData.getCmdListVtxBufferSize(listIdx);
            int srcBytes = vtxCount * IMGUI_VTX;
            int dstBytes = vtxCount * MC_VTX;

            ByteBuffer vtxSrc = drawData.getCmdListVtxBufferData(listIdx);
            vtxSrc.limit(srcBytes).position(0);

            // Grow staging buffer if needed (native memory, freed in dispose())
            if (stagingBuffer == null || stagingBuffer.capacity() < dstBytes) {
                if (stagingBuffer != null) MemoryUtil.memFree(stagingBuffer);
                stagingBuffer = MemoryUtil.memAlloc(Math.max(dstBytes, 4096 * MC_VTX));
            }
            stagingBuffer.limit(dstBytes).position(0);

            // Insert pos.z = 0.0f at byte offset 8 of each vertex
            // ImDrawVert: [px:4][py:4][u:4][v:4][col:4]       (20 bytes)
            // MC layout:  [px:4][py:4][pz:4][u:4][v:4][col:4] (24 bytes)
            for (int i = 0; i < vtxCount; i++) {
                int s = i * IMGUI_VTX;
                int d = i * MC_VTX;
                stagingBuffer.putFloat(d,      vtxSrc.getFloat(s));      // pos.x
                stagingBuffer.putFloat(d + 4,  vtxSrc.getFloat(s + 4));  // pos.y
                stagingBuffer.putFloat(d + 8,  0.0f);                    // pos.z = 0
                stagingBuffer.putFloat(d + 12, vtxSrc.getFloat(s + 8));  // uv.x
                stagingBuffer.putFloat(d + 16, vtxSrc.getFloat(s + 12)); // uv.y
                stagingBuffer.putInt  (d + 20, vtxSrc.getInt  (s + 16)); // color
            }
            stagingBuffer.position(0);

            // createBuffer(label, flags, ByteBuffer) — creates and uploads in one call
            // (mirrors FlashbackDrawBuffer.uploadVertexBuffer() pattern)
            GpuBuffer vtxGpu = RenderSystem.getDevice().createBuffer(
                () -> "imgui_vtx",
                GpuBuffer.USAGE_VERTEX,
                stagingBuffer
            );

            // ── Upload index buffer ───────────────────────────────────────────
            int idxCount = drawData.getCmdListIdxBufferSize(listIdx);
            int idxBytes = idxCount * 2; // ImDrawIdx = uint16
            ByteBuffer idxSrc = drawData.getCmdListIdxBufferData(listIdx);
            idxSrc.limit(idxBytes).position(0);

            GpuBuffer idxGpu = RenderSystem.getDevice().createBuffer(
                () -> "imgui_idx",
                GpuBuffer.USAGE_INDEX,
                idxSrc
            );

            // ── Issue draw commands ───────────────────────────────────────────
            int cmdCount = drawData.getCmdListCmdBufferSize(listIdx);
            for (int cmdIdx = 0; cmdIdx < cmdCount; cmdIdx++) {
                int elemCount = drawData.getCmdListCmdBufferElemCount(listIdx, cmdIdx);
                if (elemCount == 0) continue;

                int idxOffset = drawData.getCmdListCmdBufferIdxOffset(listIdx, cmdIdx);
                int vtxOffset = drawData.getCmdListCmdBufferVtxOffset(listIdx, cmdIdx);

                // getCmdListCmdBufferClipRect fills clipRect.x/y/z/w
                drawData.getCmdListCmdBufferClipRect(clipRect, listIdx, cmdIdx);
                float cx1 = (clipRect.x - clipOffX) * fbScaleX;
                float cy1 = (clipRect.y - clipOffY) * fbScaleY;
                float cx2 = (clipRect.z - clipOffX) * fbScaleX;
                float cy2 = (clipRect.w - clipOffY) * fbScaleY;

                if (cx1 >= displayW || cy1 >= displayH || cx2 < 0 || cy2 < 0) continue;

                // DynamicTransforms = orthographic projection + colour modulator
                GpuBufferSlice dynTransforms = RenderSystem.getDynamicUniforms().writeTransform(
                    ortho,
                    new Vector4f(1.0f, 1.0f, 1.0f, 1.0f),
                    new Vector3f(),
                    new Matrix4f()
                );

                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                        .createRenderPass(
                            () -> "imgui_draw",
                            renderTarget.getColorTextureView(),
                            OptionalInt.empty()
                        )) {

                    pass.setPipeline(ShaderManager.IMGUI_DRAW);
                    RenderSystem.bindDefaultUniforms(pass);
                    pass.setUniform("DynamicTransforms", dynTransforms);

                    int sx = (int) Math.max(0.0f, cx1);
                    int sy = (int) Math.max(0.0f, cy1);
                    int sw = (int) Math.min(displayW, cx2) - sx;
                    int sh = (int) Math.min(displayH, cy2) - sy;
                    if (sw > 0 && sh > 0) {
                        pass.enableScissor(sx, sy, sw, sh);
                    }

                    pass.setVertexBuffer(0, vtxGpu);
                    pass.setIndexBuffer(idxGpu, VertexFormat.IndexType.SHORT);
                    // getClampToEdge is the correct method — getLinear does not exist
                    pass.bindTexture("InSampler", fontTextureView,
                        RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));

                    // drawIndexed(baseVertex, firstIndex, indexCount, instanceCount)
                    pass.drawIndexed(vtxOffset, idxOffset, elemCount, 1);
                }
            }

            // Free per-frame GPU buffers (created fresh every frame)
            vtxGpu.close();
            idxGpu.close();
        }
    }

    /** Release all GPU and CPU resources. */
    public void dispose() {
        destroyFontsTexture();
        if (stagingBuffer != null) {
            MemoryUtil.memFree(stagingBuffer);
            stagingBuffer = null;
        }
    }
}
