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

import java.nio.*;

import static android.opengl.GLES20.*;
import static android.opengl.GLES20.GL_RGB;
import static android.opengl.GLES20.GL_UNSIGNED_SHORT_5_6_5;

/**
 * A VTW (Very Thin Wrapper) around an OpenGL ES 2/3 texture.
 */
public class GLTexture
//====================
{
   public enum TextureFormat { RGBA, RGB, RGB565 };

   private int target, name, format, internalFormat, alignment, dataType, textureUnit, unit, error, uniform =-1;

   /**
    * Factory method to create a GLTexture.
    * @param textureUnit The GPU texture unit as passed to glActiveTexture. Values between GL_TEXTURE0 .. GL_TEXTURE31
    *                    are accepted.
    * @param textureTarget The texture target, for example GL_TEXTURE_1D, GL_TEXTURE_2D etc
    * @param abstractFormat An abstraction for the texture format as represented by the GLTexture.TextureFormat enum.
    *                       This will be translated internally into a OpenGL format. and internal format.
    * @return A GLTexture instance.
    */
   public static GLTexture create(int textureUnit, int textureTarget, TextureFormat abstractFormat)
   //----------------------------------------------------------------------------------------------
   {
      return create(textureUnit, textureTarget, abstractFormat, -1);
   }

   /**
    * Factory method to create a GLTexture.
    * @param textureUnit The GPU texture unit as passed to glActiveTexture. Values between GL_TEXTURE0 .. GL_TEXTURE31
    *                    are accepted.
    * @param textureTarget The texture target, for example GL_TEXTURE_1D, GL_TEXTURE_2D etc
    * @param abstractFormat An abstraction for the texture format as represented by the GLTexture.TextureFormat enum.
    *                       This will be translated internally into a OpenGL format. and internal format.
    * @param shaderUniform The shader uniform used to specify the texture unit to the shader.  This uniform will be
    *                      set to a value corresponding to the textureUnit index eg for GL_TEXTURE0 it will be set to 0.
    *                      Note this implementation currently assumes GL_TEXTURE0 .. GL_TEXTURE31 are
      *                    an uninterrupted range when setting the unit value.
    * @return  A GLTexture instance.
    */
   public static GLTexture create(int textureUnit, int textureTarget, TextureFormat abstractFormat, int shaderUniform)
   //-----------------------------------------------------------------------------------------------------------------
   {
      int format, internalFormat, alignment, dataType;
      switch (abstractFormat)
      {
         case RGBA:
            alignment = 4;
            format = internalFormat = GL_RGBA;
            dataType = GL_UNSIGNED_BYTE;
            break;
         case RGB:  // Not always a safe option, see http://www.opengl.org/wiki/Common_Mistakes#Texture_upload_and_pixel_reads
            alignment = 1;
            format = internalFormat = GL_RGB;
            dataType = GL_UNSIGNED_BYTE;
            break;
         case RGB565:
            alignment = 2;
            internalFormat = GL_RGB565;
            format = GL_RGB;
            dataType = GL_UNSIGNED_SHORT_5_6_5;
            break;
         default:
            return null;
      }
      final int unit;
      if (shaderUniform >= 0)
      {
         //TODO: WARNING: Depends on GL_TEXTURE0 .. GL_TEXTURE31 being continuous
         unit = textureUnit - GL_TEXTURE0;
         if (unit < 0)
            throw new RuntimeException("textureUnit must be one of GL_TEXTURE0 .. GL_TEXTURE31");
      }
      else
         unit = -1;
      return new GLTexture(textureUnit, unit, textureTarget, format, internalFormat, alignment, dataType, shaderUniform);
   }

   private GLTexture(int textureUnit, int unit, int textureTarget, int format, int internalFormat, int alignment, int dataType,
                     int shaderUniform)
   //-----------------------------------------------------------------------------------------------------------------
   {
      this.format = format;
      this.internalFormat = internalFormat;
      this.alignment = alignment;
      this.dataType = dataType;
      this.textureUnit = textureUnit;
      this.unit = unit;
      this.target = textureTarget;
      this.uniform = shaderUniform;

      int[] texnames = new int[1];
      glGenTextures(1, texnames, 0);
      name = texnames[0];
      glActiveTexture(textureUnit);
      glBindTexture(target, name);
      error = glGetError();
      if (error != GL_NO_ERROR)
         throw new RuntimeException("glBindTexture: " + error + ": " + lastErrorMessage());
      glBindTexture(target, 0);
   }

   /**
    * Bind the texture.
    * @return <i>true</i> if successfully bound else false in which case the OpenGL error will be accessible via getError.
    */
   public boolean bind()
   //----------------
   {
      glActiveTexture(textureUnit);
      error = glGetError();
      if (error != GL_NO_ERROR)
         return false;
      if ( (uniform >= 0) && (unit >= 0) )
      {
         glUniform1i(uniform, unit);
         error = glGetError();
         if (error != GL_NO_ERROR)
            return false;
      }
      glBindTexture(target, name);
      error = glGetError();
      if (error != GL_NO_ERROR)
         return false;
      return true;
   }

   /**
    * Unbind the texture.
    */
   public void unbind() { glBindTexture(target, 0); error = glGetError(); }


   /**
    * Sets integer texture parameters. Accepts a variable number of Pair objects where Pair.first is the texture parameter and
    * Pair.second is the texture parameter value . For example:<br>
    * <code>
    *    texture.setIntParameters(Pair.create(GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE),Pair.create(GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE));<br>
    * </code>
    *
    * @param parameters
    * @return
    */
   public boolean setIntParameters(Pair<Integer, Integer> ...parameters)
   //-------------------------------------------------------------
   {
      if (! bind())
         return false;
      try
      {
         for (Pair<Integer, Integer> parameterPair : parameters)
         {
            glTexParameteri(target, parameterPair.first, parameterPair.second);
            error = glGetError();
            if (error != GL_NO_ERROR)
               return false;
         }
      }
      catch (Exception e)
      {
         Log.e("GLTexture", "", e);
      }
      finally
      {
         unbind();
      }
      return true;
   }

   /**
    * Sets float texture parameters. Accepts a variable number of Pair objects where Pair.first is the texture parameter and
    * Pair.second is the texture parameter value.
    * @param parameters
    * @return
    */
   public boolean setFloatParameters(Pair<Integer, Float> ...parameters)
   //-------------------------------------------------------------
   {
      if (! bind())
         return false;
      try
      {
         for (Pair<Integer, Float> parameterPair : parameters)
         {
            glTexParameterf(target, parameterPair.first, parameterPair.second);
            error = glGetError();
            if (error != GL_NO_ERROR)
               return false;
         }
      }
      catch (Exception e)
      {
         Log.e("GLTexture", "", e);
      }
      finally
      {
         unbind();
      }
      return true;
   }


   /**
    * Loads data into the texture.
    * @param buffer A ByteBuffer containing the data to load.
    * @param width The width of the texture data image.
    * @param height The height of the texture data image.
    * @param isUpdate If <i>true</i> then update (glTexSubImage2D) the texture else allocate and update the texture
    *                 (glTexImage2D). If isUpdate is <i>true</i> and glTexSubImage2D fails with error GL_INVALID_OPERATION
    *                 then it will try using glTexImage2D too.
    * @return
    */
   public boolean load(ByteBuffer buffer, int width, int height, boolean isUpdate) { return load(buffer, width, height, isUpdate, true); }

   /**
    * Loads data into the texture.
    * @param buffer A ByteBuffer containing the data to load.
    * @param width The width of the texture data image.
    * @param height The height of the texture data image.
    * @param isUpdate If <i>true</i> then update (glTexSubImage2D) the texture else allocate and update the texture
    *                 (glTexImage2D). If isUpdate is <i>true</i> and glTexSubImage2D fails with error GL_INVALID_OPERATION
    *                 then it will try using glTexImage2D too.
    * @param isRewindBuffer If <i>true</i> then the buffer will be rewound before loading data.
    * @return true
    */
   public boolean load(ByteBuffer buffer, int width, int height, boolean isUpdate, boolean isRewindBuffer)
   //-------------------------------------------------------------------------------------------------------
   {
      glActiveTexture(textureUnit);
      if ( (uniform >= 0) && (unit >= 0) )
         glUniform1i(uniform, unit);
      glBindTexture(target, name);
      glPixelStorei(GL_UNPACK_ALIGNMENT, alignment);
      if (isRewindBuffer)
         buffer.rewind();
      if (isUpdate)
      {
         glTexSubImage2D(target, 0, 0, 0, width, height, format, dataType, buffer);
         error = glGetError();
         if (error != GL_INVALID_OPERATION)
            return (error == GL_NO_ERROR);
      }
      glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, dataType, buffer);
      error = glGetError();
      return (error == GL_NO_ERROR);
   }

   /**
    * @return <i>true</i> if this texture is valid else <i>false</i>.
    */
   public boolean isValid() { return ( (name >= 0) && (glIsTexture(name)) ); }

   /**
    * Delete this texture (OpenGL glDeleteTextures)
    */
   public void delete()
   //------------------
   {
      int[] texnames = new int[1];
      texnames[0] = name;
      glDeleteTextures(1, texnames, 0);
   }

   /**
    * @return The texture name.
    */
   public int getName() { return name; }

   /**
    * @return The last error code.
    */
   public int lastError() { return error;}

   /**
    * @return The last error message.
    */
   public String lastErrorMessage() { return GLU.gluErrorString(error); }

   @Override
   protected void finalize() throws Throwable
   {
      super.finalize();
      delete();
   }
}
