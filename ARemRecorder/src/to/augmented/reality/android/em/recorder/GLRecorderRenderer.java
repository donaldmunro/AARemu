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

package to.augmented.reality.android.em.recorder;

import android.app.*;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.opengl.GLES11Ext;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.*;
import android.os.Process;
import android.support.v8.renderscript.*;
import android.util.Log;
import android.widget.Toast;

import to.augmented.reality.android.common.gl.*;
import to.augmented.reality.android.common.math.*;
import to.augmented.reality.android.common.sensor.orientation.*;
import to.augmented.reality.em.recorder.ScriptC_RGBAtoRGB565;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.*;
import java.util.concurrent.*;

import static android.opengl.GLES20.*;
import static to.augmented.reality.android.common.sensor.orientation.OrientationProvider.ORIENTATION_PROVIDER;

public class GLRecorderRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener
//========================================================================================================
{
   public enum RecordFileFormat { RGBA, RGB, RGB565, NV21 }
   public enum RecordMode { RETRY, TRAVERSE, TRAVERSE2 }

   private static final String TAG = GLRecorderRenderer.class.getSimpleName();
   private static final String VERTEX_SHADER = "vertex.glsl";
   private static final String FRAGMENT_SHADER = "fragment.glsl";
   static final int SIZEOF_FLOAT = Float.SIZE / 8;
   static final int SIZEOF_SHORT = Short.SIZE / 8;
   static final boolean DIRECT_TO_SURFACE = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB);
   final private static int GL_TEXTURE_EXTERNAL_OES = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)
                                                        ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES
                                                        : 0x8D65;

   RecorderActivity activity;
   private ARSurfaceView view;
   private boolean isInitialised = false;

   private int uIndex =-1, vIndex;

   private int displayWidth, displayHeight;

   private ORIENTATION_PROVIDER orientationProviderType = ORIENTATION_PROVIDER.DEFAULT;
   OrientationProvider orientationProvider = null;
   BearingRingBuffer bearingBuffer = new BearingRingBuffer(15);
   volatile float currentBearing = 0, lastBearing = -1, bearingDelta = 0;
   long currentBearingTimestamp =-1L, lastBearingTimestamp = -1L;
   volatile boolean isRecording = false, isStopRecording = false, mustStopNow = false;
   SurfaceTexture previewSurfaceTexture = null;
   ByteBuffer previewByteBuffer = null;
   final Object lockSurface = new Object();
   volatile boolean isUpdateSurface = false, isUpdateTexture = false;

   boolean isWaitingGPS = true, isWaitingNetLoc = true;
   Location recordLocation = null;

   int previewTexture = -1;
   private int yComponent =-1;
   private int uComponent =-1;
   private int vComponent =-1;
   public int getPreviewTexture() { return previewTexture; }

   private int rgbaBufferSize = 0;
   private int rgbBufferSize = 0;
   int nv21BufferSize = 0;
   private int rgb565BufferSize = 0;
   boolean isAwaitingTextureFix = false;

   private int previewMVPLocation = -1, cameraTextureUniform = -1, yComponentUniform =-1, uComponentUniform =-1,
               vComponentUniform; //, previewSTMLocation = -1
   private float[] previewMVP = new float[16]; //, previewSTM = new float[16];

   private int arrowMVPLocation = -1, arrowColorUniform = -1;
   private float[] arrowMVP = new float[16];

   int screenWidth = -1;
   public int getScreenWidth() { return screenWidth; }

   int screenHeight = -1;
   public int getScreenHeight()
   {
      return screenHeight;
   }

   final private ByteBuffer previewPlaneVertices =
         ByteBuffer.allocateDirect(SIZEOF_FLOAT * 12).order(ByteOrder.nativeOrder());
   final private ByteBuffer previewPlaneTextures =
         ByteBuffer.allocateDirect(SIZEOF_FLOAT * 8).order(ByteOrder.nativeOrder());
   final private ByteBuffer previewPlaneFaces =
         ByteBuffer.allocateDirect(SIZEOF_SHORT * 6).order(ByteOrder.nativeOrder());
   final private ByteBuffer arrowVerticesBuffer =
         ByteBuffer.allocateDirect(SIZEOF_FLOAT * 21).order(ByteOrder.nativeOrder());
   final private ByteBuffer arrowFacesBuffer =
         ByteBuffer.allocateDirect(SIZEOF_SHORT * 9).order(ByteOrder.nativeOrder());

   float[] projectionM = new float[16], viewM = new float[16];

   public int previewWidth;
   public int previewHeight;

   GLSLAttributes previewShaderGlsl, arrowShaderGlsl;

   CameraPreviewThread previewer;

   byte[] cameraBuffer = null;

   String lastError = null;

   private RecordMode recordingMode = null;
   private RecordFileFormat recordFileFormat = RecordFileFormat.RGBA;
   public void setRecordFileFormat(RecordFileFormat recordFileFormat) { this.recordFileFormat = recordFileFormat; }
   public RecordFileFormat getRecordFileFormat() { return recordFileFormat; }
   private ConditionVariable recordingCondVar = null;
   private ExecutorService recordingPool = createSingleThreadPool("Recording"),
                           stopRecordingPool = createSingleThreadPool("StopRecording");


   private ExecutorService createSingleThreadPool(final String name)
   //---------------------------------------------------------
   {
      ExecutorService executor = Executors.newSingleThreadExecutor(
      new ThreadFactory()
      //=================
      {
         @Override
         public Thread newThread(Runnable r)
         //---------------------------------
         {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName(name);
            return t;
         }
      });
      return executor;
   }

   private RecordingThread recordingThread;
   static final public float[] GREEN = { 0, 1, 0 }, RED = { 1, 0, 0 }, BLUE = { 0, 0, 0.75f };
   float[] arrowColor = GREEN;
   float arrowRotation = 0.0f;

   public void getLastBuffer(byte[] buffer) { previewer.getLastBuffer(buffer); }

   GLRecorderRenderer(RecorderActivity activity, ARSurfaceView surfaceView, ORIENTATION_PROVIDER orientationProviderType)
   //------------------------------------------------------------------------------------------------------------
   {
      this.activity = activity;
      this.view = surfaceView;

      Matrix.setIdentityM(previewMVP, 0);
//      previewSTM = new float[] // Rotates texture as OpenGL coords start at bl while device coords start at tl
//            {
//                  -1, 0, 0, 0,
//                  0, 1, 0, 0,
//                  0, 0, 1, 0,
//                  1, 0, 0, 1,
//            };
      initOrientationSensor(orientationProviderType);
   }

   @Override
   public void onSurfaceCreated(GL10 gl, EGLConfig config)
   //---------------------------------------------------------------
   {
      glDisable(GL10.GL_DITHER);
      glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_FASTEST);
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
      displayWidth = width - 2;
      displayHeight = height - 2;
      xScaleArrow = screenWidth / (ARROW_WIDTH * 4); // width of arrow = 5 : 5x = screenWidth/4 -> x = screenWidth/5 * 4
      yScaleArrow = screenHeight / (ARROW_HEIGHT * 4);  // height of arrow = 1 : y = screenHeight/4
      xTranslateArrow = screenWidth / 2.0f - (xScaleArrow * ARROW_WIDTH) / 2.0f;
      yTranslateArrow = screenHeight / 2.0f - (yScaleArrow * ARROW_HEIGHT) / 2.0f;

      try
      {
         initRender();
         if ( (previewer == null) || (previewer.camera == null) )
            initPreviewer(true);
         else
            previewer.initCamera();
         isInitialised = true;
      }
      catch (Exception e)
      {
         Log.e(TAG, "OpenGL Initialization error", e);
         throw new RuntimeException("OpenGL Initialization error", e);
      }
   }

   boolean isLocationSensorInititialised = false;
//   SensorEventListener rotationListener = null;
   LocationListener gpsListener = null, netListener = null;

   public void initLocationSensor()
   //-------------------------------
   {
      if (isLocationSensorInititialised)
         return;
      isLocationSensorInititialised = true;
      recordLocation = null;
      LocationManager locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
      netListener = new LocationListener()
      {
         @Override
         public void onLocationChanged(Location location)
         //--------------------------------------------------------
         {
            if (! isWaitingGPS) return;
            if (isWaitingNetLoc)
            {
               recordLocation = location;
               toast("Updated location using Network to " + String.format("%12.7f,%12.7f", location.getLatitude(),
                                                                          location.getLongitude()));
               GLRecorderRenderer.this.activity.onNetLocationConnected(true);
               isWaitingNetLoc = false;
            }
            GLRecorderRenderer.this.activity.onLocationChanged(location);
         }

         // @formatter:off
         @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

         @Override public void onProviderEnabled(String provider) { }

         @Override public void onProviderDisabled(String provider)
         {
            GLRecorderRenderer.this.activity.onNetLocationConnected(false);
         }
         // @formatter:on
      };
      gpsListener = new LocationListener()
      {
         @Override
         public void onLocationChanged(Location location)
         //--------------------------------------------------------
         {
            recordLocation = location;
            GLRecorderRenderer.this.activity.onGpsConnected(true);
            isWaitingNetLoc = isWaitingGPS = false;
            GLRecorderRenderer.this.activity.onLocationChanged(location);
            toast("Updated location using GPS to " + String.format("%12.7f,%12.7f", location.getLatitude(),
                                                                   location.getLongitude()));

         }

         // @formatter:off
         @Override public void onStatusChanged(String provider, int status, Bundle extras) { }

         @Override public void onProviderEnabled(String provider) { }

         @Override public void onProviderDisabled(String provider) { GLRecorderRenderer.this.activity.onGpsConnected(false); }
         // @formatter:on
      };
      if (locationManager != null)
      {
         if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
            locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, netListener, null);
         if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, gpsListener, null);

      }
   }

   private void stopSensors()
   //------------------------
   {
      SensorManager sensorManager = (SensorManager) activity.getSystemService(Activity.SENSOR_SERVICE);
      if (sensorManager != null)
      {
//         sensorManager.unregisterListener(rotationListener);
         if (orientationProvider.isStarted())
            orientationProvider.stop();
      }
      LocationManager locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
      if (locationManager != null)
      {
         if (netListener != null)
            locationManager.removeUpdates(netListener);
         if (gpsListener != null)
            locationManager.removeUpdates(gpsListener);
      }
   }

   Bundle lastInstanceState = new Bundle();

   public void onSaveInstanceState(Bundle B)
   //----------------------------------------------
   {
      if (previewer != null)
         previewer.pause(B);;
      B.putBoolean("isRecording", isRecording);
      B.putBoolean("isStopRecording", isStopRecording);
      if ( (isRecording) && (recordingMode != null) )
         B.putString("recordingMode", recordingMode.name());
      B.putInt("displayWidth", displayWidth);
      B.putInt("displayHeight", displayHeight);
      if (recordingThread != null)
         recordingThread.pause(B);
      B.putInt("rgbaBufferSize", rgbaBufferSize);
      B.putInt("rgbBufferSize", rgbBufferSize);
      B.putInt("rgb565BufferSize", rgb565BufferSize);
      B.putInt("nv21BufferSize", nv21BufferSize);
      B.putBoolean("isWaitingGPS", isWaitingGPS);
      B.putBoolean("isWaitingNetLoc", isWaitingNetLoc);
      if (recordLocation != null)
      {
         B.putDouble("latitude", recordLocation.getLatitude());
         B.putDouble("longitude", recordLocation.getLongitude());
         B.putDouble("altitude", recordLocation.getAltitude());
      }
      B.putString("orientationProviderType", orientationProviderType.name());
      B.putString("recordFileFormat", recordFileFormat.name());
      if (recordDir != null)
         B.putString("recordDir", recordDir.getAbsolutePath());
      if (recordFramesFile != null)
         B.putString("recordFramesFile", recordFramesFile.getAbsolutePath());
      if (recordFileName != null)
         B.putString("recordFileName", recordFileName);
      B.putFloat("lastSaveBearing", lastSaveBearing);
      lastInstanceState = new Bundle(B);
   }

   public void pause()
   //-----------------
   {
      if (isStopRecording)
      {  // @formatter:off
         mustStopNow = true;
         try { Thread.sleep(70); } catch (Exception _e) {}
         if (NV21toRGBInputRAF != null)
            try { NV21toRGBInputRAF.close(); } catch (Exception _e) {}
         if (NV21toRGBOutputRAF != null)
            try { NV21toRGBOutputRAF.close(); } catch (Exception _e) {}
      }  // @formatter:on
      else if (isRecording)
      {  // @formatter:off
         isRecording = false;
         recordingCondVar.open();
         try { Thread.sleep(50); } catch (Exception _e) {}
         recordingThread.cancel(true);
         if (recordingThread.getStatus() == AsyncTask.Status.RUNNING)
            try { recordingPool.shutdownNow(); } catch (Exception _ee) {}
         isRecording = true;
      }  // @formatter:on
      stopCamera();
      stopSensors();
      isLocationSensorInititialised = false;
   }

   public void startPreview(int width, int height)
   //---------------------------------------------
   {
      if (previewer != null)
      {
         if (previewer.isPreviewing())
         {
            if ((previewer.getPreviewWidth() == width) && (previewer.getPreviewHeight() == height))
               return;
            if (previewSurfaceTexture != null)
               previewSurfaceTexture.setOnFrameAvailableListener(null);
         }
         rgbaBufferSize = width * height * 4; // RGBA buffer size
         rgbBufferSize = width * height * 3; // RGB buffer size
         rgb565BufferSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.RGB_565) / 8;
         // or nv21BufferSize = Width * Height + ((Width + 1) / 2) * ((Height + 1) / 2) * 2
         if (DIRECT_TO_SURFACE)
            nv21BufferSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
         else
            nv21BufferSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.YV12) / 8;
         vIndex = width * height;
         uIndex = (int) (vIndex * 1.25);
         previewByteBuffer = ByteBuffer.allocateDirect(nv21BufferSize);
         cameraBuffer = new byte[nv21BufferSize];
         previewer.startPreview(width, height);
      }
   }

   public boolean isPreviewing() { return (previewer != null) ? previewer.isPreviewing() : false; }

   private void stopCamera()
   //----------------------
   {
      if (previewSurfaceTexture != null)
         previewSurfaceTexture.setOnFrameAvailableListener(null);
      if ( (previewer != null) && (previewer.isPreviewing()) )
         previewer.stopCamera();
   }

   public void onRestoreInstanceState(Bundle B)
   //------------------------------------------
   {
      if (previewer != null)
         previewer.restore(B);
      isRecording = B.getBoolean("isRecording");
      isStopRecording = B.getBoolean("isStopRecording");
      displayWidth = B.getInt("displayWidth");
      displayHeight = B.getInt("displayHeight");
      rgbaBufferSize = B.getInt("rgbaBufferSize");
      rgbBufferSize = B.getInt("rgbBufferSize");
      rgb565BufferSize = B.getInt("rgb565BufferSize");
      nv21BufferSize = B.getInt("nv21BufferSize");
      isWaitingGPS = B.getBoolean("isWaitingGPS");
      isWaitingNetLoc = B.getBoolean("isWaitingNetLoc");
      double latitude = B.getDouble("latitude", Double.MAX_VALUE);
      if (latitude < Double.MAX_VALUE)
      {
         double longitude = B.getDouble("longitude");
         double altitude = B.getDouble("altitude");
         recordLocation = new Location( (isWaitingGPS) ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER);
         recordLocation.setTime(System.currentTimeMillis());
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
            recordLocation.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
         else
         {
            // Kludge because some location APIs requires elapsedtime in nanos but call is not available in all Android versions.
            try
            {
               Method makeCompleteMethod = null;
               makeCompleteMethod = Location.class.getMethod("makeComplete");
               if (makeCompleteMethod != null)
                  makeCompleteMethod.invoke(recordLocation);
            }
            catch (Exception e)
            {
               e.printStackTrace();
            }
         }
         recordLocation.setLatitude(latitude);
         recordLocation.setLongitude(longitude);
         recordLocation.setAltitude(altitude);
         recordLocation.setAccuracy(1.0f);
      }
      orientationProviderType = ORIENTATION_PROVIDER.valueOf(B.getString("orientationProviderType",
                                                                         ORIENTATION_PROVIDER.DEFAULT.name()));
      recordFileFormat = RecordFileFormat.valueOf(B.getString("recordFileFormat", RecordFileFormat.RGB.name()));
      String recordDirName = B.getString("recordDir", null);
      if (recordDirName != null)
         recordDir = new File(recordDirName);
      String recordFramesFileName = B.getString("recordFramesFile", null);
      if (recordFramesFileName != null)
         recordFramesFile = new File(recordFramesFileName);
      recordFileName = B.getString("recordFileName", null);
      lastSaveBearing = B.getFloat("lastSaveBearing", 0);
      if (isRecording)
      {
         try { recordingMode = RecordMode.valueOf(B.getString("recordingMode")); } catch (Exception _e) { recordingMode = null; }
            if ( (recordingThread == null) && (recordingMode != null) )
         {
            recordingThread = RecordingThread.createRecordingThread(recordingMode, this);
            recordingThread.restore(B);
         }
      }
      lastInstanceState = B;
   }

   protected void resume()
   //-----------------------
   {
//      initCamera();
//      initRender();
      mustStopNow = false;
      initOrientationSensor(orientationProviderType);
      if (isStopRecording)
      {
         if (recordingThread == null)
            recordingThread = RecordingThread.createRecordingThread(recordingMode, this);
         else
         recordingThread.restore(lastInstanceState);
         isRecording = true;
         ExecutorService stopRecordingPool =  Executors.newSingleThreadExecutor();
         stopRecordingPool.submit(new Runnable()
         //=====================================
         {
            @Override public void run() { stopRecording(false); }
         });
      }
      else if (isRecording)
         resumeRecording(lastInstanceState);
      lastSaveBearing = 0;
   }

   private void initPreviewer(boolean isInitCamera)
    //---------------------------------------------
   {
      previewer = new CameraPreviewThread(this, isInitCamera);
      previewer.setPreviewListener(new CameraPreviewThread.Previewable()
      //-------------------------------------------------------------------------
      {
         @Override
         public void onCameraFrame(long timestamp, byte[] frame)
         //-----------------------------------------------------
         {
            if (!DIRECT_TO_SURFACE)
            {
               synchronized (lockSurface)
               {
                  previewByteBuffer.rewind();
                  previewByteBuffer.put(frame);
                  isUpdateSurface = true;
               }
               view.requestRender();
            }
         }
      });
      previewer.start();
   }

   @Override
   public void onFrameAvailable(SurfaceTexture surfaceTexture)
   //-------------------------------------------------------------------
   {
      synchronized (lockSurface) { isUpdateSurface = true; }
      view.requestRender();
   }

   void toast(final String s)
   //--------------------------------
   {
      toast(s, Toast.LENGTH_LONG);
   }

   private void toast(final String s, final int duration)
   //----------------------------------------------------
   {
      activity.runOnUiThread(new Runnable()
      {
         @Override
         public void run()
         {
            Toast.makeText(activity, s, duration).show();
         }
      });
   }

   private boolean initRender()
   //---------------------------------
   {
      final StringBuilder errbuf = new StringBuilder();
      String shaderDir = "camera/";
      if (DIRECT_TO_SURFACE)
         shaderDir += "direct/";
      else
         shaderDir += "texture/";
      previewShaderGlsl = loadShaders(shaderDir + "preview/", "vPosition", null, "vTexCoord", null);
      if (previewShaderGlsl == null)
         return false;

      if (! initTextures(previewShaderGlsl, errbuf))
      {
         Log.e(TAG, "Error initializing textures: " + errbuf.toString());
         throw new RuntimeException("Error initializing textures: " + errbuf.toString());
      }

      previewMVPLocation = glGetUniformLocation(previewShaderGlsl.shaderProgram, "MVP");
      if ((GLHelper.isGLError(errbuf)) || (previewMVPLocation == -1))
      {
         Log.e(TAG, "Error getting mvp matrix uniform 'MVP'");
         activity.runOnUiThread(new Runnable()
         {
            @Override
            public void run()
            {
               Toast.makeText(activity, errbuf.toString(), Toast.LENGTH_LONG).show();
            }
         });
         lastError = errbuf.toString();
         return false;
      }

//      if (DIRECT_TO_SURFACE)
//      {
//         previewSTMLocation = glGetUniformLocation(previewShaderGlsl.shaderProgram, "ST");
//         if ((GLHelper.isGLError(errbuf)) || (previewSTMLocation == -1))
//         {
//            Log.e(TAG, "Error getting texture transform matrix uniform 'ST'");
//            activity.runOnUiThread(new Runnable()
//            {
//               @Override public void run() { Toast.makeText(activity, errbuf.toString(), Toast.LENGTH_LONG).show(); }
//            });
//            lastError = errbuf.toString();
//            return false;
//         }
//      }

      float[] planeVertices =
            {
                  displayWidth, 1f, -50.0f, // bottom-right
                  1.0f, 1f, -50.0f, // bottom-left
                  displayWidth, displayHeight, -50.0f, // top-right
                  1.0f, displayHeight, -50.0f, // top-left
            };
      previewPlaneVertices.clear();
      FloatBuffer fb = previewPlaneVertices.asFloatBuffer();
      fb.put(planeVertices);
      final float[] planeTextures =
            {
                  1, 1,
                  0, 1,
                  1, 0,
                  0, 0
            };

      previewPlaneTextures.clear();
      fb = previewPlaneTextures.asFloatBuffer();
      fb.put(planeTextures);
      short[] planeFaces = {2, 3, 1, 0, 2, 1};
      //short[] planeFaces= { 0,1,2, 2,1,3 };
      previewPlaneFaces.clear();
      ShortBuffer sb = previewPlaneFaces.asShortBuffer();
      sb.put(planeFaces);

      Matrix.orthoM(projectionM, 0, 0, screenWidth, 0, screenHeight, 0.2f, 120.0f);
      Matrix.setLookAtM(viewM, 0, 0, 0, 0, 0, 0, -1, 0, 1, 0);
      Matrix.multiplyMM(previewMVP, 0, projectionM, 0, viewM, 0);

      arrowShaderGlsl = loadShaders("render/", "vPosition", null, null, null);
      if (arrowShaderGlsl == null)
         throw new RuntimeException("Error loading arrow display shader");
      arrowMVPLocation = glGetUniformLocation(arrowShaderGlsl.shaderProgram, "MVP");
      arrowColorUniform = glGetUniformLocation(arrowShaderGlsl.shaderProgram, "uvColor");

      float[] arrowVertices =
            {
                  4, 0.2f, -10.0f, // bottom-right
                  0, 0.2f, -10.0f, // bottom-left
                  4, 0.8f, -10.0f, // top-right
                  0, 0.8f, -10.0f, // top-left

                  // Arrow head
                  4, 0,    -10,   //   4
                  5, 0.5f, -10,   //   5
                  4, 1,    -10    //   6
            };

      arrowVerticesBuffer.clear();
      fb = arrowVerticesBuffer.asFloatBuffer();
      fb.put(arrowVertices);
      short[] arrowFaces = {2, 3, 1, 0, 2, 1, 5, 6, 4};
      arrowFacesBuffer.clear();
      sb = arrowFacesBuffer.asShortBuffer();
      sb.put(arrowFaces);

      return true;
   }

   private boolean initTextures(GLSLAttributes previewShaderGlsl, StringBuilder errbuf)
   //------------------------------------------------
   {
      if (DIRECT_TO_SURFACE)
      {
         final int texTarget = GL_TEXTURE_EXTERNAL_OES;
         int[] texnames = new int[1];
         texnames[0] = -1;
         glGenTextures(1, texnames, 0);
         previewTexture = texnames[0];
         glActiveTexture(GL_TEXTURE0);
         glBindTexture(texTarget, previewTexture);
         if ( (GLHelper.isGLError(errbuf)) || (! glIsTexture(previewTexture)) )
         {
            Log.e(TAG, "Error binding texture");
            return false;
         }
         glTexParameteri(texTarget, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
         glTexParameteri(texTarget, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
         glTexParameteri(texTarget, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
         glTexParameteri(texTarget, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
         glBindTexture(texTarget, 0);
         if ( (previewShaderGlsl != null) && (previewShaderGlsl.shaderProgram >= 0) )
         {
            cameraTextureUniform = glGetUniformLocation(previewShaderGlsl.shaderProgram, "previewSampler");
            if ((GLHelper.isGLError(errbuf)) || (cameraTextureUniform == - 1))
            {
               Log.e(TAG, "Error getting texture uniform 'previewSampler'");
               toast(errbuf.toString());
               lastError = errbuf.toString();
               return false;
            }
         }
      }
      else if ( (! DIRECT_TO_SURFACE) &&
                ( (yComponent < 0) || (! glIsTexture(yComponent)) ||
                  (uComponent < 0) || (! glIsTexture(uComponent))  ||
                  (vComponent < 0) || (! glIsTexture(vComponent)) ) )
      {
         final int texTarget = GL_TEXTURE_2D;
         int[] texnames = new int[3];
         glGenTextures(3, texnames, 0);
         yComponent = texnames[0];
         glActiveTexture(GL_TEXTURE0);
         glBindTexture(texTarget, yComponent);
         glTexParameteri(texTarget, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
         glTexParameteri(texTarget, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

         uComponent = texnames[1];
         glActiveTexture(GL_TEXTURE1);
         glBindTexture(texTarget, uComponent);
         glTexParameteri(texTarget, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
         glTexParameteri(texTarget, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

         vComponent = texnames[2];
         glActiveTexture(GL_TEXTURE2);
         glBindTexture(texTarget, vComponent);
         glTexParameteri(texTarget, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
         glTexParameteri(texTarget, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

         if (GLHelper.isGLError(errbuf))
         {
            Log.e(TAG, "Error binding texture");
            return false;
         }
         glBindTexture(texTarget, 0);

         yComponentUniform = glGetUniformLocation(previewShaderGlsl.shaderProgram, "y_texture");
         if ((GLHelper.isGLError(errbuf)) || (yComponentUniform == -1))
         {
            Log.e(TAG, "Error getting texture uniform 'y_texture'");
            return false;
         }
         uComponentUniform = glGetUniformLocation(previewShaderGlsl.shaderProgram, "u_texture");
         if ((GLHelper.isGLError(errbuf)) || (uComponentUniform == -1))
         {
            Log.e(TAG, "Error getting texture uniform 'u_texture'");
            return false;
         }
         vComponentUniform = glGetUniformLocation(previewShaderGlsl.shaderProgram, "v_texture");
         if ((GLHelper.isGLError(errbuf)) || (vComponentUniform == -1))
         {
            Log.e(TAG, "Error getting texture uniform 'v_texture'");
            return false;
         }
      }
      return true;
   }

   final static private float ARROW_WIDTH = 5;
   final static private float ARROW_HEIGHT = 1;
   private float xScaleArrow = 1, yScaleArrow = 1, xTranslateArrow = 0, yTranslateArrow = 0;

   private float[] arrowModelView = new float[16], scaleM = new float[16], translateM = new float[16],
         rotateM = new float[16], modelM = new float[16];
   StringBuilder glerrbuf = new StringBuilder();

   @Override
   public void onDrawFrame(GL10 gl)
   //------------------------------
   {
      glerrbuf.setLength(0);
      boolean isPreviewed = false;
      final int texTarget = (DIRECT_TO_SURFACE) ? GL_TEXTURE_EXTERNAL_OES : GL_TEXTURE_2D;
      try
      {
         glViewport(0, 0, screenWidth, screenHeight);
         glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
         glUseProgram(previewShaderGlsl.shaderProgram);
         if (DIRECT_TO_SURFACE)
         {
            if ( (previewTexture < 0) || (! glIsTexture(previewTexture)) )
            {
               initTextures(previewShaderGlsl, null);
               if (isAwaitingTextureFix)
                  previewer.startFixedPreview();
            }


            glActiveTexture(GL_TEXTURE0);
            glUniform1i(cameraTextureUniform, 0);
            glBindTexture(texTarget, previewTexture);
            if (isUpdateSurface)
            {
               isUpdateSurface = false;
               synchronized (lockSurface)
               {
                  previewSurfaceTexture.updateTexImage();
                  // Using the texture transform matrix reflects (when using landscape). Not using a texture transform
                  // (ie identity matrix) works fine ?????
                  // previewSurfaceTexture.getTransformMatrix(previewSTM);
               }
               isPreviewed = true;
            }
         }
         else
         {
            if (isUpdateSurface)
            {
               final int hw = previewWidth >> 1;
               final int hh = previewHeight >> 1;
               synchronized (lockSurface)
               {  // set up texture units for shader conversion from YV12 format (http://www.fourcc.org/yuv.php#YV12)
                  // assets/shaders/camera/texture/preview/fragment.glsl
                  glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
                  glActiveTexture(GL_TEXTURE0);
                  glUniform1i(yComponentUniform, 0);
                  glBindTexture(texTarget, yComponent);
                  previewByteBuffer.rewind();
                  glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, previewWidth, previewHeight, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE,
                               previewByteBuffer);

                  glActiveTexture(GL_TEXTURE1);
                  glUniform1i(uComponentUniform, 1);
                  glBindTexture(texTarget, uComponent);
                  previewByteBuffer.rewind().position(uIndex);
                  glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, hw, hh, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE,
                               previewByteBuffer.slice());

                  glActiveTexture(GL_TEXTURE2);
                  glUniform1i(vComponentUniform, 2);
                  glBindTexture(texTarget, vComponent);
                  previewByteBuffer.rewind().position(vIndex);
                  glTexImage2D(GL_TEXTURE_2D, 0, GL_LUMINANCE, hw, hh, 0, GL_LUMINANCE, GL_UNSIGNED_BYTE,
                               previewByteBuffer.slice());
               }
               isPreviewed = true;
            }
         }
         if (GLHelper.isGLError(glerrbuf))
            throw new RuntimeException(glerrbuf.toString());

         glUniformMatrix4fv(previewMVPLocation, 1, false, previewMVP, 0);
//         if (DIRECT_TO_SURFACE)
//            glUniformMatrix4fv(previewSTMLocation, 1, false, previewSTM, 0);
         previewPlaneVertices.rewind();
         glVertexAttribPointer(previewShaderGlsl.vertexAttr, 3, GL_FLOAT, false, 0,
                               previewPlaneVertices.asFloatBuffer());
         glEnableVertexAttribArray(previewShaderGlsl.vertexAttr);
         previewPlaneTextures.rewind();
         glVertexAttribPointer(previewShaderGlsl.textureAttr, 2, GL_FLOAT, false, 0,
                               previewPlaneTextures.asFloatBuffer());
         glEnableVertexAttribArray(previewShaderGlsl.textureAttr);
         previewPlaneFaces.rewind();
         glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, previewPlaneFaces.asShortBuffer());
//         glDrawArrays(GL10.GL_TRIANGLE_STRIP, 0, 4);
         glBindTexture(texTarget, 0);

         if (GLHelper.isGLError(glerrbuf))
            throw new RuntimeException(glerrbuf.toString());

         if (isRecording)
         {
            Matrix.setIdentityM(scaleM, 0);
            Matrix.scaleM(scaleM, 0, xScaleArrow, yScaleArrow, 1);
            Matrix.setIdentityM(translateM, 0);
            Matrix.translateM(translateM, 0, xTranslateArrow, yTranslateArrow, 0);
            Matrix.setRotateM(rotateM, 0, arrowRotation, 0, 0, 1);
            //         Matrix.multiplyMM(arrowModelView, 0, lookM, 0, scaleM, 0);
            Matrix.multiplyMM(arrowModelView, 0, translateM, 0, scaleM, 0);
            Matrix.multiplyMM(modelM, 0, arrowModelView, 0, rotateM, 0);
            Matrix.multiplyMM(arrowModelView, 0, viewM, 0, modelM, 0);

            //         Matrix.multiplyMM(arrowModelView, 0, modelM, 0, rotateM, 0);
            Matrix.multiplyMM(arrowMVP, 0, projectionM, 0, arrowModelView, 0);
            glUseProgram(arrowShaderGlsl.shaderProgram);
            glUniformMatrix4fv(arrowMVPLocation, 1, false, arrowMVP, 0);
            glUniform3f(arrowColorUniform, arrowColor[0], arrowColor[1], arrowColor[2]);
            arrowVerticesBuffer.rewind();
            glVertexAttribPointer(arrowShaderGlsl.vertexAttr, 3, GL_FLOAT, false, 0, arrowVerticesBuffer);
            glEnableVertexAttribArray(arrowShaderGlsl.vertexAttr);

            //         previewPlaneFaces.rewind();
            //         glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, previewPlaneFaces.asShortBuffer());
            arrowFacesBuffer.rewind();
            glDrawElements(GL_TRIANGLES, 9, GL_UNSIGNED_SHORT, arrowFacesBuffer.asShortBuffer());
         }
         if (GLHelper.isGLError(glerrbuf))
            throw new RuntimeException(glerrbuf.toString());
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         throw new RuntimeException(e);
      }
      finally
      {
         if ((previewer.camera != null) && (isPreviewed))
            previewer.camera.addCallbackBuffer(cameraBuffer);
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
         toast("Vertex shader not found in assets/" + VERTEX_SHADER);
         throw new RuntimeException("Error reading vertex shader source from assets/" + VERTEX_SHADER);
      }
      final StringBuilder errbuf = new StringBuilder();
      int vertexShader = GLHelper.compileShader(GL_VERTEX_SHADER, src, errbuf);
      if (vertexShader < 0)
      {
         Log.e(TAG, "Vertex shader compile error: " + errbuf.toString());
         toast(errbuf.toString());
         lastError = errbuf.toString();
         throw new RuntimeException("Error compiling vertex shader source from assets/" + VERTEX_SHADER);
      }

      shaderFile = "shaders/" + assetDir + ((assetDir.endsWith("/")) ? "" : "/") + FRAGMENT_SHADER;
      src = readAssetFile(shaderFile, null);
      if (src == null)
      {
         Log.e(TAG, "Fragment shader not found in assets/" + shaderFile);
         toast("Fragment shader not found in assets/" + FRAGMENT_SHADER);
         throw new RuntimeException("Error reading vertex shader source from assets/" + FRAGMENT_SHADER);
      }
      int fragmentShader = GLHelper.compileShader(GL_FRAGMENT_SHADER, src, errbuf);
      if (fragmentShader < 0)
      {
         Log.e(TAG, "Error compiling fragment shader " + FRAGMENT_SHADER + ": " + errbuf.toString());
         toast(errbuf.toString());
         lastError = errbuf.toString();
         throw new RuntimeException("Error compiling fragment shader source from assets/" + FRAGMENT_SHADER +
                                    " Error: " + errbuf.toString());
      }

      int program = GLHelper.createShaderProgram(errbuf, vertexShader, fragmentShader);
      if (program < 0)
      {
         toast(errbuf.toString());
         lastError = errbuf.toString();
         return null;
      }
      GLSLAttributes shaderAttr = new GLSLAttributes(program);

      if (vertexAttrName != null)
      {
         glBindAttribLocation(shaderAttr.shaderProgram, shaderAttr.vertexAttr(), vertexAttrName);
         if (GLHelper.isGLError(errbuf))
         {
            Log.e(TAG, "Error binding vertex attribute " + vertexAttrName + " (" + errbuf.toString() + ")");
            toast(errbuf.toString());
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
            Log.e(TAG, "Error binding texture attribute " + textureAttrName + " (" + errbuf.toString() + ")");
            toast(errbuf.toString());
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
            Log.e(TAG, "Error binding normal attribute " + colorAttrName + " (" + errbuf.toString() + ")");
            toast(errbuf.toString());
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
            Log.e(TAG, "Error binding normal attribute " + normalAttrName + " (" + errbuf.toString() + ")");
            toast(errbuf.toString());
            lastError = errbuf.toString();
            return null;
         }
         glEnableVertexAttribArray(shaderAttr.normalAttr());
      }
      if (! GLHelper.linkShaderProgram(shaderAttr.shaderProgram, errbuf))
      {
         Log.e(TAG, "Error linking shader program");
         toast(errbuf.toString());
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
         while ((line = br.readLine()) != null)
            sb.append(line).append("\n");
         return sb.toString();
      }
      catch (Exception e)
      {
         return def;
      }
      finally
      {  // @formatter:off
         if (br != null)
            try { br.close(); } catch (Exception _e) { }
         if (is != null)
            try { is.close(); } catch (Exception _e) { }
      } // @formatter:on
   }

   public void requestRender() { view.requestRender(); }

   File recordDir, recordFramesFile;
   String recordFileName;
   final Map<String, Object> recordHeader = new HashMap<String, Object>();

   public boolean startRecording(File dir, String name, float increment, RecordMode mode)
   //-------------------------------------------------------------------------------------
   {
      if (isRecording) return false;
      if (previewer == null)
         initPreviewer(false);
      if (previewer == null)
         return false;
      if (! dir.isDirectory())
         dir.mkdirs();
      if ((! dir.isDirectory()) || (! dir.canWrite()))
      {
         Log.e(TAG, dir.getAbsolutePath() + " not a writable directory");
         return false;
      }
      stopRecordingThread();

      recordDir = dir;
      if ((name == null) || (name.trim().isEmpty()))
         recordFileName = null;
      else
         recordFileName = name;
      recordLocation = new Location(LocationManager.GPS_PROVIDER);
      recordLocation.setTime(System.currentTimeMillis());
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
         recordLocation.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
      else
      {
         // Kludge because some location APIs requires elapsedtime in nanos but call is not available in all Android versions.
         try
         {
            Method makeCompleteMethod = null;
            makeCompleteMethod = Location.class.getMethod("makeComplete");
            if (makeCompleteMethod != null)
               makeCompleteMethod.invoke(recordLocation);
         }
         catch (Exception e)
         {
            e.printStackTrace();
         }
      }

      recordLocation.setLatitude(0);
      recordLocation.setLongitude(0);
      recordLocation.setAltitude(0);
      recordLocation.setAccuracy(1.0f);
      initLocationSensor();

      isRecording = true;
      recordingCondVar = new ConditionVariable(false);
      this.recordingMode = mode;
      recordingThread = RecordingThread.createRecordingThread(mode, this, increment, recordingCondVar,
                                                              previewer.getFrameAvailCondVar());
      previewer.bufferOn();
      recordingThread.executeOnExecutor(recordingPool);
      return true;
   }

   protected void resumeRecording(Bundle B)
   //---------------------------------
   {
      recordingCondVar = new ConditionVariable(false);
      previewer.bufferOn();
      arrowRotation = 0;
      arrowColor = GREEN;
      recordingThread = RecordingThread.createRecordingThread(recordingMode, this);
      recordingThread.restore(B);
      recordingThread.setBearingBuffer(bearingBuffer).setBearingCondVar(recordingCondVar).
                      setFrameCondVar(previewer.getFrameAvailCondVar()).setPreviewer(previewer);
      recordingPool = createSingleThreadPool("Recording");
      recordingThread.executeOnExecutor(recordingPool);
   }

   private void stopRecordingThread()
   //--------------------------------
   {
      if ( (recordingThread != null) && (recordingThread.getStatus() == AsyncTask.Status.RUNNING) )
      {
         isRecording = false;
         recordingCondVar.open();
         try { Thread.sleep(100); } catch (Exception _e) {}
         recordingThread.cancel(true);
         try { Thread.sleep(50); } catch (Exception _e) {}
         if (recordingThread.getStatus() == AsyncTask.Status.RUNNING)
         {
            recordingPool.shutdownNow();
            recordingPool = createSingleThreadPool("Recording");
         }
      }
   }

   private StopRecordingThread stopRecordingThread = null;
   private boolean isStoppingRecording = false;

   public boolean stopRecording(final boolean isCancelled)
   //-----------------------------------------------------
   {
      if ( (recordingThread == null) || (isStoppingRecording) ) return false;
      isStoppingRecording = true;
      stopRecordingThread();
      if ( (isCancelled) || (recordFramesFile == null) || (! recordFramesFile.exists()) && (recordFramesFile.length() == 0) )
      {
         if (recordFramesFile != null)
            recordFramesFile.delete();
         activity.stoppedRecording(null, null);
         return true;
      }
      stopRecordingPool = createSingleThreadPool("Stop Recording");
      stopRecordingThread = new StopRecordingThread();
      stopRecordingThread.executeOnExecutor(stopRecordingPool);
      return true;
   }

   RandomAccessFile NV21toRGBInputRAF = null, NV21toRGBOutputRAF = null;
   float lastSaveBearing = 0;

   final static int REMAP_X = SensorManager.AXIS_X,  REMAP_Y = SensorManager.AXIS_Z;
   final static float MAX_BEARING_DELTA = 3;

   public boolean initOrientationSensor(ORIENTATION_PROVIDER orientationProviderType)
   //-----------------------------------------------------------------------------
   {
      if ( (orientationProvider != null) && (orientationProvider.isStarted()) )
         orientationProvider.stop();
      this.orientationProviderType = orientationProviderType;
      SensorManager sensorManager = (SensorManager) activity.getSystemService(Activity.SENSOR_SERVICE);
      if (orientationProviderType == ORIENTATION_PROVIDER.DEFAULT)
      {
         if (OrientationProvider.supportsOrientationProvider(activity, ORIENTATION_PROVIDER.STABLE_FUSED_GYROSCOPE_ROTATION_VECTOR))
            orientationProviderType = ORIENTATION_PROVIDER.STABLE_FUSED_GYROSCOPE_ROTATION_VECTOR;
         else if (OrientationProvider.supportsOrientationProvider(activity, ORIENTATION_PROVIDER.ROTATION_VECTOR))
            orientationProviderType = ORIENTATION_PROVIDER.ROTATION_VECTOR;
         else if (OrientationProvider.supportsOrientationProvider(activity, ORIENTATION_PROVIDER.FUSED_GYRO_ACCEL_MAGNETIC))
            orientationProviderType = ORIENTATION_PROVIDER.FUSED_GYRO_ACCEL_MAGNETIC;
         else if (OrientationProvider.supportsOrientationProvider(activity, ORIENTATION_PROVIDER.ACCELLO_MAGNETIC))
            orientationProviderType = ORIENTATION_PROVIDER.ACCELLO_MAGNETIC;
         else
            return false;
      }
      switch (orientationProviderType)
      {
         case STABLE_FUSED_GYROSCOPE_ROTATION_VECTOR:
            orientationProvider = new StableFusedGyroscopeRotationVector(sensorManager);
            break;
         case FAST_FUSED_GYROSCOPE_ROTATION_VECTOR:
            orientationProvider = new FastFusedGyroscopeRotationVector(sensorManager);
            break;
         case ROTATION_VECTOR:
            orientationProvider = new RotationVectorProvider(sensorManager);
            break;
         case FUSED_GYRO_ACCEL_MAGNETIC:
            orientationProvider = new FusedGyroAccelMagnetic(sensorManager);
            break;
         case ACCELLO_MAGNETIC:
            orientationProvider = new AccelerometerCompassProvider(sensorManager);
            break;
      }
      orientationProvider.setOrientationListener(new OrientationListenable()
      //--------------------------------------------------------------------
      {
         final float[] RM = new float[16];
         boolean isSettling = false;
         int settleCount = 0;
         ProgressParam param = new ProgressParam();

         @Override
         public void onOrientationListenerUpdate(float[] R, Quaternion Q, long timestamp)
         //-----------------------------------------------------------------------------
         {
            lastBearing = currentBearing;
            lastBearingTimestamp = currentBearingTimestamp;
            SensorManager.remapCoordinateSystem(R, REMAP_X, REMAP_Y, RM);
            currentBearing = (float) Math.toDegrees(Math.atan2(RM[1], RM[5]));
            if (currentBearing < 0)
               currentBearing += 360;
            bearingDelta = currentBearing - lastBearing;
            float absDelta = Math.abs(bearingDelta);
            currentBearingTimestamp = timestamp;
            if (isSettling)
            {
               settleCount--;
               if (settleCount <= 0)
                  isSettling = false;
               else
               {
                  param.set(currentBearing, recordingThread.getNextBearing(), arrowColor);
                  activity.onStatusUpdate(param);
                  return;
               }
            }
            if ( (! isRecording) && (absDelta >= 0.1f) )
            {
               param.set(currentBearing, - 1, null);
               activity.onStatusUpdate(param);
            }
            else if (isRecording)
            {
               if (absDelta >= MAX_BEARING_DELTA)
               {
                  isSettling = true;
                  settleCount = 3;
                  return;
               }
               if (absDelta >= 0.01f)
               {
                  bearingBuffer.push(timestamp, currentBearing);
                  recordingCondVar.open();
               }
            }
         }
      });
      if (! orientationProvider.start())
         return false;
      this.orientationProviderType = orientationProviderType;
      return true;
/*
      Sensor rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
      rotationListener = new SensorEventListener()
      //==========================================
      {
         public float[] R = new float[16], RM = new float[16];

         @Override
         public void onSensorChanged(SensorEvent event)
         //--------------------------------------------
         {
            SensorManager.getRotationMatrixFromVector(R, event.values);
            int worldX = SensorManager.AXIS_X,  worldY = SensorManager.AXIS_Z;
            SensorManager.remapCoordinateSystem(R, worldX, worldY, RM);
            float bearing = lastBearing = (float) Math.toDegrees(QuickFloat.atan2(RM[1], RM[5]));
            if (bearing < 0)
               bearing += 360;
//               Log.d(TAG, "Record Bearing " + bearing);
            try { bearingChanged(bearing); } catch (Exception e) {}
         }

         @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
      };
      if (! sensorManager.registerListener(rotationListener, rotationSensor, SensorManager.SENSOR_DELAY_GAME))
      {
         toast("ERROR: Could not initialize, rotation sensor");
         activity.finish();
      }
      */
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

      public GLSLAttributes(int program)
      {
         shaderProgram = program;
      }

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

   final static private boolean USE_RGB565_RS = false;
   class StopRecordingThread extends AsyncTask<Void, ProgressParam, Boolean>
   //=========================================================================
   {
      File recordHeaderFile = null;

      @Override
      protected Boolean doInBackground(Void... params)
      //----------------------------------------------
      {
         Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
         final float increment = recordingThread.recordingIncrement;
         isRecording = false;
         isStopRecording = true;
         previewer.bufferOff();
         try
         {

            if ((recordFileName == null) || (recordFileName.trim().isEmpty()))
            {
               String name = recordFramesFile.getName();
               int p = name.indexOf('.');
               if (p > 0)
                  recordFileName = name.substring(0, p);
               else
                  recordFileName = name;
            }
            File recordFramesFileOld = recordFramesFile;
            File recordFramesFileKeep = recordFramesFile;
            recordFramesFile = new File(recordDir, recordFileName + ".frames");
            if (! recordFramesFileOld.getName().endsWith(".part"))
            {
               File f = new File(recordDir, recordFileName + ".frames.tmp");
               recordFramesFileOld.renameTo(f);
               recordFramesFileKeep = recordFramesFileOld = f;
            }
            StringBuilder errbuf = new StringBuilder();
            if (recordFileFormat == RecordFileFormat.NV21)
               recordFramesFileOld.renameTo(recordFramesFile);
            else
            {
               if (! YUVtoRGB(recordFramesFileOld, recordFramesFile, increment, errbuf))
               {
                  if (mustStopNow)
                  {
                     recordFramesFileOld.renameTo(recordFramesFileKeep);
                     recordFramesFile.delete();
                  } else
                  {
                     toast("Error converting NV21 to RGB(A): " + errbuf.toString());
                     recordFramesFile.delete();
                     recordFramesFileOld.delete();
                  }
                  return false;
               }
               else
                  recordFramesFileOld.delete();
            }

            if (recordLocation != null)
               recordHeader.put("Location", String.format("%12.7f,%12.7f,%12.7f", recordLocation.getLatitude(),
                                                          recordLocation.getLongitude(), recordLocation.getAltitude()));
            if (recordFileFormat == null)
               recordFileFormat = RecordFileFormat.RGBA;
            switch (recordFileFormat)
            {
               case RGB:
                  recordHeader.put("BufferSize", Integer.toString(rgbBufferSize));
                  break;
               case RGBA:
                  recordHeader.put("BufferSize", Integer.toString(rgbaBufferSize));
                  break;
               case RGB565:
                  recordHeader.put("BufferSize", Integer.toString(rgb565BufferSize));
                  break;
               case NV21:
                  recordHeader.put("BufferSize", Integer.toString(nv21BufferSize));
                  break;
            }
            recordHeader.put("Increment", String.format("%6.2f", increment));
            recordHeader.put("PreviewWidth", Integer.toString(previewWidth));
            recordHeader.put("PreviewHeight", Integer.toString(previewHeight));
            recordHeader.put("FocalLength", Float.toString(previewer.focalLen));
            recordHeader.put("fovx", Float.toString(previewer.fovx));
            recordHeader.put("fovy", Float.toString(previewer.fovy));
            recordHeader.put("FileFormat", recordFileFormat.name());
            recordHeader.put("OrientationProvider",
                             (orientationProviderType == null) ? ORIENTATION_PROVIDER.DEFAULT.name()
                                                               : orientationProviderType.name());
            recordHeader.put("FramesFile", recordFramesFile.getName());

            recordHeaderFile = new File(recordDir, recordFileName + ".head");
            PrintWriter headerWriter = null;
            try
            {
               headerWriter = new PrintWriter(recordHeaderFile);
               Set<Map.Entry<String, Object>> headerSet = recordHeader.entrySet();
               for (Map.Entry<String, Object> entry : headerSet)
                  headerWriter.printf(Locale.US, "%s=%s\n", entry.getKey(), entry.getValue());
            }
            catch (Exception _e)
            {
               Log.e(TAG, "Error writing recording header file " + recordHeaderFile.getAbsolutePath(), _e);
               toast("Error writing recording header file " + recordHeaderFile.getAbsolutePath() + ": " +
                           _e.getMessage());
               return false;
            }
            finally
            {
               if (headerWriter != null)
                  try { headerWriter.close(); } catch (Exception _e) { }
            }
            isStopRecording = false;

         }
         catch (Exception e)
         {
            Log.e(TAG, "Error saving recording", e);
            toast("Error saving recording: " + e.getMessage());
         }
         finally
         {
            arrowColor = GREEN;
            arrowRotation = 0;
            recordFramesFile = null;
            recordFileName = null;
            arrowRotation = 0;
            isRecording = isStoppingRecording = false;
         }
         return true;
      }

      @Override
      protected void onProgressUpdate(ProgressParam... values)
      {
         activity.onStatusUpdate(values[0]);
      }

      @Override
      protected void onPostExecute(Boolean aBoolean)
      {
         activity.stoppedRecording(recordHeaderFile, recordFramesFile);
      }

      @Override
      protected void onCancelled(Boolean B)
      {
         activity.stoppedRecording(recordHeaderFile, recordFramesFile);
      }

      public boolean YUVtoRGB(File src, File dest, float recordingIncrement, StringBuilder errbuf)
      //-------------------------------------------------------------------------------------------
      {
         RenderScript rsYUVtoRGBA = null, rsRGBAtoRGB565 = null;
         ScriptIntrinsicYuvToRGB YUVToRGB = null;
         ScriptC_RGBAtoRGB565 RGBAtoRGB565script = null;
         Type.Builder rstypRGBA = null;
         Type.Builder rstypRGB565 =  null;
         Allocation rgbaIn = null, rgb565Out = null;

         byte[] frameBuffer = new byte[nv21BufferSize], rgbaBuffer = null, rgbBuffer = null, rgb565Buffer = null;
//      short[] rgb565Buffer = null;
         //ByteBuffer rgb565ByteBuffer = null;
         ProgressParam params = new ProgressParam();
         try
         {
            rsYUVtoRGBA = RenderScript.create(activity);
            Type.Builder yuvType = new Type.Builder(rsYUVtoRGBA, Element.U8(rsYUVtoRGBA)).setX(previewWidth).
                  setY(previewHeight).setMipmaps(false);
            if (DIRECT_TO_SURFACE)
               yuvType.setYuvFormat(ImageFormat.NV21);
            else
               yuvType.setYuvFormat(ImageFormat.YV12);
            Allocation ain = Allocation.createTyped(rsYUVtoRGBA, yuvType.create(), Allocation.USAGE_SCRIPT);

            Type.Builder rgbType = null;
            switch (recordFileFormat)
            {
               case RGBA:
                  YUVToRGB = ScriptIntrinsicYuvToRGB.create(rsYUVtoRGBA, Element.U8_4(rsYUVtoRGBA));
                  rgbType = new Type.Builder(rsYUVtoRGBA, Element.RGBA_8888(rsYUVtoRGBA));
                  rgbaBuffer = new byte[rgbaBufferSize];
                  break;
               case RGB:
                  YUVToRGB = ScriptIntrinsicYuvToRGB.create(rsYUVtoRGBA, Element.U8_4(rsYUVtoRGBA));
                  rgbType = new Type.Builder(rsYUVtoRGBA, Element.RGBA_8888(rsYUVtoRGBA));
//               Below does not work as Renderscript only works with evenly aligned data. The below does not give
//               an error but generates 4 bytes per pixel with the 4th byte being 255 (same as Element.U8_4)
//               YUVToRGB = ScriptIntrinsicYuvToRGB.create(rsYUVtoRGBA, Element.U8_3(rsYUVtoRGBA));
//               rgbType = new Type.Builder(rsYUVtoRGBA, Element.RGB_888(rsYUVtoRGBA));
                  rgbaBuffer = new byte[rgbaBufferSize];
                  rgbBuffer = new byte[rgbBufferSize];
                  break;
               case RGB565:
//               Should work as its aligned on 2 bytes but gives invalid format exception
//               YUVToRGB = ScriptIntrinsicYuvToRGB.create(rsYUVtoRGBA, Element.U8_2(rsYUVtoRGBA));
//               rgbType = new Type.Builder(rsYUVtoRGBA, Element.RGB_565(rsYUVtoRGBA));
                  YUVToRGB = ScriptIntrinsicYuvToRGB.create(rsYUVtoRGBA, Element.U8_4(rsYUVtoRGBA));
                  rgbType = new Type.Builder(rsYUVtoRGBA, Element.RGBA_8888(rsYUVtoRGBA));
                  rgbaBuffer = new byte[rgbaBufferSize];
                  rgb565Buffer = new byte[rgb565BufferSize];

                  if (USE_RGB565_RS)
                  {
                     rsRGBAtoRGB565 = RenderScript.create(activity);
                     RGBAtoRGB565script = new ScriptC_RGBAtoRGB565(rsRGBAtoRGB565);
                     rstypRGBA = new Type.Builder(rsRGBAtoRGB565, Element.RGBA_8888(rsRGBAtoRGB565)).setX(previewWidth).
                           setY(previewHeight).setMipmaps(false);
//                rstypRGB565 = new Type.Builder(rsRGBAtoRGB565, Element.U16(rsRGBAtoRGB565)).setX(previewWidth).
//                              setY(previewHeight).setMipmaps(false);
                     rstypRGB565 = new Type.Builder(rsRGBAtoRGB565, Element.U8_2(rsRGBAtoRGB565)).setX(previewWidth).
                           setY(previewHeight).setMipmaps(false);
                     rgbaIn = Allocation.createTyped(rsRGBAtoRGB565, rstypRGBA.create(), Allocation.USAGE_SCRIPT);
                     rgb565Out = Allocation.createTyped(rsRGBAtoRGB565, rstypRGB565.create(), Allocation.USAGE_SCRIPT);
                  }
                  break;
            }
            rgbType.setX(previewWidth).setY(previewHeight).setMipmaps(false);
            Allocation aOut = Allocation.createTyped(rsYUVtoRGBA, rgbType.create(), Allocation.USAGE_SCRIPT);

            NV21toRGBInputRAF = new RandomAccessFile(src, "r");
            NV21toRGBOutputRAF = new RandomAccessFile(dest, "rw");
            float bearing = lastSaveBearing;
            long frameCount = 0;
            while ( (bearing < 360) && (! mustStopNow) )
            {
               lastSaveBearing = bearing;
               float offset = (float) (Math.floor(bearing / recordingIncrement) * recordingIncrement);
               int fileOffset = (int) (Math.floor(offset / recordingIncrement) * nv21BufferSize);
               try
               {
                  NV21toRGBInputRAF.seek(fileOffset);
                  NV21toRGBInputRAF.readFully(frameBuffer);
               }
               catch (EOFException _e)
               {
                  Arrays.fill(frameBuffer, (byte) 0);
                  Log.e(TAG, "Offset out of range: " + fileOffset + ", bearing was " + bearing, _e);
               }
               ain.copyFrom(frameBuffer);
               YUVToRGB.setInput(ain);
               YUVToRGB.forEach(aOut);
               aOut.copyTo(rgbaBuffer);
               if (mustStopNow)
                  return false;
               switch (recordFileFormat)
               {
                  case RGBA:
                     NV21toRGBOutputRAF.seek(frameCount++ * rgbaBufferSize);
                     NV21toRGBOutputRAF.write(rgbaBuffer);
                     break;
                  case RGB:
//                  aOut.copyTo(rgbBuffer);
                     RGBAtoRGB.RGBAtoRGB(rgbaBuffer, rgbBuffer);
                     NV21toRGBOutputRAF.seek(frameCount++ * rgbBufferSize);
                     NV21toRGBOutputRAF.write(rgbBuffer);
                     break;
                  case RGB565:
////                  aOut.copyTo(rgb565Buffer);
                     if (USE_RGB565_RS)
                     {
                        rgbaIn.copyFrom(rgbaBuffer);
                        RGBAtoRGB565script.forEach_root(rgbaIn, rgb565Out);
                        rgb565Out.copyTo(rgb565Buffer);
                     }
                     else
                        RGBAtoRGB.RGBAtoRGB565(rgbaBuffer, rgb565Buffer);
                     NV21toRGBOutputRAF.seek(frameCount++ * rgb565BufferSize);
                     NV21toRGBOutputRAF.write(rgb565Buffer);
                     break;
               }
               bearing = (float) (Math.rint((bearing + recordingIncrement) * 10.0f) / 10.0);
               float progress = (bearing/360.0f) * 100f;
               params.setStatus(String.format(Locale.US, "Converting to %s: %.2f%%", recordFileFormat.name(), progress),
                                (int) progress, true, Toast.LENGTH_SHORT);
               publishProgress(params);
            }
            if (mustStopNow)
               return false;
            return true;
         }
         catch (Exception e)
         {
            if (errbuf != null)
               errbuf.append(e.getMessage());
            Log.e(TAG, "YUVtoRGB", e);
            params.setStatus(String.format(Locale.US, "Error converting to %s: %s", recordFileFormat.name(), e.getMessage()),
                             100, true, Toast.LENGTH_LONG);
            publishProgress(params);
            return false;
         }
         finally
         {
            if (NV21toRGBInputRAF != null)
               try { NV21toRGBInputRAF.close(); NV21toRGBInputRAF = null; } catch (Exception _e) { Log.e(TAG, "", _e); }
            if (NV21toRGBOutputRAF != null)
               try { NV21toRGBOutputRAF.close(); NV21toRGBOutputRAF = null; } catch (Exception _e) { Log.e(TAG, "", _e); }
         }
      }
   }

   //   public void toRGB565(byte[] yuvs, int screenWidth, int screenHeight, byte[] rgbs)
//   //-------------------------------------------------------------------
//   {
//      // the end of the luminance data
//      final int lumEnd = screenWidth * screenHeight;
//      // points to the next luminance value pair
//      int lumPtr = 0;
//      // points to the next chromiance value pair
//      int chrPtr = lumEnd;
//      // points to the next byte output pair of RGB565 value
//      int outPtr = 0;
//      // the end of the current luminance scanline
//      int lineEnd = screenWidth;
//
//      while (true)
//      {
//         // skip back to the start of the chromiance values when necessary
//         if (lumPtr == lineEnd)
//         {
//            if (lumPtr == lumEnd)
//               break; // we've reached the end
//            // division here is a bit expensive, but's only done once per
//            // scanline
//            chrPtr = lumEnd + ((lumPtr >> 1) / screenWidth) * screenWidth;
//            lineEnd += screenWidth;
//         }
//
//         // read the luminance and chromiance values
//         final int Y1 = yuvs[lumPtr++] & 0xff;
//         final int Y2 = yuvs[lumPtr++] & 0xff;
//         final int Cr = (yuvs[chrPtr++] & 0xff) - 128;
//         final int Cb = (yuvs[chrPtr++] & 0xff) - 128;
//         int R, G, B;
//
//         // generate first RGB components
//         B = Y1 + ((454 * Cb) >> 8);
//         if (B < 0)
//            B = 0;
//         else if (B > 255)
//            B = 255;
//         G = Y1 - ((88 * Cb + 183 * Cr) >> 8);
//         if (G < 0)
//            G = 0;
//         else if (G > 255)
//            G = 255;
//         R = Y1 + ((359 * Cr) >> 8);
//         if (R < 0)
//            R = 0;
//         else if (R > 255)
//            R = 255;
//         // NOTE: this assume little-endian encoding
//         rgbs[outPtr++] = (byte) (((G & 0x3c) << 3) | (B >> 3));
//         rgbs[outPtr++] = (byte) ((R & 0xf8) | (G >> 5));
//
//         // generate second RGB components
//         B = Y2 + ((454 * Cb) >> 8);
//         if (B < 0)
//            B = 0;
//         else if (B > 255)
//            B = 255;
//         G = Y2 - ((88 * Cb + 183 * Cr) >> 8);
//         if (G < 0)
//            G = 0;
//         else if (G > 255)
//            G = 255;
//         R = Y2 + ((359 * Cr) >> 8);
//         if (R < 0)
//            R = 0;
//         else if (R > 255)
//            R = 255;
//         // NOTE: this assume little-endian encoding
//         rgbs[outPtr++] = (byte) (((G & 0x3c) << 3) | (B >> 3));
//         rgbs[outPtr++] = (byte) ((R & 0xf8) | (G >> 5));
//      }
//   }

//   private void setDisplayOrientation()
//   //----------------------------------
//   {
//      android.hardware.Camera.CameraInfo info =
//            new android.hardware.Camera.CameraInfo();
//      android.hardware.Camera.getCameraInfo(cameraId, info);
//      int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
//      int degrees = 0;
//      switch (rotation) {
//         case Surface.ROTATION_0: degrees = 0; break;
//         case Surface.ROTATION_90: degrees = 90; break;
//         case Surface.ROTATION_180: degrees = 180; break;
//         case Surface.ROTATION_270: degrees = 270; break;
//      }
//
//      int result;
//      if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)
//      {
//         result = (info.orientation + degrees) % 360;
//         result = (360 - result) % 360;  // compensate the mirror
//      }
//      else // back-facing
//      {
//         result = (info.orientation - degrees + 360) % 360;
////         result = (360 - result) % 360;
//      }
//
//      camera.setDisplayOrientation(result);
//   }
}
