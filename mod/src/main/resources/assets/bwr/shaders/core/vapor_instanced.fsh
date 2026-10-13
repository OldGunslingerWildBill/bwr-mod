#version 150
uniform sampler2D Sampler0;
in vec2 texCoord;
in float opacity;
out vec4 fragColor;
void main() {
    vec4 texel = texture(Sampler0, texCoord);
    fragColor = vec4(texel.rgb, texel.a * opacity);
}
