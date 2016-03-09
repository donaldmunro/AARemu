package to.augmented.reality.android.em;

import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class ARCameraDevice extends ARCameraCommon implements AutoCloseable
//==========================================================================
{

   private CameraDevice delegateCamera = null;
   private final ARCaptureCallback callback;

   public ARCameraDevice(Context context, int cameraId, ARCaptureCallback callback, File headerFile, File framesFile)
          throws IOException
   //--------------------------------------------------------------------------------------------------
   {
      super(context);
      this.id = cameraId;
      this.callback = callback;
      setFiles(headerFile, framesFile);
   }

   void setDelegateCamera(String cameraId, CameraDevice camera)
   //----------------------------------------------------------
   {
      if (camera == null)
      {
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
      cameraStatusCallback.onConfigured(captureSession);
   }

   public class ARCameraCaptureSession extends CameraCaptureSession
   //=======================================================
   {

      @Override public CameraDevice getDevice() { return delegateCamera; }

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
      {
         return 0;
      }

      public int setRepeatingRequestAR(CaptureRequest request, CaptureCallback listener, Handler handler) throws CameraAccessException
      //------------------------------------------------------------------------------------------------------------------------------
      {
         float increment = getMapFloat(headers, "Increment", -1);
         if (increment <= 0)
            throw new RuntimeException("ERROR: Recording increment not found in header file");
         try
         {
            switch (renderMode)
            {
               case GLSurfaceView.RENDERMODE_WHEN_DIRTY:
                  playbackThread = new DirtyPlaybackThread(context, ARCameraDevice.this, framesFile, bufferSize,
                                                           increment, callback, isOneShot, bearingListener, bufferQueue,
                                                           orientationProviderType, fileFormat);
                  break;
               case GLSurfaceView.RENDERMODE_CONTINUOUSLY:
                  playbackThread = new ContinuousPlaybackThread(context, ARCameraDevice.this, framesFile, bufferSize,
                                                                increment, callback, isOneShot, bearingListener, bufferQueue,
                                                                orientationProviderType, fileFormat);
                  break;
            }

            playbackFuture = playbackExecutor.submit(playbackThread);
            if (locationListener != null)
            {
               locationHandlerThread = new LocationThread(Process.THREAD_PRIORITY_LESS_FAVORABLE, ARCameraDevice.this,
                                                          context, locationListener);
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
         return 0;
      }

      @Override
      public int setRepeatingBurst(List<CaptureRequest> requests, CaptureCallback listener, Handler handler) throws CameraAccessException
      {
         return 0;
      }

      @Override
      public void stopRepeating() throws CameraAccessException
      {

      }

      @Override
      public void abortCaptures() throws CameraAccessException
      {

      }

      @Override
      public void close()
      {

      }
   }

   public interface ARCaptureCallback { void onPreviewFrame(byte[] data); }

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
