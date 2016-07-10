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
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class ARCameraManager
//==========================
{
   static private final String TAG = "ARCameraManager";

   static private Map<String, ARCameraCharacteristics> cameraCharacteristics = null;

   final private Map<String, ARCameraDevice> cameras = new HashMap<>();
   private final Context context;
   private final Object mLock = new Object();
   private final File orientationFile;
   private final File locationFile;
   public boolean isDelegateCameras = false;
   private File headerFile;
   private File framesFile;

   static private Map<File, ARCameraManager> MANAGERS = new HashMap<>();

   /**
    * Get an existing ARCameraManager instance.
    * @param headerFile The recording header file
    */
   synchronized static public ARCameraManager lookup(File headerFile)
   //--------------------------------------------------------------------------------------------------
   {
      File f;
      try { f = headerFile.getCanonicalFile(); } catch (IOException e) { f = headerFile.getAbsoluteFile(); }
      return MANAGERS.get(f);
   }

   /**
    * Get an existing or Create an ARCameraManager instance.
    * @param context A Context instance (can be null if isDelegateCameras is false). The context should exist for the
    *                duration that the CamameraManager is used eg a main activity.
    * @param headerFile The recording header file
    * @param isDelegateCameras
    */
   synchronized static public ARCameraManager get(Context context, File headerFile, boolean isDelegateCameras)
   //--------------------------------------------------------------------------------------------------
   {
      return get(context, headerFile, null, null, null, isDelegateCameras);
   }

   /**
    * Get an existing or Create an ARCameraManager instance.
    * @param context A Context instance (can be null if isDelegateCameras is false). The context should exist for the
    *                duration that the CamameraManager is used eg a main activity.
    * @param headerFile The recording header file
    * @param framesFile The frames file or null if the frames file is to be inferred from the header.
    * @param orientationFile A file containing orientation data or null for no orientation data or if inferred from
    *                        the header file (Applies only to RecordingType.FREE recordings).
    * @param locationFile A file containing location data or null for no location data  or if inferred from
    *                        the header file (Applies only to RecordingType.FREE recordings).
    * @param isDelegateCameras
    */
   synchronized static public ARCameraManager get(Context context, File headerFile, File framesFile, File orientationFile,
                                           File locationFile, boolean isDelegateCameras)
   //---------------------------------------------------------------------------------------------------------------
   {
      File f;
      try { f = headerFile.getCanonicalFile(); } catch (IOException e) { f = headerFile.getAbsoluteFile(); }
      ARCameraManager manager = MANAGERS.get(f);
      if (manager == null)
      {
         manager = new ARCameraManager(context, headerFile, framesFile, orientationFile, locationFile, isDelegateCameras);
         MANAGERS.put(f, manager);
      }
      return manager;
   }

   private ARCameraManager(Context context, File headerFile, File framesFile, File orientationFile, File locationFile,
                          boolean isDelegateCameras)
   //---------------------------------------------------------------------------------------------------------------
   {
      this.context = context;
      this.headerFile = headerFile;
      this.framesFile = framesFile;
      this.orientationFile = orientationFile;
      this.locationFile = locationFile;
      this.isDelegateCameras = isDelegateCameras;
   }

   public String[] getCameraIdList()
   //-------------------------------
   {
      if (cameraCharacteristics != null)
         cameraCharacteristics.clear();
      Map<String, String> headers = null;
      try { headers = AbstractARCamera.parseHeader(headerFile); } catch (Exception _e) { headers = null; }
      int width =-1, height = -1;
      if (headers != null)
      {
         try { width = Integer.parseInt(headers.get("PreviewWidth")); } catch (Exception e) { width = -1; }
         try { height = Integer.parseInt(headers.get("PreviewHeight")); } catch (Exception e) { height = width = -1; }
      }
      cameraCharacteristics = new HashMap<>();
      if ( (isDelegateCameras) && (context != null) )
      {
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
            cameraCharacteristics.put("0", new ARCameraCharacteristics(width, height, null));
            return cameraCharacteristics.keySet().toArray(new String[0]);
         }
         for (String cid : acamera)
         {
            int id;
            try { id = Integer.parseInt(cid.trim()); } catch (Exception _e) { continue; }
            try
            {
               CameraCharacteristics characteristics = manager.getCameraCharacteristics(cid);
               cameraCharacteristics.put(cid, new ARCameraCharacteristics(width, height, characteristics));
            }
            catch (Throwable _e)
            {
               Log.e("ARCameraManager", "", _e);
            }
         }
      }
      else
         cameraCharacteristics.put("0", new ARCameraCharacteristics(width, height, null));
      Set<String> cameraIds = cameraCharacteristics.keySet();
      return cameraIds.toArray(new String[cameraIds.size()]);
   }

   public void registerAvailabilityCallback(CameraManager.AvailabilityCallback callback, Handler handler)
   //----------------------------------------------------------------------------------------------------
   {
      if (context != null)
      {
         CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
         manager.registerAvailabilityCallback(callback, handler);
      }
   }

   public void unregisterAvailabilityCallback(CameraManager.AvailabilityCallback callback)
   //-------------------------------------------------------------------------------------
   {
      if (context != null)
      {
         CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
         manager.unregisterAvailabilityCallback(callback);
      }
   }

   public ARCameraCharacteristics getCameraCharacteristics(String cameraId) throws CameraAccessException
   //--------------------------------------------------------------------------------------------------
   {
      if (cameraCharacteristics == null)
         getCameraIdList();
      return cameraCharacteristics.get(cameraId);
   }

   public static Handler createHandler(String name)
   //----------------------------------------
   {
      HandlerThread t = new HandlerThread(name);
      t.start();
      return new Handler(t.getLooper());
   }

   public void openCamera(final String cameraId, final ARCameraDevice.StateCallback callback, Handler handler)
         throws CameraAccessException
   //----------------------------------------------------------------------------------------------------
   {
      if (! cameraCharacteristics.containsKey(cameraId))
      {
         callback.onError((ARCameraDevice) null, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
         return;
      }

      final ARCameraDevice camera;
      try
      {
         camera = new ARCameraDevice(context, cameraId, headerFile, framesFile, orientationFile, locationFile, null, false);
      }
      catch (Exception e)
      {
         throw new CameraAccessException(CameraAccessException.CAMERA_ERROR, "ARCameraDevice", e);
      }

      if ( (isDelegateCameras) && (context != null) )
      {
         CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
         final boolean isOwnHandler = (handler == null);
         if (isOwnHandler)
            handler = createHandler("openCamera");
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
                  hh.post(new Runnable() { @Override public void run() { callback.onOpened(camera); } });
               }

               @Override public void onDisconnected(CameraDevice cam)
               //---------------------------------------------------
               {
                  try { camera.close(); } catch (Exception _e) {}
                  callback.onDisconnected(camera);
               }

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
            camera.setDelegateCamera(cameraId, null);
            callback.onOpened(camera);
         }
      }
      else
      {
         camera.isOpen = true;
         camera.setDelegateCamera(cameraId, null);
         callback.onOpened(camera);
      }
   }

   void addCamera(String cameraID, ARCameraDevice camera) { cameras.put(cameraID, camera); }

   public ARCameraDevice getCamera(String cameraId) { return cameras.get(cameraId); }
}
