uniform sampler2D previewSampler;
varying lowp vec2 texCoord;

void main()
{
   gl_FragColor = texture2D(previewSampler, texCoord);
}
