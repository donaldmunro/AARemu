precision mediump float;

varying vec2 texCoord;
uniform sampler2D y_texture;
uniform sampler2D u_texture;
uniform sampler2D v_texture;

void main (void)
{
   float y = texture2D(y_texture, texCoord).r;
   float u = texture2D(u_texture, texCoord).r;
   float v = texture2D(v_texture, texCoord).r;

   y = 1.1643*(y - 0.0625);
   u = u - 0.5;
   v = v - 0.5;

   float r = clamp(y + 1.5958*v, 0.0, 1.0);
   float g = clamp(y - 0.39173*u - 0.81290*v, 0.0, 1.0);
   float b = clamp(y + 2.017*u, 0.0, 1.0);
   gl_FragColor = vec4(r, g, b, 1.0);
}