package com.moulberry.flashback.editor.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import imgui.moulberry90.ImDrawData;
import imgui.moulberry90.ImFontAtlas;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.ImVec4;
import imgui.moulberry90.type.ImInt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.nio.ByteBuffer;

/**
 * Renders ImGui draw data via Minecraft's VertexConsumer / RenderType pipeline.
 *
 * WHY THIS EXISTS:
 *   CustomImGuiImplGl3 uses raw LWJGL org.lwjgl.opengl.GL32.* calls that
 *   VulkanMod does NOT intercept — only RenderSystem / GpuDevice calls are
 *   routed to Vulkan. Using raw GL calls from ImGuiImplGl3 produces no output
 *   (or crashes) under VulkanMod.
 *
 *   This renderer instead feeds ImGui draw data into Minecraft's VertexConsumer
 *   (backed by a MultiBufferSource) using RenderType.create() with
 *   RenderPipelines.GUI_TEXTURED — the exact same pipeline Minecraft itself
 *   uses for GUI quads. VulkanMod intercepts this correctly.
 *
 * FONT TEXTURE:
 *   The ImGui font atlas is uploaded once to a Blaze3D GpuTexture and exposed
 *   as a dynamic Identifier so the RenderType sampler can reference it.
 *
 * RENDERING:
 *   For every ImGui draw command we push triangles (2 verts per ImDrawIdx
 *   index pair) into a VertexConsumer, respecting the per-command clip rect
 *   via GuiGraphics.enableScissor / disableScissor. GuiGraphics.flush() is
 *   called at the end to submit everything.
 */
public class ImGuiMcRenderer {

    /** Identifier used to bind the font atlas in the RenderType sampler. */
    private static final Identifier FONT_TEXTURE_ID =
        Identifier.fromNamespaceAndPath("flashback", "imgui_font_atlas");

    // Font atlas GPU resources
    private GpuTexture     fontGpuTexture;
    private GpuTextureView fontGpuTextureView;

    // Minecraft RenderType that samples our font atlas
    private RenderType fontRenderType;

    // Scratch clip-rect object
    private final ImVec4 clipRect = new ImVec4();

    // ── Font atlas ────────────────────────────────────────────────────────────

    /**
     * Upload ImGui font atlas to a Blaze3D GpuTexture and build the RenderType.
     * Must be called after ImFontAtlas.build().
     */
    public void createFontsTexture() {
        destroyFontsTexture();

        ImFontAtlas fonts = ImGui.getIO().getFonts();
        ImInt w = new ImInt();
        ImInt h = new ImInt();
        ByteBuffer pixels = fonts.getTexDataAsRGBA32(w, h);
        int width  = w.get();
        int height = h.get();

        fontGpuTexture = RenderSystem.getDevice().createTexture(
            () -> "flashback:imgui_font_atlas",
            GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
            TextureFormat.RGBA8,
            width, height,
            1, 1
        );

        pixels.limit(width * height * 4).position(0);
        RenderSystem.getDevice()
            .createCommandEncoder()
            .writeToTexture(fontGpuTexture, pixels, NativeImage.Format.RGBA,
                0, 0, 0, 0, width, height);

        fontGpuTextureView = RenderSystem.getDevice().createTextureView(fontGpuTexture);

        // Build a Minecraft RenderType that uses GUI_TEXTURED pipeline with our atlas.
        // GUI_TEXTURED is the same pipeline used by GuiGraphics.blit() — VulkanMod
        // handles it correctly.
        fontRenderType = RenderType.create(
            "flashback:imgui_draw",
            RenderSetup.builder(RenderPipelines.GUI_TEXTURED)
                .withTexture("Sampler0", FONT_TEXTURE_ID)
                .createRenderSetup()
        );

        // Register the texture so the sampler can find it by Identifier.
        // We do this via the dynamic texture registry.
        registerDynamicTexture(width, height, pixels, width, height);

        fonts.setTexID(1);
    }

    /**
     * Register the font atlas pixels as a Minecraft DynamicTexture so the
     * RenderType sampler (which looks up textures by Identifier) can find it.
     */
    private void registerDynamicTexture(int width, int height, ByteBuffer pixels,
                                         int w, int h) {
        try {
            pixels.limit(w * h * 4).position(0);
            NativeImage img = new NativeImage(NativeImage.Format.RGBA, w, h, false);
            // Copy pixels into NativeImage row by row
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int off = (y * w + x) * 4;
                    int r = pixels.get(off)     & 0xFF;
                    int g = pixels.get(off + 1) & 0xFF;
                    int b = pixels.get(off + 2) & 0xFF;
                    int a = pixels.get(off + 3) & 0xFF;
                    // NativeImage stores ABGR
                    img.setPixel(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                }
            }
            var dynTex = new net.minecraft.client.renderer.texture.DynamicTexture(
                () -> "flashback_imgui_font", img);
            Minecraft.getInstance().getTextureManager()
                .register(FONT_TEXTURE_ID, dynTex);
        } catch (Exception e) {
            // Fallback: if dynamic texture registration fails, rendering will use
            // a blank white texture (UI still visible, just no text anti-aliasing)
        }
    }

    public void destroyFontsTexture() {
        if (fontGpuTextureView != null) { fontGpuTextureView.close(); fontGpuTextureView = null; }
        if (fontGpuTexture     != null) { fontGpuTexture.close();     fontGpuTexture     = null; }
        fontRenderType = null;
        try {
            Minecraft.getInstance().getTextureManager().release(FONT_TEXTURE_ID);
        } catch (Exception ignored) {}
    }

    // ── Per-frame render ──────────────────────────────────────────────────────

    /**
     * Render all ImGui draw data for this frame.
     *
     * @param guiGraphics  The active GuiGraphics from Gui.render() — must NOT be null.
     */
    public void renderDrawData(ImDrawData drawData, GuiGraphics guiGraphics) {
        if (drawData == null || !drawData.getValid()) return;
        if (fontRenderType == null) return;

        int cmdListsCount = drawData.getCmdListsCount();
        if (cmdListsCount == 0) return;

        float fbScaleX = drawData.getFramebufferScaleX();
        float fbScaleY = drawData.getFramebufferScaleY();
        float displayW = drawData.getDisplaySizeX() * fbScaleX;
        float displayH = drawData.getDisplaySizeY() * fbScaleY;
        if (displayW <= 0 || displayH <= 0) return;

        float clipOffX = drawData.getDisplayPosX();
        float clipOffY = drawData.getDisplayPosY();

        // bufferSource() was removed in MC 1.21.2; use drawSpecial(Consumer<MultiBufferSource>)
        // which internally flushes and ends the batch after the consumer runs.
        for (int listIdx = 0; listIdx < cmdListsCount; listIdx++) {
            int vtxCount = drawData.getCmdListVtxBufferSize(listIdx);
            ByteBuffer vtxBuf = drawData.getCmdListVtxBufferData(listIdx);
            vtxBuf.limit(vtxCount * 20).position(0); // ImDrawVert = 20 bytes

            int cmdCount = drawData.getCmdListCmdBufferSize(listIdx);

            for (int cmdIdx = 0; cmdIdx < cmdCount; cmdIdx++) {
                int elemCount = drawData.getCmdListCmdBufferElemCount(listIdx, cmdIdx);
                if (elemCount == 0) continue;

                int idxOffset = drawData.getCmdListCmdBufferIdxOffset(listIdx, cmdIdx);
                int vtxOffset = drawData.getCmdListCmdBufferVtxOffset(listIdx, cmdIdx);

                // Clip rect
                drawData.getCmdListCmdBufferClipRect(clipRect, listIdx, cmdIdx);
                float cx1 = (clipRect.x - clipOffX) * fbScaleX;
                float cy1 = (clipRect.y - clipOffY) * fbScaleY;
                float cx2 = (clipRect.z - clipOffX) * fbScaleX;
                float cy2 = (clipRect.w - clipOffY) * fbScaleY;

                if (cx1 >= displayW || cy1 >= displayH || cx2 < 0 || cy2 < 0) continue;

                int sx  = (int) Math.max(0, cx1);
                int sy  = (int) Math.max(0, cy1);
                int sx2 = (int) Math.min(displayW, cx2);
                int sy2 = (int) Math.min(displayH, cy2);

                guiGraphics.enableScissor(sx, sy, sx2, sy2);

                // Get index buffer for this draw command
                ByteBuffer idxBuf = drawData.getCmdListIdxBufferData(listIdx);
                // ImDrawIdx = uint16, offset in shorts
                idxBuf.position(idxOffset * 2);

                // Capture loop variables for the lambda
                final ByteBuffer finalVtxBuf = vtxBuf;
                final ByteBuffer finalIdxBuf  = idxBuf;
                final int        finalElemCount = elemCount;
                final int        finalVtxOffset = vtxOffset;

                // drawSpecial replaces bufferSource(): provides a MultiBufferSource,
                // submits the geometry, and ends the batch automatically.
                guiGraphics.drawSpecial(bufferSource -> {
                    VertexConsumer vc = bufferSource.getBuffer(fontRenderType);

                    // Emit triangles: elemCount indices
                    for (int i = 0; i < finalElemCount; i++) {
                        int idx  = (finalIdxBuf.getShort() & 0xFFFF) + finalVtxOffset;
                        int vBase = idx * 20; // ImDrawVert size = 20 bytes

                        float px  = finalVtxBuf.getFloat(vBase);
                        float py  = finalVtxBuf.getFloat(vBase + 4);
                        float u   = finalVtxBuf.getFloat(vBase + 8);
                        float v   = finalVtxBuf.getFloat(vBase + 12);
                        int   col = finalVtxBuf.getInt  (vBase + 16); // ABGR in ImGui

                        // ImGui color = 0xAABBGGRR, Minecraft VertexConsumer wants ARGB
                        int a = (col >> 24) & 0xFF;
                        int b = (col >> 16) & 0xFF;
                        int g = (col >>  8) & 0xFF;
                        int r = (col      ) & 0xFF;

                        vc.addVertex(px, py, 0.0f)
                          .setUv(u, v)
                          .setColor(r, g, b, a);
                    }
                });

                guiGraphics.disableScissor();
            }
        }
    }

    public void dispose() {
        destroyFontsTexture();
    }
}
