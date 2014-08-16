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

import android.app.Activity;
import android.content.res.AssetManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.support.v8.renderscript.*;
import android.util.*;
import android.widget.Toast;
import to.augmented.reality.android.common.gl.*;
import to.augmented.reality.android.em.ARCamera;

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
import java.util.List;

import static android.opengl.GLES20.*;

public class GLRenderer implements GLSurfaceView.Renderer
//=======================================================
{
   private static final String TAG = GLRenderer.class.getSimpleName();
   private static final String VERTEX_SHADER = "vertex.glsl";
   private static final String FRAGMENT_SHADER = "fragment.glsl";
   static final int SIZEOF_FLOAT = Float.SIZE/8;
   static final int SIZEOF_SHORT = Short.SIZE/8;

   private Activity activity;
   private ARSurfaceView view;

   private ARCamera camera = null;
   public ARCamera getCamera() { return camera; }

   private int cameraId = -1;
   private Camera.PreviewCallback previewCallback;

   private boolean isUseOwnBuffers = true;

   private int previewWidth = -1, previewHeight =-1;
   private SurfaceTexture previewSurfaceTexture = null;
   private File headerFile = null, framesFile = null;

   int getPreviewWidth() { return previewWidth;}
   int getPreviewHeight() { return previewHeight;}

   private int displayWidth, displayHeight;

   boolean isPreviewing = false;
   final Object lockSurface = new Object();
   volatile boolean isUpdateSurface = false;

   private GLTexture previewTexture;
   private int bufferSize = 0, nv21BufferSize = 0;
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

   GLSLAttributes previewShaderGlsl;

   String lastError = null;

   GLRenderer(Activity activity, ARSurfaceView surfaceView)
   //------------------------------------------------------
   {
      this.activity = activity;
      this.view = surfaceView;
      Matrix.setIdentityM(previewMVP, 0);
   }

   GLTexture.TextureFormat textureFormat = null;

   public void setPreviewFiles(File headerFile, File framesFile)
   //----------------------------------------------------------
   {
      this.headerFile = headerFile;
      this.framesFile = framesFile;
      try { initCamera(); } catch (Exception e) { Log.e(TAG, "Camera initialization", e); throw new RuntimeException("Camera initialization", e); }
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

   public boolean startPreview()
   //---------------------------
   {
      if (camera == null) return false;
      if (isPreviewing)
      {
         try
         {
            camera.setPreviewCallbackWithBuffer(null);
            camera.stopPreview();
            isPreviewing = false;
            previewCallback = null;
         }
         catch (Exception e)
         {
            Log.e(TAG, "", e);
         }
      }
      Camera.Size cameraSize = camera.getParameters().getPreviewSize();
      previewWidth = cameraSize.width;
      previewHeight = cameraSize.height;

      if ( (previewWidth < 0) || (previewHeight < 0) )
         throw new RuntimeException("Invalid resolution " + previewWidth + "x" + previewHeight);

      bufferSize = camera.getHeaderInt("BufferSize", -1);
      if (bufferSize == -1)
      {
         switch (camera.getFileFormat())
         {
            case RGB:      bufferSize = previewWidth * previewHeight * 3;
                           bufferSize += bufferSize % 4;
                           break;
            case RGBA:     bufferSize = previewWidth * previewHeight * 4; break;
            case RGB565:   bufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(ImageFormat.RGB_565) / 8; break;
            case NV21:     bufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;    break;
            default: throw new RuntimeException("Cannot determine buffer size. (BufferSize and FileFormat are missing from the header file");
         }
      }
      switch (camera.getFileFormat())// == ARCamera.RecordFileFormat.NV21)
      {
         case NV21:
            nv21BufferSize = bufferSize;
            bufferSize = previewWidth * previewHeight * 4;
            if (isUseOwnBuffers)
               cameraBuffer  = new byte[nv21BufferSize];
            break;
         case RGB:
            bufferSize += bufferSize % 4;
         case RGBA:
         case RGB565:
            if (isUseOwnBuffers)
               cameraBuffer  = new byte[bufferSize];
      }
      previewBuffer = null;
      if (previewByteBuffer != null)
         previewByteBuffer.clear();
      previewByteBuffer = null;
      previewBuffer = new byte[bufferSize];

      previewByteBuffer = ByteBuffer.allocateDirect(bufferSize);
      previewByteBuffer.put(previewBuffer);
      previewByteBuffer.rewind();
      previewSurfaceTexture = new SurfaceTexture(10);
      try
      {
         Camera.Parameters cameraParameters = camera.getParameters();
         cameraParameters.setPreviewSize(previewWidth, previewHeight);
         cameraParameters.setPreviewFormat(ImageFormat.NV21);
         camera.setParameters(cameraParameters);
         previewCallback = new CameraPreviewCallback();
         if (isUseOwnBuffers)
         {
            camera.setPreviewCallbackWithBuffer(previewCallback);
            camera.addCallbackBuffer(cameraBuffer);
         }
         else
            camera.setPreviewCallback(previewCallback);
         camera.setPreviewTexture(previewSurfaceTexture);
         camera.startPreview();
         isPreviewing = true;
      }
      catch (final Exception e)
      {
         Log.e(TAG, "Initialising camera preview", e);
         toast("Error initialising camera preview: " + e.getMessage());
         return false;
      }
      return true;
   }

   private void stopCamera()
   //----------------------
   {
      if (camera != null)
      {
         if (isPreviewing)
         {
            try
            {
               if (previewSurfaceTexture != null)
                  previewSurfaceTexture.setOnFrameAvailableListener(null);
               camera.setPreviewCallbackWithBuffer(null);
               camera.stopPreview();
            }
            catch (Exception e)
            {
               Log.e(TAG, "", e);
            }
         }
         try { camera.release(); } catch (Exception _e) { Log.e(TAG, _e.getMessage()); }
      }
      camera = null;
      if (realCamera != null)
         try { realCamera.release(); } catch (Exception e) {}
      realCamera = null;
   }

   Camera realCamera = null;

   protected boolean initCamera() throws Exception
   //-----------------------------------------------
   {
      if ( (headerFile == null) || (! headerFile.exists()) || (! headerFile.canRead()) )
         throw new RuntimeException("Invalid or non-existent replay header file (" +
                                          ((headerFile == null) ? "null" : headerFile.getAbsolutePath()));
      if ( (framesFile == null) || (! framesFile.exists()) || (! framesFile.canRead()) )
         throw new RuntimeException("Invalid or non-existent replay header file (" +
                                          ((framesFile == null) ? "null" : framesFile.getAbsolutePath()));
      try
      {
         stopCamera();
         Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
         int frontId = -1, backId = -1;
         for (int i = 0; i < Camera.getNumberOfCameras(); i++)
         {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK)
               backId = i;
            else if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)
               frontId = i;
         }
         if (backId >= 0)
         {
            cameraId = backId;
            try { realCamera = Camera.open(cameraId); } catch (Exception _e) { realCamera = null; }
            if (realCamera != null)
            {
               camera = new ARCamera(activity, cameraId, realCamera);
               camera = (ARCamera) ARCamera.open(cameraId);
            }
         }
         if ( (camera == null) && (frontId >= 0) )
         {
            cameraId = frontId;
            try { realCamera = Camera.open(cameraId); } catch (Exception _e) { realCamera = null; }
            if (realCamera != null)
            {
               camera = new ARCamera(activity, cameraId, realCamera);
               camera = (ARCamera) ARCamera.open(cameraId);
            }
         }
         if (camera == null)
         {
            camera = new ARCamera(activity, -1);
            cameraId = camera.getId();
            camera = (ARCamera) ARCamera.open(cameraId);
         }
         if (camera != null)
            camera.setFiles(headerFile, framesFile);
         else
            return false;
         camera.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
//         setDisplayOrientation();
         camera.setDisplayOrientation(180);
         Camera.Parameters cameraParameters = camera.getParameters();
         if (cameraParameters.isVideoStabilizationSupported())
            cameraParameters.setVideoStabilization(true);
         List<Camera.Size> L = cameraParameters.getSupportedPreviewSizes();
         Camera.Size sz = cameraParameters.getPreviewSize();
         List<String> focusModes = cameraParameters.getSupportedFocusModes();
//         if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
//            cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
//         else
         if  (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_INFINITY))
            cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
         if (cameraParameters.isZoomSupported())
            cameraParameters.setZoom(0);
         cameraParameters.setPreviewFrameRate(30000);
         camera.setParameters(cameraParameters);
         ARCamera.RecordFileFormat fileFormat = camera.getFileFormat();
         switch (fileFormat)
         {
            case NV21:
            case RGBA:
               textureFormat = GLTexture.TextureFormat.RGBA;
               break;
            case RGB:  // Not always a safe option, see http://www.opengl.org/wiki/Common_Mistakes#Texture_upload_and_pixel_reads
               textureFormat = GLTexture.TextureFormat.RGB;
               break;
            case RGB565:
               textureFormat = GLTexture.TextureFormat.RGB565;
               break;
            default: throw new RuntimeException(fileFormat.name() + ": Unknown recording file format");
         }
         return true;
      }
      catch (Exception e)
      {
         camera = null;
         toast(String.format("Could not obtain rear facing camera (%s). Check if it is in use by another application.",
                             e.getMessage()));
         Log.e(TAG, "Camera.open()", e);
         return false;
      }
   }

   private void toast(final String s)
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
      if ( (textureFormat != null) && ( (previewTexture == null) || (! previewTexture.isValid()) ) )
      {
         previewTexture = GLTexture.create(GL_TEXTURE0, GL_TEXTURE_2D, textureFormat, cameraTextureUniform);
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
               if (! previewTexture.load(previewByteBuffer, previewWidth, previewHeight, (!isReloadTexture)))
                  throw new RuntimeException("Texture error: " + previewTexture.lastError() + ": " +
                                             previewTexture.lastErrorMessage());
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
         if ( (camera != null) && (isPreviewed) && (isUseOwnBuffers) )
            camera.addCallbackBuffer(cameraBuffer);
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
      glUseProgram(shaderAttr.shaderProgram);
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

   public void review(float startBearing, float endBearing, int pauseMs, boolean isRepeat, ARCamera.Reviewable reviewable)
   //---------------------------------------------------------------------------------------------------------------------
   {
      if ( (camera == null) || (camera.isReviewing()) ) return;
      if ( (camera != null) && (isPreviewing) )
         camera.startReview(startBearing, endBearing, pauseMs, isRepeat, reviewable);
   }

   public void pause() { stopCamera(); }

   protected void resume()
   //-----------------------
   {
      if ( (headerFile != null) && (headerFile.exists()) && (framesFile != null) && (framesFile.exists()) )
         try { initCamera(); } catch (Exception e) { Log.e(TAG, "Camera initialization", e); throw new RuntimeException("Camera initialization", e); }
      if (isPreviewing)
         startPreview();
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

   public boolean isReviewing()
   //--------------------------
   {
      if (camera != null)
         return camera.isReviewing();
      return false;
   }

   public void stopReviewing()
   //-------------------------
   {
      if (camera != null)
         camera.stopReview();
   }

   public float getReviewCurrentBearing()
   {
      if (camera != null)
         return camera.getReviewCurrentBearing();
      return -1;
   }

   public void setReviewBearing(float bearing) { if (camera != null) camera.setReviewCurrentBearing(bearing); }

   class CameraPreviewCallback implements Camera.PreviewCallback
   //==================================================================
   {
      RenderScript rsNv21toRGBA;
      ScriptIntrinsicYuvToRGB YUVToRGBA;
      Allocation ain, aOut;

      public CameraPreviewCallback()
      //-----------------------------
      {
         if (GLRenderer.this.camera.getFileFormat() == ARCamera.RecordFileFormat.NV21)
         {
            rsNv21toRGBA = RenderScript.create(activity);
            YUVToRGBA = ScriptIntrinsicYuvToRGB.create(rsNv21toRGBA, Element.U8_4(rsNv21toRGBA));
            Type.Builder yuvType = new Type.Builder(rsNv21toRGBA, Element.U8(rsNv21toRGBA)).setX(previewWidth).
                  setY(previewHeight).setMipmaps(false).setYuvFormat(ImageFormat.NV21);
            Type.Builder rgbaType = new Type.Builder(rsNv21toRGBA, Element.RGBA_8888(rsNv21toRGBA)).setX(previewWidth).
                  setY(previewHeight).setMipmaps(false);
            ain = Allocation.createTyped(rsNv21toRGBA, yuvType.create(), Allocation.USAGE_SCRIPT);
            aOut = Allocation.createTyped(rsNv21toRGBA, rgbaType.create(), Allocation.USAGE_SCRIPT);
         }
      }

      @Override
      public void onPreviewFrame(byte[] data, Camera camera)
      //----------------------------------------------------
      {
         if (data == null)
         {
            if ( (isUseOwnBuffers) && (camera != null) )
               camera.addCallbackBuffer(cameraBuffer);
            return;
         }
         if (GLRenderer.this.camera.getFileFormat() == ARCamera.RecordFileFormat.NV21)
         {
            ain.copyFrom(data);
            YUVToRGBA.setInput(ain);
            YUVToRGBA.forEach(aOut);
            aOut.copyTo(previewBuffer);
            synchronized (this)
            {
               previewByteBuffer.clear();
               previewByteBuffer.put(previewBuffer);
            }
         }
         else
         {
            synchronized (this)
            {
               previewByteBuffer.clear();
               previewByteBuffer.put(data);
            }
         }
         isUpdateSurface = true;
         view.requestRender();
      }
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
}
