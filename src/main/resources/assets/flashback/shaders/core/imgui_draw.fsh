#version 150

uniform sampler2D InSampler;

in vec2 fragUV;
in vec4 fragColor;

out vec4 outColor;

void main() {
    outColor = fragColor * texture(InSampler, fragUV);
}
