//#define VERTEX_P mediump
//#define FRAGMENT_P lowp

uniform mediump mat4 MVP;

attribute mediump vec3 vPosition;
attribute lowp vec2 vTexCoord;

varying mediump vec2 texCoord;

void main()
{      
   texCoord = vTexCoord;
   gl_Position = MVP * vec4(vPosition, 1.0);
}
