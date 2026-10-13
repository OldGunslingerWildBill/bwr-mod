#version 150
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform samplerBuffer Instances;
out vec2 texCoord;
out float opacity;
void main() {
    vec4 positionAndSize = texelFetch(Instances, gl_InstanceID * 3);
    vec4 alphaAndU = texelFetch(Instances, gl_InstanceID * 3 + 1);
    vec4 vAndPadding = texelFetch(Instances, gl_InstanceID * 3 + 2);
    // Counter-clockwise triangle strip: bottom-left, bottom-right, top-left, top-right.
    vec2 corner = vec2((gl_VertexID == 1 || gl_VertexID == 3) ? 1.0 : 0.0,
                       (gl_VertexID >= 2) ? 1.0 : 0.0);
    vec2 signedCorner = corner * 2.0 - 1.0;
    vec4 center = ModelViewMat * vec4(positionAndSize.xyz, 1.0);
    center.xy += signedCorner * positionAndSize.w;
    gl_Position = ProjMat * center;
    texCoord = mix(vec2(alphaAndU.y, alphaAndU.z), vec2(alphaAndU.w, vAndPadding.x), corner);
    opacity = alphaAndU.x;
}
