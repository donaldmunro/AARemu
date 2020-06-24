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
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.media.ImageReader;
import android.media.ImageWriter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.view.Surface;
import to.augmented.reality.android.em.free.PlaybackThreadFree;
import to.augmented.reality.android.em.three60.PlaybackThread360;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Semaphore;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class ARCameraDevice extends AbstractARCamera implements AutoCloseable, ARCameraInterface
//============================================================================================
{
   final static private String TAG = ARCameraDevice.class.getSimpleName();

   public interface ARCaptureCallback { void onPreviewFrame(byte[] data); }

   private CameraDevice delegateCamera = null;

   private ARCameraDevice.ARCaptureCallback cameraCallback;

   public void setPreviewCallback(Object callback) { this.cameraCallback = (ARCaptureCallback) callback; }

   public void setPreviewCallbackWithBuffer(Object callback) { this.cameraCallback = (ARCaptureCallback) callback; isUseBuffer = true; }

   /**
    * Creates an ARCameraDevice instance.
    * @param context A Context instance. Can be null for RecordingType.FREE recordings if setDelegateCamera is not used.
    * @param cameraId The camera id to use.
    * @param headerFile The header file of the recording.
    * @throws IOException
    */
   public ARCameraDevice(Context context, String cameraId, File headerFile) throws IOException
   //----------------------------------------------------------------------------------------------------------
   {
      this(context, cameraId, headerFile, null, null, null, null, false);
   }

   /**
    * Creates an ARCameraDevice instance.
    * @param context A Context instance. Can be null for RecordingType.FREE recordings if setDelegateCamera is not used.
    * @param cameraId The camera id to use.
    * @param headerFile The header file of the recording.
    * @param framesFile The frames file or null if the frames file is to be inferred from the header.
    * @param orientationFile A file containing orientation data or null for no orientation data or if inferred from
    *                        the header file (Applies only to RecordingType.FREE recordings).
    * @param locationFile A file containing location data or null for no location data  or if inferred from
    *                        the header file (Applies only to RecordingType.FREE recordings).
    * @param  type The Recording type or null if it is to be determined from the header file
    * @param isRepeat Continue cycling the recording until cancelled
    * @throws IOException
    */
   public ARCameraDevice(Context context, String cameraId, File headerFile, File framesFile, File orientationFile,
                         File locationFile, RecordingType type, boolean isRepeat) throws IOException
   //--------------------------------------------------------------------------------------------------
   {
      super(context);
      this.id = cameraId;
      recordingType = null;
      setFiles(headerFile, framesFile, orientationFile, locationFile);
      if (recordingType == null)
         recordingType  = type;
      this.isRepeat = isRepeat;
      ARCameraManager manager = ARCameraManager.get(context, headerFile, false);
      if (manager != null)
      {
         ARCameraDevice existingCamera = manager.getCamera(cameraId);
         if (existingCamera != null)
         {
            Log.w(TAG, "ARCameraDevice constructor: Camera Id" + cameraId +
                  " has already been added to ARCameraManager. Closing and recreating.");
            try { existingCamera.close(); } catch (Exception e) { Log.e(TAG, "", e); }
         }
      }
   }

   static public ARCameraDevice create(Context context, String cameraId, File headerFile, File framesFile,
                                       File orientationFile, File locationFile, RecordingType type, boolean isRepeat)
         throws IOException
   //------------------------------------------------------------------------------------------------------------------
   {
      ARCameraDevice camera = new ARCameraDevice(context, cameraId, headerFile, framesFile, orientationFile,
                                                 locationFile, type, isRepeat);
      ARCameraManager manager = ARCameraManager.get(context, headerFile, false);
      if (manager != null)
      {
         ARCameraDevice existingCamera = manager.getCamera(cameraId);
         if (existingCamera != null)
         {
            Log.w(TAG, "ARCameraDevice constructor: Camera Id" + cameraId +
                  " has already been added to ARCameraManager. Closing and recreating.");
            try { existingCamera.close(); } catch (Exception e) { Log.e(TAG, "", e); }
         }
         manager.addCamera(cameraId, camera);
      }
      camera.isOpen = true;
      return camera;
   }

   public CameraDevice getDelegateCamera() { return delegateCamera; }

   /**
    * Sets the delegate camera.
    * @param cameraId The id of the 'real' camera or null if the camera parameter is not null)
    * @param camera The 'real' camera to use as the delegate camera or null to open the camera using the cameraId
    *               parameter.
   */
   public void setDelegateCamera(String cameraId, CameraDevice camera)
   //------------------------------------------------------------------
   {
      if (context == null)
         throw new RuntimeException("Requires a non-null context in ARCameraDevice");
      if (camera == null)
      {
         if (cameraId == null)
            throw new RuntimeException("One or both of cameraId or camera must be non-null");
         CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
         String[] cameras;
         try { cameras = manager.getCameraIdList(); } catch (Exception e) { cameras = null; }
         if (cameras != null)
         {
            final HandlerThread t = new HandlerThread("TempHandler");
            t.start();
            try
            {
               Handler h =  new Handler(t.getLooper());
               final Semaphore mutex = new Semaphore(1);
               final CameraDevice.StateCallback callback = new CameraDevice.StateCallback()
               {
                  @Override public void onOpened(CameraDevice camera)
                  //-------------------------------------------------
                  {
                     delegateCamera = camera;
                     mutex.release();
                  }

                  @Override public void onDisconnected(CameraDevice camera) { mutex.release(); }

                  @Override public void onError(CameraDevice camera, int error) { mutex.release(); }
               };
               for (String cid : cameras)
               {
                  if (cid.trim().equals(cameraId.trim()))
                     continue;
                  try
                  {
                     mutex.acquire();
                     manager.openCamera(cid, callback, h);
                  }
                  catch (CameraAccessException e)
                  {
                     mutex.release();
                  }
                  catch (InterruptedException e)
                  {
                     mutex.release();
                     throw new RuntimeException(e);
                  }
                  try { mutex.acquire(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                  mutex.release();
                  if (delegateCamera != null)
                     break;
               }
            }
            finally
            {
               t.quit();
            }
         }
      }
      else
         delegateCamera = camera;
   }

   @Override
   public void close() throws Exception
   //-----------------------------------
   {
      if (delegateCamera != null)
         delegateCamera.close();
      delegateCamera = null;
   }

   @Override protected void onSetCallbackFree(PlaybackThreadFree playbackThread)
   //---------------------------------------------------------------------------
   {
      playbackThread.setCameraListener(cameraCallback);
      playbackThread.setOrientationListener(orientationCallback);
      playbackThread.setLocationListener(locationListener);
   }

   @Override
   protected void onSetCallback360(PlaybackThread360 playbackThread)
   //---------------------------------------------------------------
   {
      playbackThread.setCameraListener(cameraCallback);
      if (bearingListener != null)
         playbackThread.setBearingListener(bearingListener);
   }

   public CaptureRequest.Builder createCaptureRequest(int templatePreview)
   //--------------------------------------------------------------------
   {
      CaptureRequest.Builder builder = null;
      if (delegateCamera != null)
         try { builder = delegateCamera.createCaptureRequest(templatePreview); } catch (Exception e) { builder = null; }
      return builder;
   }

   public void createCaptureSession(List<Surface> surfaces, CameraCaptureSession.StateCallback cameraStatusCallback,
                                    Handler cameraHandler)  throws CameraAccessException
   //---------------------------------------------------------------------------------------------------------------
   {
      ARCameraCaptureSession captureSession = new ARCameraCaptureSession();
      if (cameraStatusCallback != null)
         cameraStatusCallback.onConfigured(captureSession);
   }

   public ARCameraCaptureSession createCaptureSessionDirect() { return new ARCameraCaptureSession(); }

   @Override
   public void startPreview()
   //-----------------------
   {
      if (cameraCallback == null)
         Log.e(TAG, "ERROR: Camera frame callback is null for ARCameraDevice");
      if ( (orientationCallback == null) && (recordingType == RecordingType.FREE) )
         Log.w(TAG, "WARNING: Orientation callback is null for ARCameraDevice");
      super.startPreview();
   }

   public class ARCameraCaptureSession extends CameraCaptureSession
   //==============================================================
   {
      @Override public CameraDevice getDevice() { return delegateCamera; }


      @Override public void prepare(@NonNull Surface surface) throws CameraAccessException { }

      @Override public void finalizeOutputConfigurations(List<OutputConfiguration> outputConfigs) throws CameraAccessException
      {

      }

      @Override
      public int capture(CaptureRequest request, CaptureCallback listener, Handler handler) throws CameraAccessException
      {
         return 0;
      }

      @Override
      public int captureBurst(List<CaptureRequest> requests, CaptureCallback listener, Handler handler) throws CameraAccessException
      {
         return 0;
      }

      @Override
      public int setRepeatingRequest(CaptureRequest request, CaptureCallback listener, Handler handler) throws CameraAccessException
      //-----------------------------------------------------------------------------------------------------------------------------
      {
         if (cameraCallback == null)
            throw new RuntimeException("ARCameraDevice.ARCaptureCallback not set in ARCameraDevice");
         startPreview();
         return 0;
      }

      public int setRepeatingRequestAR(CaptureRequest request, ARCameraDevice.ARCaptureCallback callback, Handler handler)
            throws CameraAccessException
      //------------------------------------------------------------------------------------------------------------------------------
      {
         if (callback != null)
            cameraCallback = callback;
         startPreview();
         return 0;
      }

      @Override
      public int setRepeatingBurst(List<CaptureRequest> requests, CaptureCallback listener, Handler handler) throws CameraAccessException
      {
         return 0;
      }

      @Override public void stopRepeating() throws CameraAccessException { stopPreview(); }

      @Override
      public void abortCaptures() throws CameraAccessException { stopRepeating(); }

      /**
       * Return if the application can submit reprocess capture requests with this camera capture
       * session.
       *
       * @return {@code true} if the application can submit reprocess capture requests with this
       * camera capture session. {@code false} otherwise.
       * @see CameraDevice#createReprocessableCaptureSession
       */
      @Override
      public boolean isReprocessable()
      {
         return false;
      }

      /**
       * Get the input Surface associated with a reprocessable capture session.
       * <p>
       * <p>Each reprocessable capture session has an input {@link Surface} where the reprocess
       * capture requests get the input images from, rather than the camera device. The application
       * can create a {@link ImageWriter ImageWriter} with this input {@link Surface}
       * and use it to provide input images for reprocess capture requests. When the reprocessable
       * capture session is closed, the input {@link Surface} is abandoned and becomes invalid.</p>
       *
       * @return The {@link Surface} where reprocessing capture requests get the input images from. If
       * this is not a reprocess capture session, {@code null} will be returned.
       * @see CameraDevice#createReprocessableCaptureSession
       * @see ImageWriter
       * @see ImageReader
       */
      @Nullable
      @Override
      public Surface getInputSurface()
      {
         return null;
      }

      @Override
      public void close() { try { stopRepeating(); } catch (Exception _e) {} }
   }

   static public abstract class StateCallback extends CameraDevice.StateCallback
   //===========================================================================
   {
      public abstract void onOpened(ARCameraDevice camera);

      @Override public void onOpened(CameraDevice camera) { }

      public void onClosed(ARCameraDevice camera) { }

      public abstract void onDisconnected(ARCameraDevice camera);

      @Override public void onDisconnected(CameraDevice camera) { }

      public abstract void onError(ARCameraDevice camera, int error);

      @Override public void onError(CameraDevice camera, int error) { }
   }
//   public class CameraCharacteristics extends CameraMetadata<android.hardware.camera2.CameraCharacteristics.Key<?>>
//   {
//
//      protected CameraCharacteristics() { super();  }
//
//   }
}
