#extension GL_OES_EGL_image_external : require
//#define VERTEX_P mediump
//#define FRAGMENT_P lowp

uniform samplerExternalOES previewSampler;
varying lowp vec2 texCoord;

void main()
{
   gl_FragColor = texture2D(previewSampler, texCoord);
}
