uniform mediump mat4 MVP;

attribute mediump vec3 vPosition;
attribute lowp vec2 vTexCoord;

varying lowp vec2 texCoord;

void main()
{      
   texCoord = vTexCoord;
   gl_Position = MVP * vec4(vPosition, 1.0);
}
