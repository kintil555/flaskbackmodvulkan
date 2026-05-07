#version 150

// Vertex layout: DefaultVertexFormat.POSITION_TEX_COLOR (24 bytes)
//   vec3 Position  (12 bytes) — Z is always 0, inserted by ImGuiVulkanRenderer
//   vec2 UV        (8 bytes)
//   vec4 Color     (4 bytes, u8×4 normalized to [0,1])
//
// ProjMat (from DynamicTransforms) = ImGui orthographic projection matrix.
// ModelViewMat is identity (written as zeros by writeTransform).

in vec3 Position;
in vec2 UV;
in vec4 Color;

#moj_import <minecraft:dynamictransforms.glsl>
#moj_import <minecraft:projection.glsl>

out vec2 fragUV;
out vec4 fragColor;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    fragUV    = UV;
    fragColor = Color * ColorModulator;
}
