#version 150
uniform sampler2D Sampler0;
uniform float GameTime;
uniform float Power;
uniform float Falling;
uniform float WorldLight;
in vec4 tint;
in vec2 texCoord;
out vec4 fragColor;
void main() {
    float agitation = clamp(Power * 0.30 + Falling * 0.60, 0.0, 0.65);
    vec2 p = texCoord * 6.283185;
    float a = sin(p.x * 3.0 + GameTime * 0.8);
    float b = sin(p.y * 4.0 - GameTime * 0.65);
    float c = sin((p.x + p.y) * 2.0 + GameTime * 0.37);
    vec2 uv = texCoord + agitation * 0.004 * vec2(a + c, b - c);
    vec4 water = texture(Sampler0, uv);
    float glint = 1.0 + agitation * 0.12 * (a + b + c) / 3.0;
    fragColor = vec4(water.rgb * tint.rgb * glint * WorldLight, water.a * tint.a);
}
