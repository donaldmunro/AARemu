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

package to.augmented.reality.android.em.sample;

import android.content.res.AssetManager;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;
import to.augmented.reality.android.common.gl.GLHelper;
import to.augmented.reality.android.common.gl.GLTexture;
import to.augmented.reality.android.em.ARCamera;
import to.augmented.reality.android.em.ARCameraDevice;
import to.augmented.reality.android.em.ReviewListenable;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.CountDownLatch;

import static android.opengl.GLES20.*;

public abstract class GLRenderer implements GLSurfaceView.Renderer
//=======================================================
{
   private static final String TAG = GLRenderer.class.getSimpleName();
   private static final String VERTEX_SHADER = "vertex.glsl";
   private static final String FRAGMENT_SHADER = "fragment.glsl";
   static final int SIZEOF_FLOAT = Float.SIZE/8;
   static final int SIZEOF_SHORT = Short.SIZE/8;

   protected MainActivity activity;
   protected ARSurfaceView view;


   protected int previewWidth = -1, previewHeight =-1;
   protected File headerFile = null, framesFile = null;
   protected CountDownLatch latch;

   int getPreviewWidth() { return previewWidth;}
   int getPreviewHeight() { return previewHeight;}

   private int displayWidth, displayHeight;

   boolean isPreviewing = false;
   final Object lockSurface = new Object();
   volatile boolean isUpdateSurface = false;

   private GLTexture previewTexture;
   private int previewMVPLocation =-1, cameraTextureUniform =-1;
   private float[] previewMVP = new float[16];

   int screenWidth =-1;
   public int getScreenWidth() { return screenWidth; }

   int screenHeight = -1;
   public int getScreenHeight() { return screenHeight; }

   byte[] previewBuffer = null, cameraBuffer = null;
   ByteBuffer previewByteBuffer = null;

   final private ByteBuffer previewPlaneVertices = ByteBuffer.allocateDirect(SIZEOF_FLOAT*12).order(ByteOrder.nativeOrder());
   final private ByteBuffer previewPlaneTextures = ByteBuffer.allocateDirect(SIZEOF_FLOAT*8).order(ByteOrder.nativeOrder());
   final private ByteBuffer previewPlaneFaces = ByteBuffer.allocateDirect(SIZEOF_SHORT*6).order(ByteOrder.nativeOrder());

   float[] projectionM = new float[16], viewM = new float[16];

   GLTexture.TextureFormat textureFormat = null;

   GLSLAttributes previewShaderGlsl;

   String lastError = null;

   public GLRenderer(MainActivity activity, ARSurfaceView surfaceView)
   //----------------------------------------------------------------
   {
      this.activity = activity;
      this.view = surfaceView;
      Matrix.setIdentityM(previewMVP, 0);
   }

   abstract protected boolean initCamera(StringBuilder errbuf) throws Exception;

   abstract public boolean startPreview(CountDownLatch latch);

   abstract protected void stopCamera();

   abstract protected void releaseCameraFrame(boolean isPreviewed);

   abstract public void review(float startBearing, float endBearing, int pauseMs, boolean isRepeat,
                               ReviewListenable reviewListenable);

   abstract public boolean isReviewing();

   abstract public void stopReviewing();

   abstract public float getReviewCurrentBearing();

   abstract public void setReviewBearing(float bearing);

   public ARCamera getLegacyCamera() { return null; }

   public ARCameraDevice getCamera2Camera() { return null; }

   public void setPreviewFiles(File headerFile, File framesFile, StringBuilder errbuf)
   //---------------------------------------------------------------------------------
   {
      this.headerFile = headerFile;
      this.framesFile = framesFile;
      try { initCamera(errbuf); } catch (Exception e) { Log.e(TAG, "Camera initialization", e); throw new RuntimeException("Camera initialization", e); }
   }

   @Override public void onSurfaceCreated(GL10 gl, EGLConfig config)
   //---------------------------------------------------------------
   {
      glDisable(GL10.GL_DITHER);
      glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_FASTEST);
      //glEnable(GL10.GL_CULL_FACE);
      glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
      glClearDepthf(1.0f);
      glEnable(GL_DEPTH_TEST);
      glDepthFunc(GL_LEQUAL);
   }

   @Override
   public void onSurfaceChanged(GL10 gl, int width, int height)
   //---------------------------------------------------------
   {
      this.screenWidth = width;
      this.screenHeight = height;
      displayWidth = width - 1;
      displayHeight = height - 1;
      initRender();
   }

   protected void toast(final String s)
   //--------------------------------
   {
      activity.runOnUiThread(new Runnable()
      {
         @Override
         public void run() { Toast.makeText(activity, s, Toast.LENGTH_LONG).show(); }
      });
   }

   private boolean initRender()
   //---------------------------------
   {
      final StringBuilder errbuf = new StringBuilder();
      String shaderDir = "preview/";
      previewShaderGlsl = loadShaders(shaderDir, "vPosition", null, "vTexCoord", null);      ;
      if (previewShaderGlsl == null)
         return false;

      previewMVPLocation = glGetUniformLocation(previewShaderGlsl.shaderProgram, "MVP");
      if ( (GLHelper.isGLError(errbuf)) || (previewMVPLocation == -1) )
      {
         Log.e(TAG, "Error getting mvp matrix uniform 'MVP'");
         activity.runOnUiThread(new Runnable()
         {
            @Override public void run() { Toast.makeText(activity, errbuf.toString(), Toast.LENGTH_LONG).show(); }
         });
         lastError = errbuf.toString();
         return false;
      }

      cameraTextureUniform = glGetUniformLocation(previewShaderGlsl.shaderProgram, "previewSampler");
      if ( (GLHelper.isGLError(errbuf)) || (cameraTextureUniform == -1) )
      {
         Log.e(TAG, "Error getting texture uniform 'previewSampler'");
         activity.runOnUiThread(new Runnable()
         {
            @Override public void run() { Toast.makeText(activity, errbuf.toString(), Toast.LENGTH_LONG).show(); }
         });
         lastError = errbuf.toString();
         return false;
      }
      float[] planeVertices =
      {
            displayWidth, 1f,            -50.0f, // bottom-right
            1.0f,         1f,            -50.0f, // bottom-left
            displayWidth, displayHeight, -50.0f, // top-right
            1.0f,         displayHeight, -50.0f, // top-left
      };
      previewPlaneVertices.clear();
      FloatBuffer fb = previewPlaneVertices.asFloatBuffer();
      fb.put(planeVertices);
      final float[] planeTextures =
      {
            1,1,
            0,1,
            1,0,
            0,0
      };

      previewPlaneTextures.clear();
      fb = previewPlaneTextures.asFloatBuffer();
      fb.put(planeTextures);
      short[] planeFaces= { 2,3,1, 0,2,1 };
      //short[] planeFaces= { 0,1,2, 2,1,3 };
      previewPlaneFaces.clear();
      ShortBuffer sb = previewPlaneFaces.asShortBuffer();
      sb.put(planeFaces);

      Matrix.orthoM(projectionM, 0, 0, screenWidth, 0, screenHeight, 0.2f, 120.0f);
      Matrix.setLookAtM(viewM, 0, 0, 0, 0, 0, 0, -1, 0, 1, 0);
      Matrix.multiplyMM(previewMVP, 0, projectionM, 0, viewM, 0);
      return true;
   }

   private float[] arrowModelView = new float[16], scaleM = new float[16], translateM = new float[16],
                   rotateM = new float[16], modelM = new float[16];
   boolean isReloadTexture = true;

   @Override
   public void onDrawFrame(GL10 gl)
   //------------------------------
   {
      StringBuilder errbuf = new StringBuilder();
      boolean isPreviewed = false;
      if ( ( (previewTexture == null) || (! previewTexture.isValid()) ) )
      {
         previewTexture = GLTexture.create(GL_TEXTURE0, GL_TEXTURE_2D, GLTexture.TextureFormat.RGBA, cameraTextureUniform);
         if (! previewTexture.setIntParameters(Pair.create(GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE),
                                               Pair.create(GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE),
                                               Pair.create(GL_TEXTURE_MIN_FILTER, GL_LINEAR),
                                               Pair.create(GL_TEXTURE_MAG_FILTER, GL_LINEAR)))
         {
            Log.w(TAG, "Preview texture parameter set error. " + previewTexture.lastError() + ": " +
                  previewTexture.lastErrorMessage());
         }
         isReloadTexture = true;
      }
      try
      {
         glViewport(0, 0, screenWidth, screenHeight);
         glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
         glUseProgram(previewShaderGlsl.shaderProgram);
         synchronized (lockSurface)
         {
            if ( (isUpdateSurface) && (previewTexture != null) )
            {
               isPreviewed = true;
               isUpdateSurface = false;
               previewByteBuffer.rewind();
               if (! previewTexture.load(previewByteBuffer, previewWidth, previewHeight, (!isReloadTexture)))
                  throw new RuntimeException("Texture error: " + previewTexture.lastError() + ": " +
                                             previewTexture.lastErrorMessage());
               isReloadTexture = false;
            }
         }

         glUniformMatrix4fv(previewMVPLocation, 1, false, previewMVP, 0);
         previewPlaneVertices.rewind();
         glVertexAttribPointer(previewShaderGlsl.vertexAttr, 3, GL_FLOAT, false, 0, previewPlaneVertices.asFloatBuffer());
         glEnableVertexAttribArray(previewShaderGlsl.vertexAttr);
         previewPlaneTextures.rewind();
         glVertexAttribPointer(previewShaderGlsl.textureAttr, 2, GL_FLOAT, false, 0, previewPlaneTextures.asFloatBuffer());
         glEnableVertexAttribArray(previewShaderGlsl.textureAttr);
         previewPlaneFaces.rewind();
         glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, previewPlaneFaces.asShortBuffer());
//         glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 4);
         glBindTexture(GL_TEXTURE_2D, 0);
//         if (GLHelper.isGLError(errbuf))
//            Log.e(TAG, "onDrawFrame: " + errbuf.toString());
//         glFinish();
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
      }
      finally
      {
         releaseCameraFrame(isPreviewed);
      }
   }

   private GLSLAttributes loadShaders(String assetDir, String vertexAttrName, String colorAttrName,
                                      String textureAttrName,
                                      String normalAttrName)
   //------------------------------------------------------------------------------------------------------------------------
   {
      assetDir = assetDir.trim();
      String shaderFile = "shaders/" + assetDir + ((assetDir.endsWith("/")) ? "" : "/") + VERTEX_SHADER;
      String src = readAssetFile(shaderFile, null);
      if (src == null)
      {
         Log.e(TAG, "Vertex shader not found in assets/" + shaderFile);
         activity.runOnUiThread(new Runnable()
         {
            @Override public void run() { Toast.makeText(activity, "Vertex shader not found in assets/" + VERTEX_SHADER,
                                                         Toast.LENGTH_LONG).show(); }
         });
         throw new RuntimeException("Error reading vertex shader source from assets/" + VERTEX_SHADER);
      }
      final StringBuilder errbuf = new StringBuilder();
      int vertexShader = GLHelper.compileShader(GL_VERTEX_SHADER, src, errbuf);
      if (vertexShader < 0)
      {
         Log.e(TAG, "Vertex shader compile error: " + errbuf.toString());
         activity.runOnUiThread(new Runnable()
         {
            @Override public void run() { Toast.makeText(activity, errbuf.toString(), Toast.LENGTH_LONG).show(); }
         });
         lastError = errbuf.toString();
         throw new RuntimeException("Error compiling vertex shader source from assets/" + VERTEX_SHADER);
      }

      shaderFile = "shaders/" + assetDir + ((assetDir.endsWith("/")) ? "" : "/") + FRAGMENT_SHADER;
      src = readAssetFile(shaderFile, null);
      if (src == null)
      {
         Log.e(TAG, "Fragment shader not found in assets/" + shaderFile);
         activity.runOnUiThread(new Runnable()
         {
            @Override public void run() { Toast.makeText(activity, "Fragment shader not found in assets/" + FRAGMENT_SHADER,
                                                         Toast.LENGTH_LONG).show(); }
         });
         throw new RuntimeException("Error reading vertex shader source from assets/" + FRAGMENT_SHADER);
      }
      int fragmentShader = GLHelper.compileShader(GL_FRAGMENT_SHADER, src, errbuf);
      if (fragmentShader < 0)
      {
         Log.e(TAG, "Error compiling fragment shader " + FRAGMENT_SHADER + ": " + errbuf.toString());
         activity.runOnUiThread(new Runnable()
         {
            @Override public void run() { Toast.makeText(activity, errbuf.toString(), Toast.LENGTH_LONG).show(); }
         });
         lastError = errbuf.toString();
         throw new RuntimeException("Error compiling fragment shader source from assets/" + FRAGMENT_SHADER);
      }

      int program = GLHelper.createShaderProgram(errbuf, vertexShader, fragmentShader);
      if (program < 0)
      {
         activity.runOnUiThread(new Runnable()
         {
            @Override public void run() { Toast.makeText(activity, errbuf.toString(), Toast.LENGTH_LONG).show(); }
         });

         lastError = errbuf.toString();
         return null;
      }
      GLSLAttributes shaderAttr = new GLSLAttributes(program);

      if (vertexAttrName != null)
      {
         glBindAttribLocation(shaderAttr.shaderProgram, shaderAttr.vertexAttr(), vertexAttrName);
         if (GLHelper.isGLError(errbuf))
         {
            Log.e(TAG, "Error binding vertex attribute " + vertexAttrName);
            activity.runOnUiThread(new Runnable()
            {
               @Override
               public void run()
               {
                  Toast.makeText(activity, errbuf.toString(), Toast.LENGTH_LONG).show();
               }
            });
            lastError = errbuf.toString();
            return null;
         }
         glEnableVertexAttribArray(shaderAttr.vertexAttr());
      }

      if (textureAttrName != null)
      {
         glBindAttribLocation(shaderAttr.shaderProgram, shaderAttr.textureAttr(), textureAttrName);
         if (GLHelper.isGLError(errbuf))
         {
            Log.e(TAG, "Error binding texture attribute " + textureAttrName);
            activity.runOnUiThread(new Runnable()
            {
               @Override
               public void run()
               {
                  Toast.makeText(activity, errbuf.toString(), Toast.LENGTH_LONG).show();
               }
            });
            lastError = errbuf.toString();
            return null;
         }
         glEnableVertexAttribArray(shaderAttr.textureAttr());
      }

      if (colorAttrName != null)
      {
         glBindAttribLocation(shaderAttr.shaderProgram, shaderAttr.colorAttr(), colorAttrName);
         if (GLHelper.isGLError(errbuf))
         {
            Log.e(TAG, "Error binding normal attribute " + colorAttrName);
            activity.runOnUiThread(new Runnable()
            {
               @Override
               public void run()
               {
                  Toast.makeText(activity, errbuf.toString(), Toast.LENGTH_LONG).show();
               }
            });
            lastError = errbuf.toString();
            return null;
         }
         glEnableVertexAttribArray(shaderAttr.colorAttr());
   }

      if (normalAttrName != null)
      {
         glBindAttribLocation(shaderAttr.shaderProgram, shaderAttr.normalAttr(), normalAttrName);
         if (GLHelper.isGLError(errbuf))
         {
            Log.e(TAG, "Error binding normal attribute " + normalAttrName);
            activity.runOnUiThread(new Runnable()
            {
               @Override
               public void run()
               {
                  Toast.makeText(activity, errbuf.toString(), Toast.LENGTH_LONG).show();
               }
            });
            lastError = errbuf.toString();
            return null;
         }
         glEnableVertexAttribArray(shaderAttr.normalAttr());
      }
      if (! GLHelper.linkShaderProgram(shaderAttr.shaderProgram, errbuf))
      {
         Log.e(TAG, "Error linking shader program");
         activity.runOnUiThread(new Runnable()
         {
            @Override
            public void run()
            {
               Toast.makeText(activity, errbuf.toString(), Toast.LENGTH_LONG).show();
            }
         });
         lastError = errbuf.toString();
         return null;
      }
      glUseProgram(shaderAttr.shaderProgram);
      if (GLHelper.isGLError(errbuf))
      {
         Log.e(TAG, "Error binding vertex attribute " + vertexAttrName + " (" + errbuf.toString() + ")");
         toast(errbuf.toString());
         lastError = errbuf.toString();
         return null;
      }
      return shaderAttr;
   }

   private String readAssetFile(String name, String def)
   //---------------------------------------------------
   {
      AssetManager am = activity.getAssets();
      InputStream is = null;
      BufferedReader br = null;
      try
      {
         is = am.open(name);
         br = new BufferedReader(new InputStreamReader(is));
         String line = null;
         StringBuilder sb = new StringBuilder();
         while ( (line = br.readLine()) != null)
            sb.append(line).append("\n");
         return sb.toString();
      }
      catch (Exception e)
      {
         return def;
      }
      finally
      {
         if (br != null) try { br.close(); } catch (Exception _e) {}
         if (is != null) try { is.close(); } catch (Exception _e) {}
      }
   }

   public void pause() { stopCamera(); }

   protected void resume()
   //-----------------------
   {
      if ( (headerFile != null) && (headerFile.exists()) && (framesFile != null) && (framesFile.exists()) )
      {
         StringBuilder errbuf = new StringBuilder();
         try { initCamera(errbuf); } catch (Exception e) { Log.e(TAG, "Camera initialization", e); activity.toast(errbuf.toString()); }
      }
      if (isPreviewing)
         startPreview(latch);
   }

   public void onSaveInstanceState(Bundle B)
   //---------------------------------------
   {
      B.putBoolean("isPreviewing", isPreviewing);
      if ( (headerFile != null) && (headerFile.exists()) && (framesFile != null) && (framesFile.exists()))
      {
         B.putString("headerFile", headerFile.getAbsolutePath());
         B.putString("framesFile", framesFile.getAbsolutePath());
      }
   }

   public void onRestoreInstanceState(Bundle B)
   //------------------------------------------
   {
      isPreviewing = B.getBoolean("isPreviewing");
      String s = B.getString("headerFile");
      if (s != null)
         headerFile = new File(s);
      s = B.getString("framesFile");
      if (s != null)
         framesFile = new File(s);
   }

   static class GLSLAttributes
   //=========================
   {
      private int currentAttribute = 0;
      int shaderProgram = -1;
      private int vertexAttr = -1;
      private int colorAttr = -1;
      private int normalAttr = -1;
      private int textureAttr = -1;

      public GLSLAttributes(int program) { shaderProgram = program; }

      public int vertexAttr()
      {
         if (vertexAttr == -1)
            vertexAttr = currentAttribute++;
         return vertexAttr;
      }

      public int colorAttr()
      {
         if (colorAttr == -1)
            colorAttr = currentAttribute++;
         return colorAttr;
      }

      public int normalAttr()
      {
         if (normalAttr == -1)
            normalAttr = currentAttribute++;
         return normalAttr;
      }

      public int textureAttr()
      {
         if (textureAttr == -1)
            textureAttr = currentAttribute++;
         return textureAttr;
      }
   }

   protected Handler createHandler(String name)
   //----------------------------------------
   {
      HandlerThread t = new HandlerThread(name);
      t.start();
      return new Handler(t.getLooper());
   }
}
