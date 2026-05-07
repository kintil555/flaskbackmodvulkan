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
 * ── Root cause of the "UI invisible" bug ──────────────────────────────────────
 *
 * When VulkanMod is active, there is NO live OpenGL context.
 * CustomImGuiImplGl3 relies entirely on raw OpenGL calls:
 *   1. updateFontsTexture() → glGenTextures() returns 0 → fontAtlas.texID = 0
 *   2. renderDrawData()     → gl* calls are no-ops → zero pixels produced
 *
 * Result: Timeline, Visuals, Export windows — the entire Flashback UI — is
 * completely invisible when VulkanMod is loaded.
 *
 * ── Fix ───────────────────────────────────────────────────────────────────────
 *
 * Route everything through Blaze3D's GpuDevice abstraction, which VulkanMod
 * overrides to use Vulkan instead of OpenGL.
 *
 * ── Vertex format ─────────────────────────────────────────────────────────────
 *
 * ImDrawVert = 20 bytes: [pos.x:4][pos.y:4][uv.x:4][uv.y:4][col:4]
 * DefaultVertexFormat.POSITION_TEX_COLOR = 24 bytes:
 *   [pos.x:4][pos.y:4][pos.z:4][uv.x:4][uv.y:4][col:4]
 *
 * We expand each vertex on the CPU by inserting pos.z = 0.0f (4 zero bytes)
 * at offset 8 before uploading to GpuBuffer.  This costs one extra memcopy per
 * draw-list per frame but avoids registering a custom VertexFormat.
 */
public class ImGuiVulkanRenderer {

    // ── Font texture ──────────────────────────────────────────────────────────
    private GpuTexture     fontTexture;
    private GpuTextureView fontTextureView;

    // ── Reusable scratch buffers ──────────────────────────────────────────────
    /** CPU-side staging buffer (expanded vertex data). */
    private ByteBuffer stagingBuffer;

    /** GPU vertex buffer (POSITION_TEX_COLOR layout, 24 bytes/vertex). */
    private GpuBuffer vtxGpuBuffer;
    private int       vtxGpuCapacity;

    /** GPU index buffer (uint16). */
    private GpuBuffer idxGpuBuffer;
    private int       idxGpuCapacity;

    /** ImDrawVert size in bytes. */
    private static final int IMGUI_VTX = 20;
    /** DefaultVertexFormat.POSITION_TEX_COLOR size in bytes. */
    private static final int MC_VTX    = 24;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Upload the ImGui font atlas to a GpuTexture (Vulkan/GL-safe).
     * Must be called after {@link ImFontAtlas#build()} and any time fonts are rebuilt.
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

        // writeToTexture(target, buf, format, mipLevel, depth, offsetX, offsetY, w, h)
        pixels.limit(w * h * 4).position(0);
        RenderSystem.getDevice().createCommandEncoder()
            .writeToTexture(fontTexture, pixels, NativeImage.Format.RGBA, 0, 0, 0, 0, w, h);

        fontTextureView = RenderSystem.getDevice().createTextureView(fontTexture);

        // Sentinel ID = 1: all ImGui draw-calls reference the font atlas.
        fonts.setTexID(1);
    }

    /** Free font GPU resources. */
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

        // Orthographic projection: ImGui uses top-left as (0,0).
        // ortho(L, R, Bottom, Top, near, far) — Bottom > Top flips Y.
        Matrix4f ortho = new Matrix4f().ortho(
            clipOffX,
            clipOffX + drawData.getDisplaySizeX(),
            clipOffY + drawData.getDisplaySizeY(),
            clipOffY,
            -1.0f, 1.0f
        );

        var renderTarget = Minecraft.getInstance().getMainRenderTarget();

        for (int listIdx = 0; listIdx < cmdListsCount; listIdx++) {

            // ── Expand and upload vertices ────────────────────────────────────
            int vtxCount  = drawData.getCmdListVtxBufferSize(listIdx);
            int srcBytes  = vtxCount * IMGUI_VTX;
            int dstBytes  = vtxCount * MC_VTX;

            ByteBuffer vtxSrc = drawData.getCmdListVtxBufferData(listIdx);
            vtxSrc.limit(srcBytes).position(0);

            // Ensure staging buffer is large enough
            if (stagingBuffer == null || stagingBuffer.capacity() < dstBytes) {
                if (stagingBuffer != null) MemoryUtil.memFree(stagingBuffer);
                stagingBuffer = MemoryUtil.memAlloc(Math.max(dstBytes, 4096 * MC_VTX));
            }
            stagingBuffer.limit(dstBytes).position(0);

            // Expand: insert pos.z = 0.0f at byte offset 8 of each vertex
            // ImDrawVert: [px:4][py:4][u:4][v:4][col:4]  (20 bytes)
            // MC layout:  [px:4][py:4][pz:4][u:4][v:4][col:4]  (24 bytes)
            for (int i = 0; i < vtxCount; i++) {
                int src = i * IMGUI_VTX;
                int dst = i * MC_VTX;
                // pos.x, pos.y  (8 bytes)
                stagingBuffer.putFloat(dst,     vtxSrc.getFloat(src));
                stagingBuffer.putFloat(dst + 4, vtxSrc.getFloat(src + 4));
                // pos.z = 0.0f  (4 bytes, injected)
                stagingBuffer.putFloat(dst + 8, 0.0f);
                // uv.x, uv.y   (8 bytes)
                stagingBuffer.putFloat(dst + 12, vtxSrc.getFloat(src + 8));
                stagingBuffer.putFloat(dst + 16, vtxSrc.getFloat(src + 12));
                // color (4 bytes)
                stagingBuffer.putInt(dst + 20, vtxSrc.getInt(src + 16));
            }
            stagingBuffer.position(0);

            // Upload to GPU
            if (vtxGpuBuffer == null || vtxGpuCapacity < dstBytes) {
                if (vtxGpuBuffer != null) vtxGpuBuffer.close();
                int cap = Math.max(dstBytes, 4096 * MC_VTX);
                vtxGpuBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "imgui_vtx",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                    cap
                );
                vtxGpuCapacity = cap;
            }
            RenderSystem.getDevice().createCommandEncoder()
                .writeToBuffer(vtxGpuBuffer, stagingBuffer, 0);

            // ── Upload indices ────────────────────────────────────────────────
            int idxCount = drawData.getCmdListIdxBufferSize(listIdx);
            int idxBytes = idxCount * 2; // ImDrawIdx = uint16
            ByteBuffer idxData = drawData.getCmdListIdxBufferData(listIdx);

            if (idxGpuBuffer == null || idxGpuCapacity < idxBytes) {
                if (idxGpuBuffer != null) idxGpuBuffer.close();
                int cap = Math.max(idxBytes, 8192 * 2);
                idxGpuBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "imgui_idx",
                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
                    cap
                );
                idxGpuCapacity = cap;
            }
            idxData.limit(idxBytes).position(0);
            RenderSystem.getDevice().createCommandEncoder()
                .writeToBuffer(idxGpuBuffer, idxData, 0);

            // ── Issue draw commands ───────────────────────────────────────────
            int cmdCount = drawData.getCmdListCmdBufferSize(listIdx);
            for (int cmdIdx = 0; cmdIdx < cmdCount; cmdIdx++) {
                int elemCount = drawData.getCmdListCmdBufferElemCount(listIdx, cmdIdx);
                if (elemCount == 0) continue;

                int idxOffset = drawData.getCmdListCmdBufferIdxOffset(listIdx, cmdIdx);
                int vtxOffset = drawData.getCmdListCmdBufferVtxOffset(listIdx, cmdIdx);

                float cx1 = (drawData.getCmdListCmdBufferClipRect(listIdx, cmdIdx, 0) - clipOffX) * fbScaleX;
                float cy1 = (drawData.getCmdListCmdBufferClipRect(listIdx, cmdIdx, 1) - clipOffY) * fbScaleY;
                float cx2 = (drawData.getCmdListCmdBufferClipRect(listIdx, cmdIdx, 2) - clipOffX) * fbScaleX;
                float cy2 = (drawData.getCmdListCmdBufferClipRect(listIdx, cmdIdx, 3) - clipOffY) * fbScaleY;

                if (cx1 >= displayW || cy1 >= displayH || cx2 < 0 || cy2 < 0) continue;

                // Write per-draw transform into DynamicUniforms ring buffer
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

                    // Scissor: enableScissor(x, y, width, height)
                    int sx = (int) Math.max(0.0f, cx1);
                    int sy = (int) Math.max(0.0f, cy1);
                    int sw = (int) Math.min(displayW, cx2) - sx;
                    int sh = (int) Math.min(displayH, cy2) - sy;
                    if (sw > 0 && sh > 0) {
                        pass.enableScissor(sx, sy, sw, sh);
                    }

                    pass.setVertexBuffer(0, vtxGpuBuffer);
                    pass.setIndexBuffer(idxGpuBuffer, VertexFormat.IndexType.SHORT);
                    pass.bindTexture("InSampler", fontTextureView,
                        RenderSystem.getSamplerCache().getLinear(FilterMode.LINEAR));

                    // drawIndexed(baseVertex, firstIndex, indexCount, instanceCount)
                    pass.drawIndexed(vtxOffset, idxOffset, elemCount, 1);
                }
            }
        }
    }

    /** Release all GPU and CPU resources. */
    public void dispose() {
        destroyFontsTexture();
        if (vtxGpuBuffer != null) { vtxGpuBuffer.close(); vtxGpuBuffer = null; }
        if (idxGpuBuffer != null) { idxGpuBuffer.close(); idxGpuBuffer = null; }
        if (stagingBuffer != null) {
            MemoryUtil.memFree(stagingBuffer);
            stagingBuffer = null;
        }
    }
}
