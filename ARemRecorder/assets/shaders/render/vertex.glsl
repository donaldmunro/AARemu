//#define VERTEX_P mediump
//#define FRAGMENT_P lowp

uniform mediump mat4 MVP;
uniform lowp vec3 uvColor;

attribute mediump vec3 vPosition;

void main()
{
   gl_Position = MVP * vec4(vPosition, 1.0);
}
