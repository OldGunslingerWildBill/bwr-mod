#version 150
uniform float Strength;
uniform float CoreTop;
uniform float CoreBottom;
uniform float Surface;
uniform float Radius;
uniform float GameTime;
in vec4 tint;
in vec2 volume;
out vec4 fragColor;
void main() {
    if (Strength <= 0.0 || volume.y > Surface) discard;
    float radial = 1.0 - smoothstep(0.12, 1.0, volume.x / max(Radius, 0.001));
    float depth = max(0.0, Surface - volume.y);
    float submergence = 0.85 / (1.0 + depth * 0.12);
    float submergedCore = max(step(CoreBottom, volume.y) * step(volume.y, CoreTop),
                              step(Surface - 0.02, volume.y));
    float shimmer = 1.0 + 0.035 * sin(GameTime * 0.11 + volume.x * 13.0 + volume.y * 0.7);
    float intensity = Strength * radial * submergence * submergedCore * shimmer * tint.a;
    fragColor = vec4(vec3(0.04, 0.6, 1.0) * intensity, intensity);
}
