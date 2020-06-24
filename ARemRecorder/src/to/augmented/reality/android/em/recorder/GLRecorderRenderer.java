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

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.LocationManager;
import android.opengl.GLES11Ext;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Debug;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import to.augmented.reality.android.common.gl.GLHelper;
import to.augmented.reality.android.common.sensor.orientation.AccelerometerCompassProvider;
import to.augmented.reality.android.common.sensor.orientation.FastFusedGyroscopeRotationVector;
import to.augmented.reality.android.common.sensor.orientation.FusedGyroAccelMagnetic;
import to.augmented.reality.android.common.sensor.orientation.OrientationProvider;
import to.augmented.reality.android.common.sensor.orientation.RotationVectorProvider;
import to.augmented.reality.android.common.sensor.orientation.StableFusedGyroscopeRotationVector;

import static android.opengl.GLES20.*;
import static to.augmented.reality.android.common.sensor.orientation.OrientationProvider.ORIENTATION_PROVIDER;

public class GLRecorderRenderer implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener
//========================================================================================================
{
   private static final String TAG = GLRecorderRenderer.class.getSimpleName();
   private static final String VERTEX_SHADER = "vertex.glsl";
   private static final String FRAGMENT_SHADER = "fragment.glsl";
   static final int SIZEOF_FLOAT = Float.SIZE / 8;
   static final int SIZEOF_SHORT = Short.SIZE / 8;
   final static int MAX_BUFFERED_FRAMES = 15;
   final private static int GL_TEXTURE_EXTERNAL_OES = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
   final static boolean DEBUG_TRACE = false;

   RecorderActivity activity;
   private ARSurfaceView view;
   private boolean isInitialised = false, isUseCamera2Api = true;

   public boolean isInitialised() { return isInitialised; }

   private int uIndex = -1, vIndex;

   private int displayWidth, displayHeight;
   Surface displaySurface;

   private ORIENTATION_PROVIDER orientationProviderType = ORIENTATION_PROVIDER.DEFAULT;
   OrientationProvider orientationProvider = null;
   OrientationHandler orientationListener;
   volatile float currentBearing = 0, lastBearing = -1;
   long currentBearingTimestamp = -1L, lastBearingTimestamp = -1L;
   volatile boolean isRecording = false, isStopRecording = false, mustStopNow = false;
   SurfaceTexture previewSurfaceTexture = null;
   private String cameraID = null;
   boolean isLegacyCamera = true;

   private NativeFrameBuffer frameBuffer;
   public NativeFrameBuffer getFrameBuffer() { return frameBuffer; }

   private ConditionVariable frameAvailCondVar = new ConditionVariable(false);
   public ConditionVariable getFrameAvailCondVar() { return frameAvailCondVar; }

   final Object lockSurface = new Object();
   volatile boolean isUpdateSurface = false, isUpdateTexture = false;

   int previewTexture = -1;
   private int yComponent = -1;
   private int uComponent = -1;
   private int vComponent = -1;

   public int getPreviewTexture() { return previewTexture; }

   protected int rgbaBufferSize = 0;
   private int rgbBufferSize = 0;
   int nv21BufferSize = 0, yuvBufferSize = 0, rawBufferSize = 0;
   private int rgb565BufferSize = 0;

   private float lastSaveBearing = 0;
   private int previewMVPLocation = -1, cameraTextureUniform = -1, yComponentUniform = -1, uComponentUniform = -1,
         vComponentUniform; //, previewSTMLocation = -1
   private float[] previewMVP = new float[16]; //, previewSTM = new float[16];

   private int recordMVPLocation = -1, recordColorUniform = -1;
   private float[] recordMVP = new float[16];

   int screenWidth = -1;

   public int getScreenWidth() { return screenWidth; }

   int screenHeight = -1;

   public int getScreenHeight()
   {
      return screenHeight;
   }

   final static private float ARROW_WIDTH = 5;
   final static private float ARROW_HEIGHT = 1;

   static final float[] arrowVertices =
         {
               4, 0.2f, -10.0f, // bottom-right
               0, 0.2f, -10.0f, // bottom-left
               4, 0.8f, -10.0f, // top-right
               0, 0.8f, -10.0f, // top-left

               // Arrow head
               4, 0, -10,   //   4
               5, 0.5f, -10,   //   5
               4, 1, -10    //   6
         };

   static final short[] arrowFaces = {2, 3, 1, 0, 2, 1, 5, 6, 4};

   final static private float PAUSE_WIDTH = 3;
   final static private float PAUSE_HEIGHT = 1;

   static final float[] pauseVertices =
         {
               0.2f, 4.0f, -10.0f,  // 0
               0.2f, 0.0f, -10.0f,  // 1
               0.8f, 4.0f, -10.0f,  // 2
               0.8f, 0.0f, -10.0f,  // 3

               1.2f, 4.0f, -10.0f,  // 4
               1.2f, 0.0f, -10.0f,  // 5
               1.8f, 4.0f, -10.0f,  // 6
               1.8f, 0.0f, -10.0f,  // 7
         };

   static final short[] pauseFaces = {2, 3, 1, 0, 2, 1,
                                      6, 7, 5, 4, 6, 5};

   static final float[] recordVertices =
         {
//               -1.0f,  1.0f, -10.0f,  // 0, Top Left
//               -1.0f, -1.0f, -10.0f,  // 1, Bottom Left
//                1.0f, -1.0f, -10.0f,  // 2, Bottom Right
//                1.0f,  1.0f, -10.0f  // 3, Top Right

               0.0f,  1.0f, -10.0f,  // 0, Top Left
               0.0f, 0.0f, -10.0f,  // 1, Bottom Left
               1.0f, 0.0f, -10.0f,  // 2, Bottom Right
               1.0f, 1.0f, -10.0f  // 3, Top Right
         };

   static final short[] recordFaces = { 0, 1, 2, 0, 2, 3 };

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
   final private ByteBuffer pauseVerticesBuffer =
         ByteBuffer.allocateDirect(SIZEOF_FLOAT * 24).order(ByteOrder.nativeOrder());
   final private ByteBuffer pauseFacesBuffer =
         ByteBuffer.allocateDirect(SIZEOF_SHORT * 12).order(ByteOrder.nativeOrder());
   final private ByteBuffer recordVerticesBuffer =
         ByteBuffer.allocateDirect(SIZEOF_FLOAT * recordVertices.length).order(ByteOrder.nativeOrder());
   final private ByteBuffer recordFacesBuffer =
         ByteBuffer.allocateDirect(SIZEOF_SHORT * recordFaces.length).order(ByteOrder.nativeOrder());

   float[] projectionM = new float[16], viewM = new float[16];

   public int previewWidth = -1;
   public int previewHeight = -1;

   GLSLAttributes previewShaderGlsl, recordShaderGlsl;

   Previewable previewer;

   byte[] cameraBuffer = null;

   String lastError = null;

   private ConditionVariable recordingCondVar = null;
   private ExecutorService recordingPool = createSingleThreadPool("Recording"),
         stopRecordingPool = createSingleThreadPool("StopRecording");
   private AsyncTask<Void, ProgressParam, Boolean> stopRecordingFuture;


   private ExecutorService createSingleThreadPool(final String name)
   //---------------------------------------------------------
   {
      return Executors.newSingleThreadExecutor(
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
   }

   private RecordingThread recordingThread = null;
   private RecordingThread.RecordingType recordingType = null;
   static final public float[] GREEN = {0, 1, 0}, RED = {1, 0, 0}, BLUE = {0, 0, 0.75f}, PURPLE = {0.855f, 0.439f, 0.839f};
   float[] recordColor = GREEN;
   float arrowRotation = 0.0f;
   boolean isPause = false, isShowRecording = false;

   public GLRecorderRenderer() {}

   public GLRecorderRenderer(RecorderActivity activity, ARSurfaceView surfaceView,
                             ORIENTATION_PROVIDER orientationProviderType)
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

      xScalePause = screenWidth / (PAUSE_WIDTH * 4);
      yScalePause = screenHeight / (PAUSE_HEIGHT * 4);
      xTranslatePause = screenWidth / 2.0f - (xScalePause * PAUSE_WIDTH) / 2.0f;
      yTranslatePause = screenHeight / 2.0f - (yScalePause * PAUSE_HEIGHT) / 2.0f;

      xScaleRecord = screenWidth / 30;
      yScaleRecord = screenHeight / 30;
      xTranslateRecord = (screenWidth - xScaleRecord)/ 2.0f;
      yTranslateRecord = screenHeight - yScaleRecord;

      try
      {
         initRender();
         previewSurfaceTexture = new SurfaceTexture(previewTexture);
         displaySurface = new Surface(previewSurfaceTexture);
         previewSurfaceTexture.setOnFrameAvailableListener(this);
         isInitialised = true;
      }
      catch (Exception e)
      {
         Log.e(TAG, "OpenGL Initialization error", e);
         throw new RuntimeException("OpenGL Initialization error", e);
      }
   }

   public boolean isSurfaceReady() { return activity.isSurfaceInitialised.get(); }

   @SuppressLint("NewApi")
   protected Previewable openCamera(int width, int height, boolean isForceCamera1)
   //-----------------------------------------------------------------------------
   {
      Previewable camera = null;
      final StringBuilder errbuf = new StringBuilder();
      boolean isNV21 = true, isYUV = false;
      try
      {
         //this check occurs previously when the application starts and requests permission so here we only return
         if (! activity.hasPermissions(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            return null;

         if ( (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) && (! isForceCamera1) )
         {
            CameraManager manager = (CameraManager) view.getContext().getSystemService(Context.CAMERA_SERVICE);
            String camList[] = manager.getCameraIdList();
            if (camList.length == 0)
            {
               Log.e(TAG, "Error: camera isn't detected.");
               return null;
            }
            for (String cameraID : camList)
            {
               CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraID);
               Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
               if (facing == null) continue;
               if (facing == CameraCharacteristics.LENS_FACING_BACK)
               {
                  this.cameraID = cameraID;
                  break;
               }
            }

            if (cameraID != null)
            {
               CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraID);
               Integer level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
               isLegacyCamera = (level != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
//               isLegacyCamera = false;
               StreamConfigurationMap streamConfig =
                     characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
//               if (streamConfig.getOutputSizes(ImageFormat.YUV_420_888) != null)
//                  isLegacyCamera = false;
               isNV21 = (streamConfig.getOutputSizes(ImageFormat.NV21) != null);
               isYUV = (streamConfig.getOutputSizes(ImageFormat.YUV_420_888) != null);

               //if ( (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP) || (isForceCamera1) )
               //   isLegacyCamera = true; //TODO: Uncomment
            }
            else
            {
               Log.e(TAG, "Could not find matching camera");
               toast("Could not find matching camera");
               return null;
            }
         }
         else
            isLegacyCamera = true;

         ActivityManager activityManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
         ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
         activityManager.getMemoryInfo(memoryInfo);
         long availSize = memoryInfo.availMem / 3;
         if (! NativeFrameBuffer.isNativeLoaded)
         {
            Log.e(TAG, "Error loading libframebuffer.so");
            toast("Error loading libframebuffer.so");
            return null;
         }
         if (isLegacyCamera)
         {
            rawBufferSize = nv21BufferSize;
            int n = MAX_BUFFERED_FRAMES;
            for (; n >= 2; n--)
            {
               long totalsize = n * nv21BufferSize;
               if (totalsize <= availSize)
                  break;
            }
            frameBuffer = new NativeFrameBuffer(n, nv21BufferSize, false);
            Log.i(TAG, "Buffer size " + n + " x " + nv21BufferSize + " = " + n * nv21BufferSize);

            camera = new LegacyPreviewCamera(this, view, nv21BufferSize, frameBuffer, frameAvailCondVar);
            if (! camera.open(Camera.CameraInfo.CAMERA_FACING_BACK, width, height, errbuf))
            {
               toast(errbuf.toString());
               return null;
            }
         }
         else
         {
            rawBufferSize = yuvBufferSize;
            int n = MAX_BUFFERED_FRAMES;
            for (; n >= 2; n--)
            {
               long totalsize = n * yuvBufferSize;
               if (totalsize <= availSize)
                  break;
            }
            frameBuffer = new NativeFrameBuffer(n, yuvBufferSize, false);
            Log.i(TAG, "Buffer size " + n + " x " + yuvBufferSize + " = " + n * yuvBufferSize);
            camera = new PreviewCamera(this, view, displaySurface, frameBuffer, frameAvailCondVar);
            if (! camera.open(CameraCharacteristics.LENS_FACING_BACK, width, height, errbuf))
            {
               toast(errbuf.toString());
               return null;
            }
         }
         return camera;
      }
      catch (Exception e)
      {
         Log.e(TAG, "Open camera Exception", e);
         throw new RuntimeException(e);
      }

   }

   boolean isLocationSensorInititialised = false;

   LocationHandler locationHandler = null;

   @SuppressLint("NewApi")
   public void initLocationSensor(File recordDir) throws FileNotFoundException
   //-------------------------------------------------------------------------
   {
      if ( (isLocationSensorInititialised) || (! activity.isLocationRecorded()) )
         return;
      isLocationSensorInititialised = true;
      final LocationManager locationManager = (LocationManager) activity.getSystemService(Context.LOCATION_SERVICE);
      //M permissions previously checked and queried in onResume
      if ( (! activity.hasPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) ||
            ( (! (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))) &&
                  (! (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))) )
         )
         return;
      locationHandler = new LocationHandler(activity, recordDir);
   }

   private void stopSensors()
   //------------------------
   {
      SensorManager sensorManager = (SensorManager) activity.getSystemService(Activity.SENSOR_SERVICE);
//      if ( (sensorManager != null) && (orientationProvider != null) )
//      {
////         sensorManager.unregisterListener(rotationListener);
//         if (orientationProvider.isStarted())
//            orientationProvider.halt();
//      }
      if (orientationListener != null)
         orientationListener.stop();
      if (locationHandler != null)
         locationHandler.stop();
   }

   Bundle lastInstanceState = new Bundle();

   public void onSaveInstanceState(Bundle B)
   //----------------------------------------------
   {
      if (previewer != null)
         previewer.freeze(B);
      B.putBoolean("isRecording", isRecording);
      B.putBoolean("isStopRecording", isStopRecording);
      B.putInt("displayWidth", displayWidth);
      B.putInt("displayHeight", displayHeight);
      B.putInt("rgbaBufferSize", rgbaBufferSize);
      B.putInt("rgbBufferSize", rgbBufferSize);
      B.putInt("rgb565BufferSize", rgb565BufferSize);
      B.putInt("nv21BufferSize", nv21BufferSize);
      B.putInt("yuvBufferSize", yuvBufferSize);
      if (locationHandler != null)
      {
         B.putBoolean("locationWriter", true);
         locationHandler.freeze(B);
      }
      B.putString("orientationProviderType", orientationProviderType.name());
      if (recordDir != null)
         B.putString("recordDir", recordDir.getAbsolutePath());
      if (recordFramesFile != null)
         B.putString("recordFramesFile", recordFramesFile.getAbsolutePath());
      if (recordFileName != null)
         B.putString("recordFileName", recordFileName);
      B.putFloat("lastSaveBearing", lastSaveBearing);
      lastInstanceState = new Bundle(B);
   }

   public void onRestoreInstanceState(Bundle B)
   //------------------------------------------
   {
      if (previewer != null)
         previewer.thaw(B);
      isRecording = B.getBoolean("isRecording");
      isStopRecording = B.getBoolean("isStopRecording");
      displayWidth = B.getInt("displayWidth");
      displayHeight = B.getInt("displayHeight");
      rgbaBufferSize = B.getInt("rgbaBufferSize");
      rgbBufferSize = B.getInt("rgbBufferSize");
      rgb565BufferSize = B.getInt("rgb565BufferSize");
      nv21BufferSize = B.getInt("nv21BufferSize");
      orientationProviderType = ORIENTATION_PROVIDER.valueOf(B.getString("orientationProviderType",
                                                                         ORIENTATION_PROVIDER.DEFAULT.name()));
      String recordDirName = B.getString("recordDir", null);
      if (recordDirName != null)
      {
         recordDir = new File(recordDirName);
         if (B.getBoolean("locationWriter"))
         {
            try
            {
               locationHandler = new LocationHandler(activity, recordDir);
               locationHandler.thaw(B);
            }
            catch (Exception _e)
            {
               locationHandler = null;
               Log.e(TAG, "", _e);
            }

         }
      }
      String recordFramesFileName = B.getString("recordFramesFile", null);
      if (recordFramesFileName != null)
         recordFramesFile = new File(recordFramesFileName);
      recordFileName = B.getString("recordFileName", null);
      lastSaveBearing = B.getFloat("lastSaveBearing", 0);
      lastInstanceState = B;
   }

   public void pause()
   //-----------------
   {
      if (isStopRecording)
      {  // @formatter:off
         mustStopNow = true;
         try { Thread.sleep(70); } catch (Exception _e) {}
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

   boolean startPreview(int width, int height, boolean isFlashOn, boolean useCamera2Api)
   //------------------------------------------------------------------------------------
   {
      rgbaBufferSize = width * height * 4; // RGBA buffer size
      rgbBufferSize = width * height * 3; // RGB buffer size
      rgb565BufferSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.RGB_565) / 8;
      // or nv21BufferSize = Width * Height + ((Width + 1) / 2) * ((Height + 1) / 2) * 2
      nv21BufferSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
      yuvBufferSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8;
      vIndex = width * height;
      uIndex = (int) (vIndex * 1.25);
      cameraBuffer = new byte[nv21BufferSize];
      isUseCamera2Api = useCamera2Api;
      previewer = openCamera(width, height, (! useCamera2Api));
      if (previewer == null)
         return false;
      if (previewer.availableResolutions().length > 0)
         activity.onResolutionChange(previewer.availableResolutions());
      previewer.startPreview(isFlashOn);
      this.previewWidth = width;
      this.previewHeight  = height;
      return true;
   }

   public boolean isPreviewing() { return (previewer != null) && previewer.isPreviewing(); }

   public boolean hasFlash() { return (previewer != null) && previewer.hasFlash(); }

   private void stopCamera()
   //----------------------
   {
      if (previewSurfaceTexture != null)
         previewSurfaceTexture.setOnFrameAvailableListener(null);
      if ( (previewer != null) && (previewer.isPreviewing()) )
         previewer.stopPreview();
   }


   protected void resume() throws FileNotFoundException
   //--------------------------------------------------
   {
//      initCamera();
//      initRender();
      mustStopNow = false;
      initOrientationSensor(orientationProviderType, null);
      lastSaveBearing = 0;
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
      shaderDir += "direct/";
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

      recordShaderGlsl = loadShaders("render/", "vPosition", null, null, null);
      if (recordShaderGlsl == null)
         throw new RuntimeException("Error loading arrow display shader");
      recordMVPLocation = glGetUniformLocation(recordShaderGlsl.shaderProgram, "MVP");
      recordColorUniform = glGetUniformLocation(recordShaderGlsl.shaderProgram, "uvColor");

      arrowVerticesBuffer.clear();
      fb = arrowVerticesBuffer.asFloatBuffer();
      fb.put(arrowVertices);
      arrowFacesBuffer.clear();
      sb = arrowFacesBuffer.asShortBuffer();
      sb.put(arrowFaces);

      pauseVerticesBuffer.clear();
      fb = pauseVerticesBuffer.asFloatBuffer();
      fb.put(pauseVertices);
      pauseFacesBuffer.clear();
      sb = pauseFacesBuffer.asShortBuffer();
      sb.put(pauseFaces);

      recordVerticesBuffer.clear();
      fb = recordVerticesBuffer.asFloatBuffer();
      fb.put(recordVertices);
      recordFacesBuffer.clear();
      sb = recordFacesBuffer.asShortBuffer();
      sb.put(recordFaces);

      return true;
   }

   private boolean initTextures(GLSLAttributes previewShaderGlsl, StringBuilder errbuf)
   //------------------------------------------------
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
      return true;
   }

   private float xScaleArrow = 1, yScaleArrow = 1, xTranslateArrow = 0, yTranslateArrow = 0,
                 xScalePause = 1, yScalePause = 1, xTranslatePause = 0, yTranslatePause = 0,
                 xScaleRecord = 1, yScaleRecord = 1, xTranslateRecord = 0, yTranslateRecord = 0;

   private float[] recordModelView = new float[16], scaleM = new float[16], translateM = new float[16],
         rotateM = new float[16], modelM = new float[16];
   StringBuilder glerrbuf = new StringBuilder();

   @Override
   public void onDrawFrame(GL10 gl)
   //------------------------------
   {
      glerrbuf.setLength(0);
      boolean isPreviewed = false;
      final int texTarget = GL_TEXTURE_EXTERNAL_OES;
      try
      {
         glViewport(0, 0, screenWidth, screenHeight);
         glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
         glUseProgram(previewShaderGlsl.shaderProgram);
         if ( (previewTexture < 0) || (! glIsTexture(previewTexture)) )
            initTextures(previewShaderGlsl, null);

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
            if (isShowRecording)
            {
               Matrix.setIdentityM(scaleM, 0);
               Matrix.scaleM(scaleM, 0, xScaleRecord, yScaleRecord,  1);
               Matrix.setIdentityM(translateM, 0);
               Matrix.translateM(translateM, 0, xTranslateRecord, yTranslateRecord, 0);
               Matrix.multiplyMM(modelM, 0, translateM, 0, scaleM, 0);
               Matrix.multiplyMM(recordModelView, 0, viewM, 0, modelM, 0);
               Matrix.multiplyMM(recordMVP, 0, projectionM, 0, recordModelView, 0);
               glUseProgram(recordShaderGlsl.shaderProgram);
               glUniformMatrix4fv(recordMVPLocation, 1, false, recordMVP, 0);
               glUniform3f(recordColorUniform, RED[0], RED[1], RED[2]);
               recordVerticesBuffer.rewind();
               glVertexAttribPointer(recordShaderGlsl.vertexAttr, 3, GL_FLOAT, false, 0, recordVerticesBuffer);
               glEnableVertexAttribArray(recordShaderGlsl.vertexAttr);
               recordFacesBuffer.rewind();
               glDrawElements(GL_TRIANGLES, 12, GL_UNSIGNED_SHORT, recordFacesBuffer.asShortBuffer());
            }
            else if (! isPause)
            {
               Matrix.setIdentityM(scaleM, 0);
               Matrix.scaleM(scaleM, 0, xScaleArrow, yScaleArrow, 1);
               Matrix.setIdentityM(translateM, 0);
               Matrix.translateM(translateM, 0, xTranslateArrow, yTranslateArrow, 0);
               Matrix.setRotateM(rotateM, 0, arrowRotation, 0, 0, 1);
               Matrix.multiplyMM(recordModelView, 0, translateM, 0, scaleM, 0);
               Matrix.multiplyMM(modelM, 0, recordModelView, 0, rotateM, 0);
               Matrix.multiplyMM(recordModelView, 0, viewM, 0, modelM, 0);
               Matrix.multiplyMM(recordMVP, 0, projectionM, 0, recordModelView, 0);
               glUseProgram(recordShaderGlsl.shaderProgram);
               glUniformMatrix4fv(recordMVPLocation, 1, false, recordMVP, 0);
               glUniform3f(recordColorUniform, recordColor[0], recordColor[1], recordColor[2]);
               arrowVerticesBuffer.rewind();
               glVertexAttribPointer(recordShaderGlsl.vertexAttr, 3, GL_FLOAT, false, 0, arrowVerticesBuffer);
               glEnableVertexAttribArray(recordShaderGlsl.vertexAttr);
               arrowFacesBuffer.rewind();
               glDrawElements(GL_TRIANGLES, 9, GL_UNSIGNED_SHORT, arrowFacesBuffer.asShortBuffer());
            }
            else
            {
               Matrix.setIdentityM(scaleM, 0);
               Matrix.scaleM(scaleM, 0, yScalePause, xScalePause,  1);
               Matrix.setIdentityM(translateM, 0);
               Matrix.translateM(translateM, 0, xTranslatePause, yTranslatePause, 0);
               Matrix.multiplyMM(modelM, 0, translateM, 0, scaleM, 0);
               Matrix.multiplyMM(recordModelView, 0, viewM, 0, modelM, 0);
               Matrix.multiplyMM(recordMVP, 0, projectionM, 0, recordModelView, 0);
               glUseProgram(recordShaderGlsl.shaderProgram);
               glUniformMatrix4fv(recordMVPLocation, 1, false, recordMVP, 0);
               glUniform3f(recordColorUniform, recordColor[0], recordColor[1], recordColor[2]);
               pauseVerticesBuffer.rewind();
               glVertexAttribPointer(recordShaderGlsl.vertexAttr, 3, GL_FLOAT, false, 0, pauseVerticesBuffer);
               glEnableVertexAttribArray(recordShaderGlsl.vertexAttr);
               pauseFacesBuffer.rewind();
               glDrawElements(GL_TRIANGLES, 12, GL_UNSIGNED_SHORT, pauseFacesBuffer.asShortBuffer());
            }
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
         if ((previewer != null) && (isPreviewed))
            previewer.releaseFrame();
      }
   }

   private GLSLAttributes loadShaders(String assetDir, String vertexAttrName, String colorAttrName,
                                      String textureAttrName,
                                      String normalAttrName)
   //------------------------------------------------------------------------------------------------------------------------
   {
      assetDir = assetDir.trim();
      String shaderFile = "shaders/" + assetDir + ((assetDir.endsWith("/")) ? "" : "/") + VERTEX_SHADER;
      String src = activity.readAssetTextFile(shaderFile, null);
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
      src = activity.readAssetTextFile(shaderFile, null);
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

   public void requestRender() { view.requestRender(); }

   File recordDir, recordFramesFile;
   String recordFileName;
   final Map<String, Object> recordHeader = new HashMap<String, Object>();

   public boolean startRecording(File dir, int width, int height, String name, float increment, long maxsize,
                                 RecordingThread.RecordingType recordingType, ORIENTATION_PROVIDER orientationType,
                                 List<Integer> xtraSensorList, boolean isPostProcess, boolean isFlashOn,
                                 boolean useCamera2Api, boolean isStitch)
   //---------------------------------------------------------------------------------------------------------------
   {
      if ( (isRecording) || (dir == null) ) return false;
      boolean isStopped = false;
      if ( (previewer != null) &&
           ( ( (height > 0) && (height != previewer.getPreviewHeight()) ) ||
             ( (width > 0 ) && (width != previewer.getPreviewWidth()) ) ||
             (isFlashOn != previewer.isFlashOn()) ||
             (useCamera2Api != isUseCamera2Api)
           )
         )
      {
         if (previewer.isPreviewing())
         {
            if (previewSurfaceTexture != null)
               previewSurfaceTexture.setOnFrameAvailableListener(null);
            previewer.stopPreview();
         }
         try { previewer.close(); } catch (Exception _e) { Log.e(TAG, "", _e); }
         isStopped = true;
         previewer = null;
      }
      if ( (width > 0 ) && (width != previewWidth) )
         previewWidth = width;
      if ( (height > 0) && (height != previewHeight) )
         previewHeight = height;
      isUseCamera2Api = useCamera2Api;

      if ( ( (isStopped) || (previewer == null) ) && ( (previewWidth > 0) && (previewHeight > 0) ) )
         startPreview(previewWidth, previewHeight, isFlashOn, useCamera2Api);
      if (previewer == null)
      {
         recordingThread = null;
         return false;
      }
      if (! dir.isDirectory())
         dir.mkdirs();
      if ((! dir.isDirectory()) || (! dir.canWrite()))
      {
         Log.e(TAG, dir.getAbsolutePath() + " not a writable directory");
         toast("Error: Could not create directory " + dir.getAbsolutePath());
         recordingThread = null;
         return false;
      }
      stopRecordingThread();

      if ((name == null) || (name.trim().isEmpty()))
         recordFileName = "Unknown";
      else
         recordFileName = name;
      dir = new File(dir, recordFileName);
      if ( (dir.exists()) && (! RecorderActivity.NO_OVERWRITE_CHECK) )
         RecordingThread.delDirContents(dir);
      if (! dir.mkdirs())
      {
         if (! RecorderActivity.NO_OVERWRITE_CHECK)
         {
            toast("Error: Could not create directory " + dir.getAbsolutePath());
            recordingThread = null;
            return false;
         }
      }
      isPause = true;
      recordColor = GLRecorderRenderer.GREEN;
      recordDir = dir;
      StringBuilder errbuf = new StringBuilder();
      boolean isOrientationSensors = false;
      try
      {
         isOrientationSensors = initOrientationSensor(orientationType, errbuf);
         if (isOrientationSensors)
            initLocationSensor(recordDir);
      }
      catch (Exception e)
      {
         Log.e(TAG, "Exception initializing Location/Orientation Sensors", e);
         isOrientationSensors = false;
         errbuf.append("Exception initializing Location/Orientation Sensors: ").append(e.getMessage());
         recordingThread = null;
      }
      if (! isOrientationSensors)
      {
         toast(errbuf.toString());
         return false;
      }
      if ( (! xtraSensorList.isEmpty()) && (orientationProvider != null) && (orientationListener != null) )
      {
         if (orientationListener.initExtra(xtraSensorList))
         {
            orientationProvider.setRawSensorListener(orientationListener);
            int[] sensors = new int[xtraSensorList.size()];
            for (int i=0; i<xtraSensorList.size(); i++)
               sensors[i] = xtraSensorList.get(i);
            orientationProvider.rawSensors(sensors);
            orientationProvider.activateRawSensors(false);
         }
      }

      recordingCondVar = new ConditionVariable(false);
      recordingThread = null;
      try
      {
         switch (recordingType)
         {
            case THREE60:
               recordingThread = new Three60RecordingThread(this, previewer, recordDir, increment, maxsize,
                                                            frameAvailCondVar, orientationListener, locationHandler,
                                                            isStitch, isPostProcess);
               break;
            case FREE:
               recordingThread = new FreeRecordingThread(this, previewer, recordDir, maxsize, frameAvailCondVar,
                                                         orientationListener, locationHandler, isPostProcess);
               break;
         }
      }
      catch (Exception ee)
      {
         Log.e(TAG, "", ee);
         toast("Exception Creating Recording Thread: " + ee.getMessage());
      }
      if (recordingThread == null)
      {
         orientationListener.stop();
         return false;
      }

      isRecording = true;
      this.recordingType = recordingType;
      recordingThread.executeOnExecutor(recordingPool);
      if (DEBUG_TRACE) Debug.startMethodTracing("recorder");
      return true;
   }

   public void pauseRecording()
   //-------------------------
   {
      if ( (isRecording) && (recordingThread != null) )
         recordingThread.pauseRecording();
   }

   public void resumeRecording()
   //-------------------------
   {
      if ( (isRecording) && (recordingThread != null) )
         recordingThread.resumeRecording();
   }

   void stopRecordingFlag()
   //----------------------
   {
      if ( (recordingThread != null) && (recordingThread.getStatus() == AsyncTask.Status.RUNNING) )
         isRecording = false;
   }

   private void stopRecordingThread()
   //--------------------------------
   {
      if ( (recordingThread != null) && (recordingThread.getStatus() == AsyncTask.Status.RUNNING) )
      {
         isRecording = false;
         recordingCondVar.open();
         try { Thread.sleep(100); } catch (Exception _e) {}
         if (recordingThread.getStatus() == AsyncTask.Status.RUNNING)
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
      if (DEBUG_TRACE) Debug.stopMethodTracing();
      if ( (previewer != null) && (previewer.hasFlash()) )
         previewer.setFlash(false);
      if ( (recordingThread == null) || (isStoppingRecording) ) return false;
      isStoppingRecording = true;
      if (locationHandler != null) try { locationHandler.stop(); } catch (Exception _e) { Log.e(TAG, "", _e); }
      stopRecordingThread();
      if ( (stopRecordingThread != null) && (! stopRecordingThread.isComplete) )
      {
         stopRecordingThread.cancel(true);
         if (stopRecordingPool != null)
            stopRecordingPool.shutdownNow();
      }
      stopRecordingPool = createSingleThreadPool("Stop Recording");
      stopRecordingThread = new StopRecordingThread(isCancelled);
      stopRecordingFuture = stopRecordingThread.executeOnExecutor(stopRecordingPool);
      return true;
   }

   public boolean initOrientationSensor(ORIENTATION_PROVIDER orientationProviderType, StringBuilder errbuf)
   //------------------------------------------------------------------------------------------------------
   {
      if ( (orientationProvider != null) && (orientationProvider.isStarted()) )
         orientationProvider.halt();
      if (orientationProviderType == null)
         orientationProviderType = this.orientationProviderType;
      else
         this.orientationProviderType = orientationProviderType;
      if (orientationProviderType == null)
         return false;
      SensorManager sensorManager = (SensorManager) activity.getSystemService(Activity.SENSOR_SERVICE);
      if (orientationProviderType == ORIENTATION_PROVIDER.DEFAULT)
      {
         if (OrientationProvider.supportsOrientationProvider(activity, ORIENTATION_PROVIDER.ROTATION_VECTOR))
            orientationProviderType = ORIENTATION_PROVIDER.ROTATION_VECTOR;
         else if (OrientationProvider.supportsOrientationProvider(activity, ORIENTATION_PROVIDER.STABLE_FUSED_GYROSCOPE_ROTATION_VECTOR))
            orientationProviderType = ORIENTATION_PROVIDER.STABLE_FUSED_GYROSCOPE_ROTATION_VECTOR;
         else if (OrientationProvider.supportsOrientationProvider(activity, ORIENTATION_PROVIDER.FUSED_GYRO_ACCEL_MAGNETIC))
            orientationProviderType = ORIENTATION_PROVIDER.FUSED_GYRO_ACCEL_MAGNETIC;
         else if (OrientationProvider.supportsOrientationProvider(activity, ORIENTATION_PROVIDER.ACCELLO_MAGNETIC))
            orientationProviderType = ORIENTATION_PROVIDER.ACCELLO_MAGNETIC;
         else
         {
            if (errbuf != null)
               errbuf.append("ERROR: Device does not appear to have required orientation sensors");
            return false;
         }
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

      try
      {
         orientationListener = new OrientationHandler(recordDir, 20, orientationProviderType);
      }
      catch (IOException e)
      {
         if (errbuf != null)
            errbuf.append("ERROR: Could not access recording directory ").append(recordDir);
         return false;
      }
      orientationProvider.setOrientationListener(orientationListener);
      orientationProvider.initiate();
      if (! orientationProvider.isStarted())
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

   public String getVersionName() { return activity.getVersionName(); }

   public int getVersionCode() { return activity.getVersionCode(); }

   public Surface newDisplaySurface()
   //-------------------------------
   {
      previewSurfaceTexture.setOnFrameAvailableListener(null);
      displaySurface.release();
      previewSurfaceTexture = new SurfaceTexture(previewTexture);
      displaySurface = new Surface(previewSurfaceTexture);
      previewSurfaceTexture.setOnFrameAvailableListener(this);
      return displaySurface;
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

   class StopRecordingThread extends AsyncTask<Void, ProgressParam, Boolean>
   //=========================================================================
   {
      File recordHeaderFile = null;
      boolean isCancelled = false;

      public boolean isComplete = false;

      public StopRecordingThread(boolean isCancelled) { this.isCancelled = isCancelled; }

      @Override
      protected Boolean doInBackground(Void... params)
      //----------------------------------------------
      {
         PowerManager powerManager = (PowerManager) activity.getSystemService(Context.POWER_SERVICE);
         PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                                                   "arem:wakelock");
         try
         {
            wakeLock.acquire();
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            isRecording = false;
            isStopRecording = true;
            ((Bufferable) previewer).bufferOff();
            if (previewer.isPreviewing())
            {
               if (previewSurfaceTexture != null)
                  previewSurfaceTexture.setOnFrameAvailableListener(null);
            }
            previewer.stopPreview();
            orientationListener.bufferOff();
            orientationListener.stop();
            orientationProvider.deactivateRawSensors();
            if (locationHandler != null)
            {
               locationHandler.bufferOff();
               locationHandler.stop();
            }
            if ( (isCancelled) && (recordFramesFile != null) && (recordFramesFile.getParentFile() != null) )
               RecordingThread.delDirContents(recordFramesFile.getParentFile());
            recordColor = GREEN;
            arrowRotation = 0;
            recordFramesFile = null;
            recordFileName = null;
            arrowRotation = 0;
            isRecording = isStoppingRecording = isStopRecording = false;
            startPreview(640, 480, false, false);
            return true;
         }
         finally
         {
            wakeLock.release();
            isComplete = true;
         }
      }

      @Override
      protected void onProgressUpdate(ProgressParam... values)
      {
         activity.onStatusUpdate(values[0]);
      }

      @Override
      protected void onPostExecute(Boolean aBoolean)
      {
         activity.stoppedRecording(recordingType, recordHeaderFile, recordFramesFile);
      }

      @Override protected void onCancelled(Boolean B) { }
   }

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
