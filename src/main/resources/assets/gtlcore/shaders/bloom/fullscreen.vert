#version 150
out vec2 texCoord;
void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    texCoord = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
