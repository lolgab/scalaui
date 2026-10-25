#version 450
layout(location = 0) in vec2 pos;
layout(location = 1) in vec2 uv;
layout(location = 2) in vec4 color;
layout(push_constant) uniform Params { vec2 size; } p;
layout(location = 0) out vec2 vUv;
layout(location = 1) out vec4 vColor;
void main() {
  // logical points, origin top-left, y down (Vulkan's NDC y also points down)
  gl_Position = vec4(pos.x / p.size.x * 2.0 - 1.0, pos.y / p.size.y * 2.0 - 1.0, 0.0, 1.0);
  vUv = uv;
  vColor = color;
}
