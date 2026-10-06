#version 150
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in vec3 SectionOffset;
uniform mat4 ModelView;
uniform mat4 Projection;
uniform vec3 ChunkOffset;
uniform int FogShape;
uniform int SectionOffsets;
out vec4 vertexColor;
out vec2 texCoord;
out float fogDistance;
void main() {
    vec3 offset = ChunkOffset;
    if (SectionOffsets != 0) offset += SectionOffset;
    vec4 position = vec4(Position + offset, 1.0);
    vec4 view = ModelView * position;
    gl_Position = Projection * ModelView * position;
    vertexColor = Color;
    texCoord = UV0;
    fogDistance = FogShape == 0 ? length(view.xyz) : max(length(view.xz), abs(view.y));
}
