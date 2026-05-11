#extension GL_OES_EGL_image_external : require
#ifdef GL_ES
precision mediump float;
#endif
uniform samplerExternalOES uVideoTexture;
uniform sampler2D uImageTexture;
uniform int uUseVideo;
varying vec2 vTexCoord;

void main() {
    vec4 imageColor = texture2D(uImageTexture, vTexCoord);
    vec4 videoColor = texture2D(uVideoTexture, vTexCoord);
    gl_FragColor = mix(imageColor, videoColor, float(uUseVideo));
}