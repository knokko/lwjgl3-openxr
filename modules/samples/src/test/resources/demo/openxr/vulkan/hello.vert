#version 300

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inColor;

layout(location = 0) out vec3 passColor;

void main() {
    // TODO Transform the position
    gl_Position = vec4(inPosition, 0.0);

    passColor = inColor;
}
