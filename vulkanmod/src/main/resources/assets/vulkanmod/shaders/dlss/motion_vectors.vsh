#version 450

// Fullscreen triangle (no vertex buffer) — same trick as basic/blit.
// texCoord: (0,0) at top-left, matching Vulkan NDC where y=-1 is the top.
const vec4 pos[] = { vec4(-1, -1, 0, 1), vec4(3, -1, 0, 1), vec4(-1, 3, 0, 1) };
const vec2 uv[]  = { vec2(0, 0),         vec2(2, 0),        vec2(0, 2)        };

layout(location = 0) out vec2 texCoord;

void main() {
    texCoord = uv[gl_VertexIndex];
    gl_Position = pos[gl_VertexIndex];
}
