#extension GL_OES_EGL_image_external : require
precision mediump float;
uniform samplerExternalOES uVideoTexture;
uniform sampler2D uImageTexture;
uniform int uUseVideo;
varying vec2 vTexCoord;

void main() {
    if (uUseVideo == 1) {
        gl_FragColor = texture2D(uVideoTexture, vTexCoord);
    } else {
        gl_FragColor = texture2D(uImageTexture, vTexCoord);
    }
}