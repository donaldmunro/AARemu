//#define VERTEX_P mediump
//#define FRAGMENT_P lowp

uniform mediump mat4 MVP;
uniform lowp mat4 ST;

attribute mediump vec3 vPosition;
attribute lowp vec2 vTexCoord;

varying lowp vec2 texCoord;

void main()
{      
   texCoord = (ST * vec4(vTexCoord, 0, 1)).xy; 
   gl_Position = MVP * vec4(vPosition, 1.0);
}