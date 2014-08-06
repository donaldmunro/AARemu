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

package to.augmented.reality.android.em;

import android.annotation.*;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.opengl.GLSurfaceView;
import android.os.*;
import android.os.Process;
import android.renderscript.*;
import android.renderscript.Type;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import to.augmented.reality.android.common.math.*;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

import static to.augmented.reality.android.common.sensor.orientation.OrientationProvider.ORIENTATION_PROVIDER;

/**
 AARemu is a software tool enabling simulation of Augmented Reality by allowing an AR developer to record
 a 360 degree view of a location using the devices camera and orientation sensors (this functionality is provided by the
 ARemRecorder app). The ARCamera class which implements
 an emulation of the Android camera class can then be used to preview the recorded scene instead of the live camera
 preview provided by the Android Camera class. The ARCamera preview callback is analogous to the standard Camera except that
 the preview bytes provided in the callback are extracted from a file created by the recorder application based on the
 current compass bearing. These preview bytes are passed to the development code via the same preview callback as
 provided by the standard Camera classes and can thus be  processed by Computer Vision algorithms before being displayed
 by the client application.  This allows the developer to debug and test an AR application in the comfort of his office
 or home without having to make extensive changes to the programming code.

 Creation
 Creation/acquisition of an ARCamera instance differs from the standard Camera class as an ARCamera needs to be constructed
 and added to an internal map of instances used by ARCamera.open. Apart from having to construct an ARCamera object
 all other Camera initialisation is similar to that done for a normal Camera class.
 Examples
 <code>
 camera = new ARCamera(activity, -1, headerFile, framesFile);
 cameraId = camera.getId();
 ..
 ..
 ..
 camera = (ARCamera) ARCamera.open(cameraId);
 </code>
 Create an instance delegating to the rear facing camera:
 <code>
 for (int i = 0; i < Camera.getNumberOfCameras(); i++)
 {
    Camera.getCameraInfo(i, cameraInfo);
    if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK)
    {
       Camera camera = Camera.open(i);
       arcamera = new ARCamera(activity, i, camera, headerFile, framesFile);
    }
 }
 ..
 ..
 ARCamera camera = (ARCamera) ARCamera.open(cameraId);
 </code>
 */
public class ARCamera
//===================
{
   public enum RecordFileFormat { RGBA, RGB, RGB565, NV21 };
   public enum DelegationTypes { ALL, READ, NONE };

   final static private String TAG = ARCamera.class.getSimpleName();
   final static private Map<Integer, ARCamera> INSTANCES = new HashMap<Integer, ARCamera>();
   static private int ARCAMERA_COUNT = 0;

   private static final String KEY_PREVIEW_SIZE = "preview-size";
   private static final String KEY_PREVIEW_FORMAT = "preview-format";
   private static final String KEY_PREVIEW_FRAME_RATE = "preview-frame-rate";
   private static final String KEY_PREVIEW_FPS_RANGE = "preview-fps-range";
   private static final String KEY_ROTATION = "rotation";
   private static final String KEY_FOCAL_LENGTH = "focal-length";
   private static final String KEY_HORIZONTAL_VIEW_ANGLE = "horizontal-view-angle";
   private static final String KEY_VERTICAL_VIEW_ANGLE = "vertical-view-angle";

   private static final String SUPPORTED_VALUES_SUFFIX = "-values";

   private static final String TRUE = "true";
   private static final String FALSE = "false";

   // Formats for setPreviewFormat and setPictureFormat.
   private static final String PIXEL_FORMAT_YUV422SP = "yuv422sp";
   private static final String PIXEL_FORMAT_YUV420SP = "yuv420sp";
   private static final String PIXEL_FORMAT_YUV422I = "yuv422i-yuyv";
   private static final String PIXEL_FORMAT_YUV420P = "yuv420p";
   private static final String PIXEL_FORMAT_RGB565 = "rgb565";
   private static final String PIXEL_FORMAT_JPEG = "jpeg";

   private File headerFile, framesFile;
   private Context context = null;
   private boolean isOpen = false;
   private Surface surface = null;
   private int previewFrameRate = 60000, previewFrameRateMin = 10000; // Scaled by 1000 as per Camera.Parameters
   private int previewWidth =-1, previewHeight =-1;
   private float focalLength = -1, fovx = -1, fovy = -1;

   private SurfaceTexture surfaceTexture = null;
   private boolean isPreviewing = false, isOneShot = false, hasBuffer = false;
   protected boolean isUseBuffer = false;
   private int renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY;
   private ORIENTATION_PROVIDER orientationProviderType = ORIENTATION_PROVIDER.DEFAULT;
   private RecordFileFormat fileFormat = RecordFileFormat.RGBA;
   public RecordFileFormat getFileFormat() { return fileFormat; }

   public boolean isUseBuffer() { return isUseBuffer; }
   private Camera.PreviewCallback callback = null;
   private int orientation = 0;
   private Camera.ErrorCallback errorCallback;

   private Map<String, String> headers;
   private BearingListener bearingListener = null;

   public String getHeader(String k) { return headers.get(k); }
   public String getHeader(String k, String def) { return (headers.containsKey(k)) ? headers.get(k) : def; }
   public int getHeaderInt(String k, int def) { return getMapInt(headers, k, def); }
   public float getHeaderFloat(String k, float def) { return getMapFloat(headers, k, def); }

   private int bufferSize;
   private Camera camera = null;

   /**
    * If ALL and a delegate Camera instance was specified in the constructor then all non-implemented methods including
    * those that perform hardware operations such as zooming etc will be delegated.
    * If READ then only methods that read values from hardware cameras will be delegated.
    * If NONE then no delegation takes place, The delegate instance will only be used to obtain private classes such
    * as Camera.Size and Camera.Parameters.
    */
   public DelegationTypes delegationType = DelegationTypes.READ;

   private int id = -1;
   public int getId() {  return id;  }

   final private ExecutorService playbackExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
   {
      @Override
      public Thread newThread(Runnable r)
      {
         Thread t = new Thread(r);
         t.setDaemon(true);
         t.setName("Playback");
         return t;
      }
   });
   private Future<?> playbackFuture;
   private PlaybackThread playbackThread = null;

   /**
    * Creates an ARCamera instance and adds it to the defined ARCamera instances map. The instance can subsequently
    * be accessed using open() or open(int id). The Camera instance is used for delegation and to obtain an internal
    * Camera.Size instance as Camera.Size has a private constructor.
    * @param context A Context instance used to access sensor classes.
    * @param cameraId The camera id to use. It may be the original id of the accompanying Camera instance or
    *                 a id greater than the last available hardware camera if separate ARCamera instances are desired.
    *                 If it is the original id or the id of an existing hardware camera then the emulated camera
    *                 replaces the hadrware camera when opened using ARCamera.open(id).
    * @param camera A Camera instance is used for delegation and to obtain an internal
    * Camera.Size instance as Camera.Size has a private constructor.
    * @throws Exception
    */
   public ARCamera(Context context, int cameraId, Camera camera)
   //-----------------------------------------------------------
   {
      init(context, cameraId);
      this.camera = camera;
   }

   /**
    * Creates an ARCamera instance and adds it to the defined ARCamera instances map. The instance can subsequently
    * be accessed using open() or open(int id). The Camera instance is used for delegation and to obtain an internal
    * Camera.Size instance as Camera.Size has a private constructor.
    * @param context A Context instance used to access sensor classes.
    * @param cameraId The camera id to use. It may be the original id of the accompanying Camera instance or
    *                 a id greater than the last available hardware camera if separate ARCamera instances are desired.
    *                 If it is the original id or the id of an existing hardware camera then the emulated camera
    *                 replaces the hadrware camera when opened using ARCamera.open(id).
    * @param camera A Camera instance is used for delegation and to obtain an internal
    * Camera.Size instance as Camera.Size has a private constructor.
    * @param headerFile The recording header file of the 360 degree recording.
    * @param framesFile The recording frames file of the 360 degree recording.
    * @throws Exception
    */
   public ARCamera(Context context, int cameraId, Camera camera, File headerFile, File framesFile)
         throws IOException
   //------------------------------------------------------------------------------------------
   {
      this.camera = camera;
      init(context, cameraId);
      if ( (headerFile != null) && (framesFile != null) )
         setFiles(headerFile, framesFile);
   }

   /**
    * Creates an ARCamera instance and adds it to the defined ARCamera instances map. The instance can subsequently
    * be accessed using open() or open(int id). This constructor does not take a Camera instance so no delegation occurs
    * and methods that return Camera.Size may return null if it proves impossible to hijack a Camera.Size instance
    * from one of the existing hardware cameras.
    * @param context A Context instance used to access sensor classes.
    * @param cameraId The camera id to use. It can be a id of an existing hardware camera or an id greater than the
    *                 last available hardware camera if separate ARCamera instances are desired.
    *                 If it is the id of an existing hardware camera then the emulated camera
    *                 replaces the hadrware camera when opened using ARCamera.open(id).
    * @throws Exception
    */
   public ARCamera(Context context, int cameraId) { init(context, cameraId); }

   /**
    * Creates an ARCamera instance and adds it to the defined ARCamera instances map. The instance can subsequently
    * be accessed using open() or open(int id). This constructor does not take a Camera instance so no delegation occurs
    * and methods that return Camera.Size may return null if it proves impossible to hijack a Camera.Size instance
    * from one of the existing hardware cameras.
    * @param context A Context instance used to access sensor classes.
    * @param cameraId The camera id to use. It may be the original id of the accompanying Camera instance or
    *                 a id greater than the last available hardware camera if separate ARCamera instances are desired.
    *                 If it is the id of an existing hardware camera then the emulated camera
    *                 replaces the hadrware camera when opened using ARCamera.open(id).
    * @param headerFile The header file of the 360 degree recording.
    * @param framesFile The frames file of the 360 degree recording.
    * @throws Exception
    */
   public ARCamera(Context context, int cameraId, File headerFile, File framesFile) throws IOException
   //--------------------------------------------------------------------------------------------------
   {
      init(context, cameraId);
      if ( (headerFile != null) && (framesFile != null) )
         setFiles(headerFile, framesFile);
   }


   final private void init(Context context, int cameraId)
   //------------------------------------------------------------------------------------------
   {
      this.context = context;
      if (cameraId < 0)
         cameraId = Camera.getNumberOfCameras() + ARCAMERA_COUNT++;
      else
      {
         Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
         try { Camera.getCameraInfo(cameraId, cameraInfo); } catch (Throwable _e) { ARCAMERA_COUNT++; }
      }
      for (int i = 0; i < Camera.getNumberOfCameras(); i++)
      {
         if (i != cameraId)
            INSTANCES.put(cameraId, null);
      }
      INSTANCES.put(cameraId, this);
      this.id = cameraId;
   }

   public static int getNumberOfCameras() { return ARCAMERA_COUNT + Camera.getNumberOfCameras(); }

   /**
    * @return An ARCamera instance.
    */
   public static ARCamera open()
   //---------------------------
   {
      ARCamera instance = INSTANCES.get(0);
      if (instance == null)
      {
         Collection<ARCamera> allCameras = INSTANCES.values();
         for (ARCamera camera : allCameras)
         {
            if (camera != null)
               return camera;
         }
      }
      if (instance == null)
         throw new RuntimeException("First create an ARCamera instance using a ARCamera constructor");
      instance.isOpen = true;
      return instance;
   }

   /**
    * Return an ARCamera or Camera instance corresponding to the supplied camera id.
    * @param id The camera id.
    * @return An ARCamera or Camera instance
    */
   public static Object open(int id)
   //---------------------------------
   {
      if (id == -1)
         return ARCamera.open();
      ARCamera instance = INSTANCES.get(id);
      if (instance == null)
      {
         Camera C = Camera.open(id);
         if (C != null)
            return C;
         else
            throw new RuntimeException("First create an ARCamera instance using a ARCamera constructor");
      }
      else
      {
         instance.isOpen = true;
         return instance;
      }
   }

   /**
    * Set the 360 degree recording header and frames files to use for playback.
    * Setting either file to null will call release and stop previewing.
    * @param headerFile The recording header file of the 360 degree recording.
    * @param framesFile The recording frames file of the 360 degree recording.
    * @throws FileNotFoundException
    */
   public void setFiles(File headerFile, File framesFile) throws IOException
   //------------------------------------------------------------------------
   {
      if ( (headerFile == null) || (framesFile == null) )
      {
         Log.d(TAG, "Setting recording files to null");
         release();
         this.headerFile = this.framesFile = null;
         return;
      }
      if ( (this.headerFile != null) && (this.headerFile.equals(headerFile)) &&
           (this.framesFile != null) && (this.framesFile.equals(framesFile)) )
         return;
      if ( (! headerFile.exists()) || (! headerFile.canRead()) )
         throw new FileNotFoundException("Header file " + headerFile.getAbsolutePath() + " not found or not readable");
      if ( (! framesFile.exists()) || (! framesFile.canRead()) )
         throw new FileNotFoundException("Frames file " + framesFile.getAbsolutePath() + " not found or not readable");
      headers = parseHeader(headerFile);
      bufferSize = getMapInt(headers, "BufferSize", -1);
      String s = headers.get("OrientationProvider");
      if (s != null)
         orientationProviderType = ORIENTATION_PROVIDER.valueOf(s);  // throws IllegalArgumentException
      else
         orientationProviderType = ORIENTATION_PROVIDER.DEFAULT;
      s = headers.get("FileFormat");
      if (s != null)
         fileFormat = RecordFileFormat.valueOf(s); // throws IllegalArgumentException
      previewWidth = getMapInt(headers, "PreviewWidth", - 1);
      previewHeight = getMapInt(headers, "PreviewHeight", - 1);
      if ( (previewWidth < 0) || (previewHeight < 0) )
         throw new RuntimeException("Header file " + headerFile.getAbsolutePath() +
                                    " does not contain valid preview width and/or height");
      focalLength = getMapFloat(headers, "FocalLength", - 1);
      fovx = getMapFloat(headers, "fovx", -1);
      fovy = getMapFloat(headers, "fovy", -1);
      this.headerFile = headerFile;
      this.framesFile = framesFile;
   }

   public static void getCameraInfo(int cameraId, Camera.CameraInfo cameraInfo)
   //-------------------------------------------------------------------------
   {
      ARCamera arcamera  = INSTANCES.get(cameraId);
      if (arcamera != null)
      {
         cameraInfo.facing = Camera.CameraInfo.CAMERA_FACING_BACK;
         cameraInfo.canDisableShutterSound = true;
         cameraInfo.orientation = 0;
      }
      else Camera.getCameraInfo(cameraId, cameraInfo);
   }

   private static Map<String, String> parseHeader(File headerFile) throws IOException
   //--------------------------------------------------------------------------------
   {
      BufferedReader br = null;
      Map<String, String> header = new HashMap<String, String>();
      try
      {
         br = new BufferedReader(new FileReader(headerFile));
         String line = null;
         while ( (line = br.readLine()) != null )
         {
            line = line.trim();
            if ( (line.isEmpty()) || (line.startsWith("#")) )
               continue;
            int p = line.indexOf('=');
            if (p < 0)
            {
               String error = "Invalid line " + line + " in header file " + headerFile.getAbsolutePath() + ". = not found";
               Log.e(TAG, error);
               throw new RuntimeException(error);
            }
            String k = line.substring(0, p);
            String v = line.substring(p+1);
            header.put(k, v);
         }
      }
      finally
      {
         if (br != null)
            try { br.close(); } catch (Exception _e) {}
      }
      return header;
   }

   /**
    * Set the Orientation Provider type. @see to.augmented.reality.android.sensor.orientation.OrientationProvider
    * @param orientationProviderType
    */
   public void setOrientationProviderType(ORIENTATION_PROVIDER orientationProviderType) { this.orientationProviderType = orientationProviderType; }

   public ORIENTATION_PROVIDER getOrientationProviderType() { return orientationProviderType; }

   /**
    * Sets the recording file format. This is usually specified parsed from the header file so it should not need
    * to be specified.
    * @param format The format as specified by the RecordFileFormat enum.
    */
   public void setFileFormat(RecordFileFormat format) { fileFormat = format; }

   /**
    * Releases resources held by and stops threads owned by an ARCamera instance and stops previewing.
    * If a delegate camera class exists it will also be released.
    */
   public void release()
   //-------------------
   {
      isOpen = false;
      if (isReviewing())
         stopReview();
      if (isPreviewing)
         stopPreview();
      isPreviewing = false;
      if (camera != null)
         try { camera.release(); } catch (Exception e) {}
   }

   public void unlock() { }

   public void lock() { }

   public void reconnect() { isOpen = true; }

   public Camera.Parameters getParameters()
   //--------------------------------------
   {
      Camera.Parameters parameters = getOrStealParameters();
      if (parameters != null)
         setTo(parameters);
      return parameters;
   }

   private void setTo(Camera.Parameters parameters)
   //---------------------------------------------
   {
      parameters.setPreviewSize(previewWidth, previewHeight);
      if ( ( previewFrameRateMin > 0) && (previewFrameRate > 0) )
         parameters.setPreviewFpsRange(previewFrameRateMin, previewFrameRate);
      else if (previewFrameRate > 0)
         parameters.setPreviewFpsRange(10000, previewFrameRate);
      if (previewFrameRate > 0)
         parameters.setPreviewFrameRate(previewFrameRate);
      if (focalLength >= 0)
         parameters.set(KEY_FOCAL_LENGTH, Float.toString(focalLength));
      if (fovx >= 0)
         parameters.set(KEY_HORIZONTAL_VIEW_ANGLE, Float.toString(fovx));
      if (fovy >= 0)
         parameters.set(KEY_VERTICAL_VIEW_ANGLE, Float.toString(fovy));
      final String v = String.format(Locale.US, "%dx%d", previewWidth, previewHeight);
      parameters.set(KEY_PREVIEW_SIZE, v);
      parameters.set(KEY_PREVIEW_SIZE + SUPPORTED_VALUES_SUFFIX, v); //If more than one then comma delimited
   }

   /**
    * Sets the Camera parameters. Relevant properties will be copied to the ARCamera class instance.
    * Does not set the delegate Camera instance if any.
    * @see android.hardware.Camera#setParameters
    * @see #setParameters(android.hardware.Camera.Parameters, boolean)
    * @param params
    */
   public void setParameters(Camera.Parameters params) { setParameters(params, false);}

   /**
    * Sets the Camera parameters. Relevant properties will be copied to the ARCamera class instance.
    * If a delegate Camera instance has been specified and isDelegate is true then the delegate Camera instance
    * parameters will also be set
    * @see android.hardware.Camera#setParameters
    * @param params
    * @param isDelegate If <i>true</i> then also call setParameters on the delegate camera.
    */
   public void setParameters(final Camera.Parameters params, final boolean isDelegate)
   //---------------------------------------------------------------------------------
   {
      setFrom(params);
      if ( (isDelegate) && (camera != null) )
         try { camera.setParameters(params); } catch (Exception e) { Log.e(TAG, "", e); }
   }

   /**
    * Sets the delegate Camera parameters.
    * @param params
    */
   public void setDelegateParameters(Camera.Parameters params) { if (camera != null) camera.setParameters(params);}

   private void checkFPSRange(int fps)
   //---------------------------------
   {
      if ( (fps > 0) && (fps < 1000) )
         throw new RuntimeException(String.format(Locale.US,
                                                  "Frame rate should be scaled by 1000. Did you mean %d instead of %d ?",
                                                  fps*1000, fps));
   }

   private void setFrom(Camera.Parameters parameters)
   //---------------------------------------------
   {
      parameters.setPreviewSize(previewWidth, previewHeight);
      int fps = parameters.getPreviewFrameRate();
      if (fps != previewFrameRate)
      {
         checkFPSRange(fps);
         previewFrameRate = fps;
      }
      else
      {
         int[] range = new int[2];
         parameters.getPreviewFpsRange(range);
         if (range[0] != previewFrameRateMin)
         {
            checkFPSRange(range[0]);
            previewFrameRateMin = range[0]; // Not used at this time
         }
         if (range[1] != previewFrameRate)
         {
            checkFPSRange(range[1]);
            previewFrameRate = range[1];
         }
      }
      if (! QuickFloat.equals(parameters.getFocalLength(), focalLength, 0.0001f))
         focalLength = parameters.getFocalLength();
      if (! QuickFloat.equals(parameters.getHorizontalViewAngle(), fovx, 0.0001f))
         fovx = parameters.getHorizontalViewAngle();
      if (! QuickFloat.equals(parameters.getVerticalViewAngle(), fovy, 0.0001f))
         fovy = parameters.getVerticalViewAngle();
   }

   /**
    * @see android.hardware.Camera#setPreviewDisplay
    * @param holder
    * @throws IOException
    */
   public void setPreviewDisplay(SurfaceHolder holder) throws IOException
   //---------------------------------------------------------------------
   {
      if (holder != null)
         surface = holder.getSurface();
      else
         surface = null;
   }

   /**
    * @return The location where the recording was made as recorded in the header file/
    */
   public Location getRecordedLocation()
   //-----------------------------------
   {
      Location location = null;
      if (headers != null)
      {
         String s = headers.get("Location");
         if (s != null)
         {
            String[] as = s.split(",");
            if ((as != null) && (as.length >= 2))
            {
               float latitude, longitude, altitude = 0;
               try
               {
                  latitude = Float.parseFloat(as[0].trim());
                  longitude = Float.parseFloat(as[1].trim());
                  if (as.length > 2)
                     try
                     {
                        altitude = Float.parseFloat(as[2].trim());
                     }
                     catch (NumberFormatException _e)
                     {
                        altitude = 0;
                     }
                  location = new Location(LocationManager.GPS_PROVIDER);
                  location.setTime(System.currentTimeMillis());
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                     location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                  else
                  {
                     // Kludge because some location APIs requires elapsedtime in nanos but call is not available in all Android versions.
                     try
                     {
                        Method makeCompleteMethod = null;
                        makeCompleteMethod = Location.class.getMethod("makeComplete");
                        if (makeCompleteMethod != null)
                           makeCompleteMethod.invoke(location);
                     }
                     catch (Exception e)
                     {
                        e.printStackTrace();
                     }
                  }

                  location.setLatitude(latitude);
                  location.setLongitude(longitude);
                  location.setAltitude(altitude);
                  location.setAccuracy(1.0f);
               }
               catch (Exception _e)
               {
                  Log.e(TAG, s, _e);
                  location = null;
               }
            }
         }
      }
      return location;
   }

   public int getPreviewBufferSize()
   //-------------------------------
   {
      if ( previewWidth < 0 || previewHeight < 0 || bufferSize < 0)
         return 0;
      int size = previewWidth * previewHeight;
      int count = -1;
      switch (fileFormat)
      {
         case RGBA:     count = 4; break;
         case RGB565:   count = 2; break;
         case RGB:      count = 3; break;
         case NV21:     count = ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
      }
      int length = size * count;
      if (length != bufferSize)
         throw new RuntimeException(String.format(Locale.US, "Header buffer size of %d does not match calculated buffer size of %d for size %dx%d",
                                                  bufferSize, length, previewWidth, previewHeight));
      return length;
   }

   /**
    * @see  android.hardware.Camera#setPreviewTexture
    * @param surfaceTexture
    */
   public void setPreviewTexture(SurfaceTexture surfaceTexture) { this.surfaceTexture = surfaceTexture;  }

   LocationThread locationHandlerThread = null;
   LocationListener locationListener = null;

   /**
    * Sets a LocationListener instance which will receive the dummy location from the recording.
    * @param locationListener
    */
   public void setLocationListener(LocationListener locationListener) { this.locationListener = locationListener; }

   /**
    * Sets a BearingListener instance which will receive the latest bearing.
    * @see to.augmented.reality.android.em.BearingListener
    * @param bearingListener
    */
   public void setBearingListener(BearingListener bearingListener) { this.bearingListener = bearingListener; }

   /**
    * Starts the preview of the recording.
    */
   public void startPreview()
   //------------------------
   {
      if ( (! isOpen) || (headers == null) || (headerFile == null) || (framesFile == null) )
         throw new RuntimeException("Cannot start preview without proper initialisation");
      if (locationHandlerThread != null)
         throw new RuntimeException("startPreview called when already previewing");
      float increment = getMapFloat(headers, "Increment", -1);
      if (increment <= 0)
         throw new RuntimeException("ERROR: Recording increment not found in header file");
      try
      {
         switch (renderMode)
         {
            case GLSurfaceView.RENDERMODE_WHEN_DIRTY:
               playbackThread = new DirtyPlaybackThread(context, this, framesFile, bufferSize, increment, callback,
                                                        isOneShot, bearingListener, bufferQueue,
                                                        orientationProviderType, fileFormat);
               break;
            case GLSurfaceView.RENDERMODE_CONTINUOUSLY:
               playbackThread = new ContinuousPlaybackThread(context, this, framesFile, bufferSize, increment, callback,
                                                             isOneShot, bearingListener, bufferQueue,
                                                             orientationProviderType, fileFormat);
               break;
         }

         playbackFuture = playbackExecutor.submit(playbackThread);
         if (locationListener != null)
         {
            locationHandlerThread = new LocationThread(Process.THREAD_PRIORITY_DEFAULT, this, context, locationListener);
            locationHandlerThread.start();
         }
      }
      catch (Exception e)
      {
         if (playbackThread != null)
         {
            playbackThread.mustStop = true;
            if (playbackThread.isStarted())
            {
               if (playbackFuture != null)
               {
                  try { playbackFuture.get(300, TimeUnit.MILLISECONDS); } catch (Exception _e) { }
                  if (!playbackFuture.isDone())
                     playbackFuture.cancel(true);
               }
            }
         }
         if (locationHandlerThread != null)
            locationHandlerThread.quit();
         locationHandlerThread = null;
      }
   }

   /**
    * Stops the recording preview.
    */
   public final void stopPreview()
   //-----------------------------
   {
      if (playbackThread != null)
      {
         playbackThread.mustStop = true;
         if (playbackThread.isStarted())
         {
            if (playbackFuture != null)
            {
               try { playbackFuture.get(300, TimeUnit.MILLISECONDS); } catch (Exception _e) { }
               if (!playbackFuture.isDone())
                  playbackFuture.cancel(true);
            }
         }
      }
      if (locationHandlerThread != null)
         locationHandlerThread.quit();
      locationHandlerThread = null;
   }

   /**
    * Starts review mode. In this mode instead of using the bearing obtained from the sensors the display bearing is
    * cycled between a start and end bearing to allow debugging AR applications without having to manually move the
    * device.
    * @param startBearing The start bearing
    * @param endBearing The end bearing.
    * @param pauseMs Number of milliseconds to pause between frames.
    * @param isRepeat <i>true></i> to repeatedly display review. The review will alternate between starting at
    *                 startBearing and ending at endBearing and vice-versa.
    * @param reviewable A callback class to alert client code at different points of the review. Can be null to not
    *                   receive any callbacks. @see Reviewable
    */
   public void startReview(float startBearing, float endBearing, int pauseMs, boolean isRepeat, Reviewable reviewable)
   //------------------------------------------------------------------------------------------------------------
   {
      if ( (playbackThread != null) && (playbackThread.isStarted()) )
         playbackThread.review(startBearing, endBearing, pauseMs, isRepeat, reviewable);
   }

   /**
    * Review callback interface.
    */
   public interface Reviewable
   //=========================
   {
      /**
       * Called before starting review
       */
      void onReviewStart();

      /**
       * Called before displaying the review frame for the specified bearing.
       * @param bearing
       */
      void onReview(float bearing);

      /**
       * Called after displaying the review frame for the specified bearing.
       * @param bearing
       */
      void onReviewed(float bearing);

      /**
       * Called when the review completes or is terminated.
       */
      void onReviewComplete();
   };

   public boolean isReviewing() { if ( (playbackThread != null) && (playbackThread.isStarted()) ) return playbackThread.isReviewing(); return false;}
   public float getReviewStartBearing() { if ( (playbackThread != null) && (playbackThread.isStarted()) ) return playbackThread.getReviewStartBearing(); return -1; }
   public float getReviewEndBearing() { if ( (playbackThread != null) && (playbackThread.isStarted()) ) return playbackThread.getReviewEndBearing(); return -1;}
   public float getReviewCurrentBearing() { if ( (playbackThread != null) && (playbackThread.isStarted()) ) return playbackThread.getReviewCurrentBearing(); return -1; }
   public int getReviewPause() { if ( (playbackThread != null) && (playbackThread.isStarted()) ) return playbackThread.getReviewPause(); return -1; }
   public void setReviewCurrentBearing(float bearing) { if ( (playbackThread != null) && (playbackThread.isStarted()) ) playbackThread.setReviewCurrentBearing(bearing); }
   public boolean isReviewRepeating() { if ( (playbackThread != null) && (playbackThread.isStarted()) ) return playbackThread.isReviewRepeating(); return false;}

   /**
    * Stops the review
    */
   public void stopReview()
   //----------------------
   {
      if ( (playbackThread != null) && (playbackThread.isStarted()) )
         playbackThread.stopReview();
   }

   /**
    * Return current preview state.
    */
   public boolean previewEnabled() { return isPreviewing; }

   /**
    * @see android.hardware.Camera#setPreviewCallback
    * @param callback
    */
   public final void setPreviewCallback(Camera.PreviewCallback callback)
   //------------------------------------------------------------------
   {
      this.callback = callback;
      isOneShot = false;
   }

   /**
    * @see android.hardware.Camera#setOneShotPreviewCallback
    * @param callback
    */
   public void setOneShotPreviewCallback(Camera.PreviewCallback callback)
   //--------------------------------------------------------------------
   {
      this.callback = callback;
      isOneShot = true;
   }

   /**
    * Sets the render mode for the recording playback. Can be one of GLSurfaceView.RENDERMODE_CONTINUOUSLY or
    * GLSurfaceView.RENDERMODE_WHEN_DIRTY. For GLSurfaceView.RENDERMODE_CONTINUOUSLY the preview callback is
    * continuously called even if there is no bearing change, while for GLSurfaceView.RENDERMODE_WHEN_DIRTY
    * the callback is only called when the bearing changes. If updating a OpenGL texture to display the preview
    * when using  continuous rendering, if flickering is encountered it may be due to the frame rate being to
    * high. Consider setting the frame rate using methods in @link android.hardware.Camera#Parameters.
    * @param renderMode GLSurfaceView.RENDERMODE_CONTINUOUSLY or GLSurfaceView.RENDERMODE_WHEN_DIRTY
    */
   public void setRenderMode(int renderMode)
   //---------------------------------------
   {
      switch (renderMode)
      {
         case GLSurfaceView.RENDERMODE_CONTINUOUSLY:
         case GLSurfaceView.RENDERMODE_WHEN_DIRTY:
            this.renderMode = renderMode;
            break;
         default:
            throw new RuntimeException("setRenderMode: renderMode must be GLSurfaceView.RENDERMODE_CONTINUOUSLY or GLSurfaceView.RENDERMODE_WHEN_DIRTY");
      }
   }

   /**
    * @see android.hardware.Camera#setPreviewCallbackWithBuffer
    * @param callback
    */
   public void setPreviewCallbackWithBuffer(Camera.PreviewCallback callback)
   //------------------------------------------------------------------------
   {
      this.callback = callback;
      isUseBuffer = true;
   }

   final static private int MAX_QUEUE_BUFFERS = 5;
   final ArrayBlockingQueue<byte[]> bufferQueue = new ArrayBlockingQueue<byte[]>(MAX_QUEUE_BUFFERS);

   /**
    * @see android.hardware.Camera#addCallbackBuffer
    * @param callbackBuffer
    */
   public void addCallbackBuffer(byte[] callbackBuffer)
   //--------------------------------------------------
   {
      if (callbackBuffer.length < bufferSize)
         throw new RuntimeException("addCallbackBuffer: Buffer must be size " + bufferSize);
      isUseBuffer = true;
      bufferQueue.offer(callbackBuffer);
   }

   @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
   public Allocation createPreviewAllocation(RenderScript rs, int usage) throws RSIllegalArgumentException
   //------------------------------------------------------------------------------------------------------
   {
      Type.Builder yuvBuilder = new Type.Builder(rs, Element.createPixel(rs, Element.DataType.UNSIGNED_8,
                                                                         Element.DataKind.PIXEL_YUV)
      );
      // Use YV12 for wide compatibility. Changing this requires also
      // adjusting camera service's format selection.
      yuvBuilder.setYuvFormat(ImageFormat.YV12);
      yuvBuilder.setX(previewWidth);
      yuvBuilder.setY(previewHeight);
      Allocation a = Allocation.createTyped(rs, yuvBuilder.create(), usage | Allocation.USAGE_IO_INPUT);
      return a;
   }

   /**
    *  <p>Create a {@link android.renderscript RenderScript}
    * {@link android.renderscript.Allocation Allocation} to use as a
    * destination of preview callback frames. Use
    * {@link #setPreviewCallbackAllocation setPreviewCallbackAllocation} to use
    * the created Allocation as a destination for camera preview frames.</p>
    * @see android.hardware.Camera createPreviewAllocation
    * This version allows setting NV21 or YV12 image format.
    *
    * @param rs The RenderScript context for this Allocation.
    * @param usage  Allocation usage
    * @param imageFormat ImageFormat.NV21 or ImageFormat.YV12.
    * @return a new YUV-type Allocation with dimensions equal to the current
    *   preview size.
    * @throws RSIllegalArgumentException
    */
   @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
   public Allocation createPreviewAllocation(RenderScript rs, int usage, int imageFormat) throws RSIllegalArgumentException
   //------------------------------------------------------------------------------------------------------
   {
      Type.Builder yuvBuilder = new Type.Builder(rs, Element.createPixel(rs, Element.DataType.UNSIGNED_8,
                                                                         Element.DataKind.PIXEL_YUV)
      );

      yuvBuilder.setYuvFormat(imageFormat);
      yuvBuilder.setX(previewWidth);
      yuvBuilder.setY(previewHeight);
      Allocation a = Allocation.createTyped(rs, yuvBuilder.create(),
                                            usage | Allocation.USAGE_IO_INPUT);
      return a;
   }

   public void setPreviewCallbackAllocation(Allocation previewAllocation) throws IOException
   {
      throw new UnsupportedOperationException("setPreviewCallbackAllocation not yet implemented");
   }

   /**
    * @see android.hardware.Camera#autoFocus
    * Not implemented by ARCamera. Delegates to Camera instance if it was supplied.
    * @param cb
    */
   public void autoFocus(Camera.AutoFocusCallback cb)
   //------------------------------------------------
   {
      if ( (camera != null) && (delegationType == DelegationTypes.ALL) )
         camera.autoFocus(cb);
   }

   /**
    * @see android.hardware.Camera#cancelAutoFocus
    * Not implemented by ARCamera. Delegates to Camera instance if it was supplied.
    */
   public void cancelAutoFocus()
   //---------------------------
   {
      if ( (camera != null) && (delegationType == DelegationTypes.ALL) )
         camera.cancelAutoFocus();
   }

   @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
   public void setAutoFocusMoveCallback(Camera.AutoFocusMoveCallback cb)
   //-------------------------------------------------------------------
   {
      if ( (camera != null) && (delegationType == DelegationTypes.ALL) )
         camera.setAutoFocusMoveCallback(cb);
   }

   /**
    * @see android.hardware.Camera#takePicture
    * Not implemented by ARCamera. Delegates to Camera instance if it was supplied.
    * @param shutter
    * @param raw
    * @param jpeg
    */
   public void takePicture(Camera.ShutterCallback shutter, Camera.PictureCallback raw, Camera.PictureCallback jpeg)
   //--------------------------------------------------------------------------------------------------------------
   {
      if ( (camera != null) && (delegationType == DelegationTypes.ALL) )
         camera.takePicture(shutter, raw, jpeg);
   }

   /**
    * @see android.hardware.Camera#takePicture
    * Not implemented by ARCamera. Delegates to Camera instance if it was supplied.
    * @param shutter
    * @param raw
    * @param postview
    * @param jpeg
    */
   public void takePicture(Camera.ShutterCallback shutter, Camera.PictureCallback raw, Camera.PictureCallback postview,
                           Camera.PictureCallback jpeg)
   //-----------------------------------------------------------------------------------------------------------------
   {
      if ( (camera != null) && (delegationType == DelegationTypes.ALL) )
         camera.takePicture(shutter, raw, postview, jpeg);
   }

   /**
    * @see android.hardware.Camera#startSmoothZoom
    * Not implemented by ARCamera. Delegates to Camera instance if it was supplied.
    * @param value
    */
   public void startSmoothZoom(int value)
   //------------------------------------
   {
      if ( (camera != null) && (delegationType == DelegationTypes.ALL) )
         camera.startSmoothZoom(value);
   }

   /**
    * @see android.hardware.Camera#stopSmoothZoom
    * Not implemented by ARCamera. Delegates to Camera instance if it was supplied.
     */
   public void stopSmoothZoom()
   //--------------------------
   {
      if ( (camera != null) && (delegationType == DelegationTypes.ALL) )
         camera.stopSmoothZoom();
   }

   /**
    * @see android.hardware.Camera#setDisplayOrientation
    * @param degrees
    */
   public void setDisplayOrientation(int degrees) { this.orientation = degrees; }

   /**
    * @see android.hardware.Camera#enableShutterSound
    * Not implemented by ARCamera. Delegates to Camera instance if it was supplied.
    * @param enabled
    * @return
    */
   @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
   public boolean enableShutterSound(boolean enabled)
   {
      if ( (camera != null) && (delegationType == DelegationTypes.ALL) )
         return camera.enableShutterSound(enabled);
      return true;
   }

   /**
    * @see android.hardware.Camera#setZoomChangeListener
    * Not implemented by ARCamera. Delegates to Camera instance if it was supplied.
    * @param listener
    */
   public void setZoomChangeListener(Camera.OnZoomChangeListener listener)
   //---------------------------------------------------------------------
   {
      if ( (camera != null) && (delegationType == DelegationTypes.ALL) )
         camera.setZoomChangeListener(listener);
   }

   /**
    * @see android.hardware.Camera#setFaceDetectionListener
    * Not implemented by ARCamera. Delegates to Camera instance if it was supplied.
    * @param listener
    */
   public final void setFaceDetectionListener(Camera.FaceDetectionListener listener)
   {
      if ( (camera != null) && (delegationType == DelegationTypes.ALL) )
         camera.setFaceDetectionListener(listener);
   }

   /**
    * @see android.hardware.Camera#startFaceDetection
    * Not implemented by ARCamera. Delegates to Camera instance if it was supplied.
    */
   public void startFaceDetection()
   //------------------------------
   {
      if ( (camera != null) && (delegationType == DelegationTypes.ALL) )
         camera.startFaceDetection();
   }

   /**
    * @see android.hardware.Camera#stopFaceDetection
    * Not implemented by ARCamera. Delegates to Camera instance if it was supplied.
    */
   public void stopFaceDetection()
   //-----------------------------
   {
      if ( (camera != null) && (delegationType == DelegationTypes.ALL) )
         camera.stopFaceDetection();
   }

   /**
    * @see android.hardware.Camera#setErrorCallback
    * Also delegates to Camera instance if it was supplied and delegationType is ALL.
    */
   public final void setErrorCallback(Camera.ErrorCallback callback)
   //---------------------------------------------------------------
   {
      errorCallback = callback;
      if ( (camera != null) && (delegationType == DelegationTypes.ALL) )
         camera.setErrorCallback(callback);
   }

   public int getFrameRate() { return previewFrameRate; }

   protected Camera.Size stealSize(int width, int height)
   //----------------------------------------------------
   {
      Camera.Size sz = null;
      try
      {
//         Constructor<Camera> constructor;
//         constructor = Camera.class.getDeclaredConstructor(null);
//         constructor.setAccessible(true);
//         Camera cam = constructor.newInstance();
         Method method = Camera.class.getDeclaredMethod("getEmptyParameters", new Class[0]); //undocumented static method
         Camera.Parameters params = (Camera.Parameters) method.invoke(null);
         method = Camera.Parameters.class.getDeclaredMethod("strToSize", String.class); // private Camera.Parameters method
         method.setAccessible(true);
         String s = String.format(Locale.US, "%dx%d", width, height);
         sz = (Camera.Size) method.invoke(params, s);
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
      }
      return sz;
   }

   protected Camera.Parameters getOrStealParameters()
   //------------------------------------------------
   {
      Camera.Parameters parameters = null;
      if (camera != null)
         parameters = camera.getParameters();
      if (parameters == null)
      {
         try
         {
            //         Constructor<Camera> constructor;
            //         constructor = Camera.class.getDeclaredConstructor(null);
            //         constructor.setAccessible(true);
            //         Camera cam = constructor.newInstance();
            Method method = Camera.class.getDeclaredMethod("getEmptyParameters", new Class[0]); //undocumented static method
            parameters = (Camera.Parameters) method.invoke(null);
         }
         catch (Exception e)
         {
            Log.e(TAG, "", e);
         }
      }
      return parameters;
   }

   static public int getMapInt(Map<String, String> m, String k, int def)
   //---------------------------------------------------
   {
      String s = m.get(k);
      if (s == null)
         return def;
      try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
   }

   static public float getMapFloat(Map<String, String> m, String k, float def)
   //------------------------------------------------------------------------
   {
      String s = m.get(k);
      if (s == null)
         return def;
      try { return Float.parseFloat(s.trim()); } catch (NumberFormatException e) { return def; }
   }
}
