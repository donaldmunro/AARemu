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

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Process;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RSIllegalArgumentException;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.util.Log;
import android.view.SurfaceHolder;
import to.augmented.reality.android.common.math.QuickFloat;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
public class ARCamera extends ARCameraCommon
//===========================================
{
   private static final String KEY_PREVIEW_SIZE = "preview-size";
   private static final String KEY_PREVIEW_FORMAT = "preview-format";
   private static final String KEY_PREVIEW_FRAME_RATE = "preview-frame-rate";
   private static final String KEY_PREVIEW_FPS_RANGE = "preview-fps-range";
   private static final String KEY_ROTATION = "rotation";
   private static final String KEY_FOCAL_LENGTH = "focal-length";
   private static final String KEY_HORIZONTAL_VIEW_ANGLE = "horizontal-view-angle";
   private static final String KEY_VERTICAL_VIEW_ANGLE = "vertical-view-angle";

   private static final String SUPPORTED_VALUES_SUFFIX = "-values";

   // Formats for setPreviewFormat and setPictureFormat.
   private static final String PIXEL_FORMAT_YUV422SP = "yuv422sp";
   private static final String PIXEL_FORMAT_YUV420SP = "yuv420sp";
   private static final String PIXEL_FORMAT_YUV422I = "yuv422i-yuyv";
   private static final String PIXEL_FORMAT_YUV420P = "yuv420p";
   private static final String PIXEL_FORMAT_RGB565 = "rgb565";
   private static final String PIXEL_FORMAT_JPEG = "jpeg";

   final static protected Map<Integer, ARCamera> INSTANCES = new HashMap<Integer, ARCamera>();
   static protected int ARCAMERA_COUNT = 0;

   protected Camera.PreviewCallback callback = null;

   public static int getNumberOfCameras() { return ARCAMERA_COUNT + Camera.getNumberOfCameras(); }

   private Camera camera = null;

   /**
    * Creates an ARCamera instance and adds it to the defined ARCamera instances map. The instance can subsequently
    * be accessed using open() or open(int id). The Camera instance is used for delegation and to obtain an internal
    * Camera.Parameter instance.
    * @param context A Context instance used to access sensor classes.
    * @param cameraId The camera id to use. It may be the original id of the accompanying Camera instance or
    *                 a id greater than the last available hardware camera if separate ARCamera instances are desired.
    *                 If it is the original id or the id of an existing hardware camera then the emulated camera
    *                 replaces the hadrware camera when opened using ARCamera.open(id).
    * @param camera A Camera instance is used for delegation and to obtain an internal
    *               Camera.Parameter instance as Camera.Size has a private constructor. Can be null in which case
    *               reflection is used to obtain a Camera.Parameter.
    * @throws Exception
    */
   public ARCamera(Context context, int cameraId, Camera camera)
   //-----------------------------------------------------------
   {
      super(context);
      this.camera = camera;
      init(cameraId, camera);
   }

   /**
    * Creates an ARCamera instance and adds it to the defined ARCamera instances map. The instance can subsequently
    * be accessed using open() or open(int id). The Camera instance is used for delegation and to obtain an internal
    * Camera.Parameter instance.
    * @param context A Context instance used to access sensor classes.
    * @param cameraId The camera id to use. It may be the original id of the accompanying Camera instance or
    *                 a id greater than the last available hardware camera if separate ARCamera instances are desired.
    *                 If it is the original id or the id of an existing hardware camera then the emulated camera
    *                 replaces the hadrware camera when opened using ARCamera.open(id).
    * @param camera A Camera instance is used for delegation and to obtain an internal
    *               Camera.Parameter instance as Camera.Size has a private constructor. Can be null in which case
    *               reflection is used to obtain a Camera.Parameter.
    * @param headerFile The recording header file of the 360 degree recording.
    * @param framesFile The recording frames file of the 360 degree recording.
    * @throws Exception
    */
   public ARCamera(Context context, int cameraId, Camera camera, File headerFile, File framesFile)
         throws IOException
   //------------------------------------------------------------------------------------------
   {
      super(context);
      this.camera = camera;
      init(cameraId, camera);
      if ( (headerFile != null) && (framesFile != null) )
         setFiles(headerFile, framesFile);
   }

   /**
    * Creates an ARCamera instance and adds it to the defined ARCamera instances map. The instance can subsequently
    * be accessed using open() or open(int id).
    * @param context A Context instance used to access sensor classes.
    * @param cameraId The camera id to use. It can be a id of an existing hardware camera or an id greater than the
    *                 last available hardware camera if separate ARCamera instances are desired.
    *                 If it is the id of an existing hardware camera then the emulated camera
    *                 replaces the hadrware camera when opened using ARCamera.open(id).
    * @throws Exception
    */
   public ARCamera(Context context, int cameraId) { super(context); init(cameraId, null); }

   /**
    * Creates an ARCamera instance and adds it to the defined ARCamera instances map. The instance can subsequently
    * be accessed using open() or open(int id).
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
      super(context);
      init(cameraId, null);
      if ( (headerFile != null) && (framesFile != null) )
         setFiles(headerFile, framesFile);
   }


   final private void init(int cameraId, Camera camera)
   //------------------------------------------------------------------------------------------
   {
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
            INSTANCES.put(i, null);
      }
      INSTANCES.put(cameraId, this);
      this.id = cameraId;
      if (camera != null)
      {
         Camera.Parameters parameters = camera.getParameters();
         if (parameters != null)
            setFrom(parameters);
      }
   }

   /**
    * @return An ARCamera instance.
    */
   public static ARCamera open()
   //---------------------------
   {
      ARCameraCommon instance = INSTANCES.get(0);
      if ( (instance == null) || (! (instance instanceof ARCamera)) )
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
      return (ARCamera) instance;
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
      ARCameraCommon instance = INSTANCES.get(id);
      if (instance == null)
      {
         Camera C = Camera.open(id);
         if (C != null)
            return C;
         else
            throw new RuntimeException("First create an ARCamera instance using a ARCamera constructor");
      }
      else if (instance instanceof ARCamera)
      {
         instance.isOpen = true;
         return instance;
      }
      else
         throw new RuntimeException(id + " is an ARCameraDevice not an ARCamera instance.");
   }

   public static void getCameraInfo(int cameraId, Camera.CameraInfo cameraInfo)
   //-------------------------------------------------------------------------
   {
      ARCameraCommon arcamera  = INSTANCES.get(cameraId);
      if ( (arcamera != null) && (arcamera instanceof ARCamera) )
      {
         cameraInfo.facing = Camera.CameraInfo.CAMERA_FACING_BACK;
         cameraInfo.canDisableShutterSound = true;
         cameraInfo.orientation = 0;
      }
      else Camera.getCameraInfo(cameraId, cameraInfo);
   }

   public void reconnect()
   //---------------------
   {
      if (camera != null)
      {
         try
         {
            camera.reconnect();
            isOpen = true;
         }
         catch (Exception e)
         {
            Log.e(TAG, "", e);
         }
      }
      else
         isOpen = true;
   }

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
      if ( (previewWidth >= 0) && (previewHeight >= 0) )
         parameters.setPreviewSize(previewWidth, previewHeight);
      if ( ( previewFrameRateMin >= 0) && (previewFrameRate >= 0) )
         parameters.setPreviewFpsRange(previewFrameRateMin, previewFrameRate);
      else if (previewFrameRate >= 0)
         parameters.setPreviewFpsRange(10000, previewFrameRate);
      if (previewFrameRate >= 0)
         parameters.setPreviewFrameRate(previewFrameRate/1000);
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
    * @see Camera#startPreview()
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
            locationHandlerThread = new LocationThread(Process.THREAD_PRIORITY_LESS_FAVORABLE, this, context, locationListener);
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
    * @see android.hardware.Camera#setPreviewCallbackWithBuffer
    * @param callback
    */
   public void setPreviewCallbackWithBuffer(Camera.PreviewCallback callback)
   //------------------------------------------------------------------------
   {
      this.callback = callback;
      isUseBuffer = true;
   }

   @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
   public Allocation createPreviewAllocation(RenderScript rs, int usage) throws RSIllegalArgumentException
   //------------------------------------------------------------------------------------------------------
   {
      Type.Builder yuvBuilder = new Type.Builder(rs, Element.createPixel                           (rs, Element.DataType.UNSIGNED_8,
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
         try { camera.release(); } catch (Exception e) { Log.e(TAG, "", e); }
   }

   public void close() throws Exception { release(); }

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



   /**
    * Sets the delegate Camera parameters.
    * @param params
    */
   public void setDelegateParameters(Camera.Parameters params) { if (camera != null) camera.setParameters(params);}

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
      if ( (previewWidth >= 0) && (previewHeight >= 0) )
         parameters.setPreviewSize(previewWidth, previewHeight);
      int fps = parameters.getPreviewFrameRate()*1000;
      int[] range = new int[2];
      parameters.getPreviewFpsRange(range);
      if (fps != previewFrameRate)
      {
         checkFPSRange(fps);
         range[1] = previewFrameRateMin = previewFrameRate = fps;
      }
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
      if (! QuickFloat.equals(parameters.getFocalLength(), focalLength, 0.0001f))
         focalLength = parameters.getFocalLength();
      if (! QuickFloat.equals(parameters.getHorizontalViewAngle(), fovx, 0.0001f))
         fovx = parameters.getHorizontalViewAngle();
      if (! QuickFloat.equals(parameters.getVerticalViewAngle(), fovy, 0.0001f))
         fovy = parameters.getVerticalViewAngle();
   }

}
