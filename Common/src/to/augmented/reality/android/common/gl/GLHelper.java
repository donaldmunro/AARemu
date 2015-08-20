/*
* Copyright (C) 2014 Donald Munro.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package to.augmented.reality.android.common.gl;

import android.opengl.*;
import android.util.*;
import to.augmented.reality.android.common.math.*;

import java.io.*;
import java.util.*;

import static android.opengl.GLES20.*;

public class GLHelper
//==================
{
   private static final String TAG = GLHelper.class.getSimpleName();

   static public void clearGLErrors() { while (glGetError() != GL_NO_ERROR); }

   static public boolean isGLError(StringBuilder errbuf)
   //---------------------------------------------------
   {
      if (errbuf != null)
         errbuf.setLength(0);
      int error = glGetError();
      boolean isError = (error != GL_NO_ERROR);
      while (error != GL_NO_ERROR)
      {
         String errmsg = GLU.gluErrorString(error);
         Log.e(TAG, String.format("OpenGL error %d - %s", error, errmsg));
         if (errbuf != null)
         {
            if (errbuf.length() > 0)
               errbuf.append("\n");
            errbuf.append("OpenGL error ").append(error).append(" (").append(Integer.toHexString(error)).append(") - ").
                   append(errmsg);
         }
         error = glGetError();
      }
      return isError;
   }

   static public int compileShader(final int type, final String shader, StringBuilder errbuf)
   //----------------------------------------------------------------------------------------
   {
      return compileShader(type, shader, false, errbuf);
   }

   static public int compileShaderResource(final int type, final String resource, StringBuilder errbuf)
   //----------------------------------------------------------------------------------------
   {
      return compileShader(type, resource, true, errbuf);
   }

   static public int compileShader(final int type, final String shaderOrResource, final boolean isResource,
                                   StringBuilder errbuf)
   //----------------------------------------------------------------------------------------------------------
   {
      try
      {
         String shader;
         if (isResource)
         {
            try
            {
               shader = resource2String(shaderOrResource);
            }
            catch (IOException e)
            {
               if (errbuf != null)
                  errbuf.append("Could not open resource ").append(shaderOrResource);
               Log.e(TAG, "Could not open resource", e);
               return -1;
            }
         } else
            shader = shaderOrResource;
         clearGLErrors();
         int handle = glCreateShader(type);
         if (handle <= 0)
         {
            isGLError(errbuf);
            return -1;
         }
         glShaderSource(handle, shader);
         if (isGLError(errbuf)) return -1;
         glCompileShader(handle);
         if (isGLError(errbuf)) return -1;
         int[] status = new int[1];
         glGetShaderiv(handle, GL_COMPILE_STATUS, status, 0);
         if (status[0] == GL_FALSE)
         {
            if (errbuf == null) errbuf = new StringBuilder();
            String shaderType;
            switch (type)
            {
               case GL_VERTEX_SHADER:
                  shaderType = "Vertex shader";
                  break;
               case GL_FRAGMENT_SHADER:
                  shaderType = "Fragment shader";
                  break;
               //               case GL_GEOMETRY_SHADER:         shaderType = "Geometry shader"; break;
               //               case GL_TESS_CONTROL_SHADER:     shaderType = "Tesselation Control shader"; break;
               //               case GL_TESS_EVALUATION_SHADER:  shaderType = "Tesselation Eval shader"; break;
               default:
                  shaderType = "Unknown shader (" + type + ")";
                  break;
            }
            String message = glGetShaderInfoLog(handle);
            errbuf.append("Compile error: ").append(shaderType).append(": ").append(message);
            status[0] = 0;
            glGetShaderiv(handle, GL_INFO_LOG_LENGTH, status, 0);
            final int loglen = status[0];
            if (loglen > 0)
               errbuf.append(": ").append(glGetShaderInfoLog(handle));
            return -1;
         }
         return handle;
      }
      catch (Exception e)
      {
         Log.e(TAG, "compileShader", e);
         return -1;
      }
   }

   static public int createShaderProgram(StringBuilder errbuf, int... shaders)
   //-------------------------------------------------------------------------
   {
      boolean isError = false;
      int shaderProgram = glCreateProgram();
      if ((shaderProgram == 0) || (GLHelper.isGLError(errbuf)))
         return -1;
      for (int shader : shaders)
      {
         glAttachShader(shaderProgram, shader);
         isError = isGLError(errbuf);
         if (isError)
         {
            if (errbuf != null) errbuf.append("Error attaching vertex shader");
            return -1;
         }
      }
      return shaderProgram;
   }

   static public boolean linkShaderProgram(int shaderProgram, StringBuilder errbuf)
   //------------------------------------------------------------------------------
   {
      glLinkProgram(shaderProgram);
      boolean isError = isGLError(errbuf);
      int[] status = new int[1];
      status[0] = -1;
      glGetProgramiv(shaderProgram, GL_LINK_STATUS, status, 0);
      if ( (! isGLError(null)) && (status[0] != GL_TRUE) )
      {
         String error = glGetProgramInfoLog(shaderProgram);
         if (errbuf != null)
            errbuf.append(error);
         else
            Log.e(TAG, "glLinkProgram: " + error);
         glDeleteProgram(shaderProgram);
         return false;
      }
      if (isError)
      {
         glDeleteProgram(shaderProgram);
         return false;
      }
      return true;
   }

   static public String resource2String(String resname) throws IOException
   //---------------------------------------------------------------------
   {
      BufferedReader br = null;
      StringBuilder sb = new StringBuilder();
      try
      {
         InputStream is = GLHelper.class.getClassLoader().getResourceAsStream(resname);
         if (is == null)
         {
            if (resname.startsWith("/"))
               resname = resname.substring(1);
            else
               resname = "/" + resname;
            is = GLHelper.class.getClassLoader().getResourceAsStream(resname);
         }
         if (is != null)
         {
            br = new BufferedReader(new InputStreamReader(is));
            String s = br.readLine();
            while (s != null)
            {
               sb.append(s).append(System.getProperty("line.separator"));
               s = br.readLine();
            }
         }
      }
      finally
      {
         if (br != null)
            try { br.close(); } catch (Throwable _e) {}
      }
      return sb.toString();
   }

   static public float[] ortho(float left, float right, float bottom, float top,
                               float zNear, float zFar, float[] m)
   //-----------------------------------------------------------------------------
   {
      if (m == null)
         m = new float[16];
      else
         Arrays.fill(m, 0);
      m[0] = 2.0f / (right - left);
      m[5] = 2.0f / (top - bottom);
      m[10] = -2.0f / (zFar - zNear);
      m[12] = -(right + left) / (right - left);
      m[13] = -(top + bottom) / (top - bottom);
      m[14] = -(zFar + zNear) / (zFar - zNear);
      return m;
   }

   static public float[] lookAt(float[] eye, float[] center, final float[] up, float[] m)
   //-------------------------------------------------------------------------
   {
      float[] f = new float[3], s = new float[3], u = new float[3];
      QuickFloat.vecSubtract(center, eye, 0, 3, f);
      QuickFloat.vecNormalize(f, 0, 3);
      QuickFloat.vec3Cross(f, up, s);
      QuickFloat.vecNormalize(s, 0, 3);
      QuickFloat.vec3Cross(s, f, u);
      Arrays.fill(m, 0.0f);
      m[0] = s[0];
      m[4] = s[1];
      m[8] = s[2];
      m[1] = u[0];
      m[5] = u[1];
      m[9] = u[2];
      m[2] =-f[0];
      m[6] =-f[1];
      m[10] =-f[2];
      m[12] = -QuickFloat.vecDot(s, eye, 0, 3);
      m[13] = -QuickFloat.vecDot(u, eye, 0, 3);
      m[14] = QuickFloat.vecDot(f, eye, 0, 3);
      return m;
   }

}
