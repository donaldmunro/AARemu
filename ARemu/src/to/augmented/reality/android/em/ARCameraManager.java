package to.augmented.reality.android.em;

import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class ARCameraManager
//==========================
{
   static private final String TAG = "ARCameraManager";

   static private Map<String, CameraCharacteristics> cameras = null;

   private final Context context;
   private final Object mLock = new Object();
   private File headerFile;
   private File framesFile;
   private Handler handler;
   private ARCameraDevice.ARCaptureCallback callback;

   public ARCameraManager(Context context) { this(context, null, null, null); }

   public ARCameraManager(Context context, ARCameraDevice.ARCaptureCallback callback, File headerFile, File framesFile)
   //-------------------------------------------------------------------------------------------------------------------
   {
      this.context = context;
      this.callback = callback;
      this.headerFile = headerFile;
      this.framesFile = framesFile;
   }

   public String[] getCameraIdList()
   //-------------------------------
   {
      if (cameras == null)
      {
         cameras = new HashMap<>();
         CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
         String[] acamera;
         try
         {
            acamera = manager.getCameraIdList();
         }
         catch (Exception e)
         {
            Log.e("ARCameraManager", "", e);
            acamera = null;
         }
         if ( (acamera == null) || (acamera.length == 0) )
         {
            cameras.put("0", null);
            return cameras.keySet().toArray(new String[0]);
         }
         for (String cid : acamera)
         {
            int id;
            try { id = Integer.parseInt(cid.trim()); } catch (Exception _e) { continue; }
            try
            {
               CameraCharacteristics characteristics = manager.getCameraCharacteristics(cid);
               cameras.put(cid, characteristics);
            }
            catch (Throwable _e)
            {
               Log.e("ARCameraManager", "", _e);
            }
         }
      }
      return cameras.keySet().toArray(new String[0]);
   }

   public void registerAvailabilityCallback(CameraManager.AvailabilityCallback callback, Handler handler) { }

   public void unregisterAvailabilityCallback(CameraManager.AvailabilityCallback callback) { }

   public CameraCharacteristics getCameraCharacteristics(String cameraId) throws CameraAccessException
   //--------------------------------------------------------------------------------------------------
   {
      if (cameras == null)
         getCameraIdList();
      return cameras.get(cameraId);
   }

   public void openCamera(final String cameraId, final ARCameraDevice.StateCallback callback, Handler handler)
         throws CameraAccessException
   //----------------------------------------------------------------------------------------------------
   {
      if (this.callback == null)
      {
         Log.e(TAG, "Use constructor with ARCaptureCallback when opening camera");
         throw new RuntimeException("Use constructor with ARCaptureCallback when opening camera");
      }
      if (! cameras.containsKey(cameraId))
      {
         callback.onError((ARCameraDevice) null, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
         return;
      }
      CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
      int cid = Integer.parseInt(cameraId.trim());
      final ARCameraDevice camera;
      try
      {
         camera = new ARCameraDevice(context, cid, this.callback, headerFile, framesFile);
      }
      catch (Exception e)
      {
         throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "ARCameraDevice", e);
      }

      if (handler == null)
      {
         if (Looper.myLooper() != null)
            handler = new Handler();
      }
      if (camera.isOpen)
      {
         if (handler != null)
         {
            handler.post(new Runnable()
            {
               @Override public void run() { callback.onError(camera, CameraDevice.StateCallback.ERROR_CAMERA_IN_USE); }
            });
         }
         else
            callback.onError(camera, CameraDevice.StateCallback.ERROR_CAMERA_IN_USE);
      }
      if (handler != null)
      {
         final Handler hh = handler;
         CameraDevice.StateCallback localCallback = new CameraDevice.StateCallback()
         {
            @Override
            public void onOpened(final CameraDevice cam)
            //------------------------------------------
            {
               camera.setDelegateCamera(cameraId, cam);
               camera.isOpen = true;
               hh.post(new Runnable() { @Override public void run() { callback.onOpened(camera); callback.onOpened(cam); } });
            }

            @Override public void onDisconnected(CameraDevice cam) { try { camera.close(); } catch (Exception _e) {} }

            @Override
            public void onError(final CameraDevice cam, final int error)
            //----------------------------------------------------------
            {
               camera.setDelegateCamera(cameraId, null);
               camera.isOpen = true;
            }
         };
         manager.openCamera(cameraId, localCallback, handler);
      }
      else
      {
         camera.isOpen = true;
         if (handler != null)
         {
            handler.post(new Runnable() {
               @Override public void run() { callback.onOpened(camera); }
            });
         }
      }
   }
}
