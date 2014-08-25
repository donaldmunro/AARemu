//#define VERTEX_P mediump
//#define FRAGMENT_P lowp

uniform mediump mat4 MVP;
//uniform lowp mat4 ST;

attribute vec3 vPosition;
attribute vec2 vTexCoord;

varying vec2 texCoord;

void main()
{
//   texCoord = (ST * vec4(vTexCoord, 0, 1)).xy;
   texCoord = vTexCoord;
   gl_Position = MVP * vec4(vPosition, 1.0);
}
