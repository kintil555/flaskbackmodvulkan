package com.moulberry.flashback.visuals;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

public class ShaderManager {

    /**
     * Pipeline used by {@link com.moulberry.flashback.editor.ui.ImGuiVulkanRenderer}
     * to render ImGui draw-data without raw OpenGL calls.
     *
     * Vertex format matches ImGui's ImDrawVert (20 bytes):
     *   vec2 Position, vec2 UV, vec4u8 Color
     *
     * We re-use the POSITION_TEX_COLOR vertex format which has an identical layout
     * (Position:3f, UV:2f, Color:4ub) — the Z component is always 0 from ImGui.
     *
     * Alpha-blending is enabled (SRC_ALPHA / ONE_MINUS_SRC_ALPHA) to replicate
     * the blend mode from imgui_impl_opengl3.
     */
    public static final RenderPipeline IMGUI_DRAW = RenderPipelines.register(
        RenderPipeline.builder()
            .withLocation(Identifier.fromNamespaceAndPath("flashback", "pipeline/imgui_draw"))
            .withVertexShader(Identifier.fromNamespaceAndPath("flashback", "core/imgui_draw"))
            .withFragmentShader(Identifier.fromNamespaceAndPath("flashback", "core/imgui_draw"))
            .withSampler("InSampler")
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withDepthWrite(false)
            .withCull(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withBlend(BlendFunction.TRANSLUCENT)
            .withVertexFormat(DefaultVertexFormat.POSITION_TEX_COLOR, VertexFormat.Mode.TRIANGLES)
            .build()
    );

    public static final RenderPipeline BLIT_SCREEN = RenderPipelines.register(
        RenderPipeline.builder()
                      .withLocation(Identifier.fromNamespaceAndPath("flashback", "pipeline/blit_screen"))
                      .withVertexShader("core/screenquad")
                      .withFragmentShader("core/blit_screen")
                      .withSampler("InSampler")
                      .withDepthWrite(false)
                      .withCull(false)
                      .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                      .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                      .build()
    );

    public static final RenderPipeline BLIT_SCREEN_WITH_UV = RenderPipelines.register(
        RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("flashback", "pipeline/blit_screen_with_uv"))
                .withVertexShader(Identifier.fromNamespaceAndPath("flashback", "core/blit_screen_old"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("flashback", "core/blit_screen_old"))
                .withSampler("InSampler")
                .withDepthWrite(false)
                .withCull(false)
                .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
                .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
                .build()
    );

    public static RenderPipeline BLIT_SCREEN_ROUND_ALPHA = RenderPipelines.register(
        RenderPipeline.builder()
                      .withLocation(Identifier.fromNamespaceAndPath("flashback", "pipeline/flashback_blit_screen_flip"))
                      .withVertexShader("core/screenquad")
                      .withFragmentShader(Identifier.fromNamespaceAndPath("flashback", "core/blit_screen_round_alpha"))
                      .withSampler("InSampler")
                      .withDepthWrite(false)
                      .withCull(false)
                      .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                      .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
                      .build()
    );

    public static RenderPipeline BLIT_SCREEN_FLIP = RenderPipelines.register(
        RenderPipeline.builder()
                      .withLocation(Identifier.fromNamespaceAndPath("flashback", "pipeline/flashback_blit_screen_flip"))
                      .withVertexShader(Identifier.fromNamespaceAndPath("flashback", "core/screenquad_flip"))
                      .withFragmentShader("core/blit_screen")
                      .withSampler("InSampler")
                      .withDepthWrite(false)
                      .withCull(false)
                      .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                      .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                      .build()
    );

}
