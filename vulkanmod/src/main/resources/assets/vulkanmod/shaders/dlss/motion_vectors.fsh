#version 450

// Camera-only (static-geometry) screen-space motion vectors.
// Mirrors net.vulkanmod.dlss.MotionVectorMath, which is unit-tested on the CPU:
//   reconstruct world pos from (uv, depth) via the inverse current view-projection,
//   reproject with the previous view-projection, output (prevUV - curUV) * scale.
//
// Depth convention (verified for this renderer): Vulkan [0,1], near=0 far=1, NOT reversed-Z.
// Output target is RG16F.

layout(binding = 0) uniform sampler2D DepthSampler;

layout(binding = 1) uniform MotionVectorUBO {
    mat4 invCurrentVP;   // inverse of current frame's P*MV
    mat4 previousVP;     // previous frame's P*MV
    vec2 mvScale;        // maps the UV-space delta into the desired output range
    vec2 _pad;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec2 outMotion;

void main() {
    float depth = texture(DepthSampler, texCoord).r;

    // Current clip-space position (ndc.xy in [-1,1], depth in [0,1]).
    vec2 ndc = texCoord * 2.0 - 1.0;
    vec4 clip = vec4(ndc, depth, 1.0);

    vec4 world = invCurrentVP * clip;
    world /= world.w;

    vec4 prevClip = previousVP * vec4(world.xyz, 1.0);
    vec2 prevUV = (prevClip.xy / prevClip.w) * 0.5 + 0.5;

    outMotion = (prevUV - texCoord) * mvScale;
}
