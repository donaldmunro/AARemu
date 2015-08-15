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
import android.content.*;
import android.graphics.*;
import android.hardware.Camera;
import android.os.*;
import android.os.Process;
import android.util.Log;

import java.util.*;
import java.util.concurrent.*;

public class CameraPreviewThread extends HandlerThread implements Camera.PreviewCallback, Freezeable
//====================================================================================================
{
   final static private String TAG = CameraPreviewThread.class.getSimpleName();
   public static final int MSG_START_CAMERA = 1;
   public static final int MSG_STOP_CAMERA = 2;
   public static final int MSG_START_PREVIEW = 3;
   public static final int MSG_START_PREVIEW2 = 4;
   private boolean isInitCamera = true;
   private int bufferSize = -1;

   public interface Previewable
   {
      public void onCameraFrame(long timestamp, byte[] frame);
   }

   private Handler handler;
//   private final int previewWidth, previewHeight;
   long timestamp =-1;
   RecorderRingBuffer ringBuffer;
   private Semaphore startMutex = new Semaphore(1);

   private volatile boolean mustBuffer = false;
   protected void bufferOn() { mustBuffer = true; }
   protected void bufferOff() { mustBuffer = false; }

   private volatile ConditionVariable frameAvailCondVar = new ConditionVariable(false);;
   public ConditionVariable getFrameAvailCondVar() { return frameAvailCondVar;  }

   Previewable previewListener = null;
   public void setPreviewListener(Previewable listener) { previewListener = listener; }
   boolean isPreviewing = false;
   public boolean isPreviewing() { return isPreviewing; }

   private int previewWidth = -1, previewHeight = -1;
   int getPreviewWidth() { return previewWidth; }
   int getPreviewHeight() { return previewHeight; }

   private GLRecorderRenderer renderer;
//   RenderScript rs = null;
//   ScriptIntrinsicYuvToRGB yuvToRgb = null;

   Camera camera = null;
   int cameraId = -1;

   float focalLen = 0;
   float fovx = 0;
   float fovy = 0;

   public CameraPreviewThread(GLRecorderRenderer renderer, boolean isInitCamera)
   //-------------------------------------------------------------------------------
   {
      super("CameraPreview", Process.THREAD_PRIORITY_MORE_FAVORABLE);
      try { startMutex.acquire(); } catch (InterruptedException e) { return; }
      this.renderer = renderer;
      this.isInitCamera = isInitCamera;
//      this.previewWidth = previewWidth;
//      this.previewHeight = previewHeight;
//      this.bufferSize = bufferSize;
//      rs = RenderScript.create(context);
//      yuvToRgb = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs));
//         Type.Builder tb = new Type.Builder(rs, Element.createPixel(rs, Element.DataType.UNSIGNED_8,
//                                                                    Element.DataKind.PIXEL_YUV));
//         tb.setX(previewWidth);
//         tb.setY(previewHeight);
//         tb.setMipmaps(false);
//         tb.setYuvFormat(ImageFormat.NV21);
//         Allocation ain = Allocation.createTyped(rs, tb.create(), Allocation.USAGE_SCRIPT);
//         Type.Builder tb2 = new Type.Builder(rs, Element.RGBA_8888(rs));
////            Type.Builder tb2 = new Type.Builder(rs, Element.RGB_888(rs));
//         tb2.setX(previewWidth);
//         tb2.setY(previewHeight);
//         tb2.setMipmaps(false);
//         Allocation aOut = Allocation.createTyped(rs, tb2.create(), Allocation.USAGE_SCRIPT);
   }

   @Override
   protected void onLooperPrepared()
   //-------------------------------
   {
      super.onLooperPrepared();
      if (isInitCamera)
         startCamera();
      handler = new Handler(getLooper())
      {
         @Override
         public void handleMessage(Message msg)
         //------------------------------------
         {
            try
            {
               switch (msg.what)
               {
                  case MSG_START_PREVIEW:
                     initPreview(msg.arg1, msg.arg2);
                     break;
                  case MSG_START_PREVIEW2:
                     initPreview2();
                     break;
                  case MSG_START_CAMERA:
                     startCamera();
                     break;
                  case MSG_STOP_CAMERA:
                     _stopCamera();
                     break;
               }
            }
            catch (Throwable e)
            {
               Log.e(TAG, "", e);
            }
         }
      };
      startMutex.release();
   }

   public void initCamera()
   //----------------------
   {
      if (handler == null)
      {
         try
         {
            if (startMutex.tryAcquire(5, TimeUnit.SECONDS))
               startMutex.release();
            else
               return;
         }
         catch (InterruptedException e)
         {
            return;
         }
      }
      handler.dispatchMessage(Message.obtain(handler, MSG_START_CAMERA));
   }

   private void startCamera()
   //----------------------------
   {
      try
      {
         _stopCamera();
         Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
         for (int i = 0; i < Camera.getNumberOfCameras(); i++)
         {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK)
            {
               cameraId = i;
               try { camera = Camera.open(cameraId); } catch (Exception _e) { camera = null; }
               break;
            }
         }
         if (camera == null)
         {
            renderer.toast("Error acquiring camera");
            return;
         }
//         setDisplayOrientation();
//         camera.setDisplayOrientation(0);
         Camera.Parameters cameraParameters = camera.getParameters();
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)
            if (cameraParameters.isVideoStabilizationSupported())
               cameraParameters.setVideoStabilization(true);
         List<String> focusModes = cameraParameters.getSupportedFocusModes();
//         if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
//            cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
//         else
         if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_INFINITY))
            cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
         if (cameraParameters.isZoomSupported())
            cameraParameters.setZoom(0);
         camera.setParameters(cameraParameters);
         focalLen = cameraParameters.getFocalLength();
         fovx = cameraParameters.getHorizontalViewAngle();
         fovy = cameraParameters.getVerticalViewAngle();

         List<Camera.Size> resolutions = cameraParameters.getSupportedPreviewSizes();
         Collections.sort(resolutions, new Comparator<Camera.Size>()
         {
            @Override
            public int compare(Camera.Size lhs, Camera.Size rhs)
            //--------------------------------------------------
            {
               return (lhs.width > rhs.width) ? -1 : (lhs.width < rhs.width) ? 1 : 0;
            }
         });
         final String[] availResolutions = new String[resolutions.size()];
         int i = 0;
         for (Camera.Size resolution : resolutions)
         {
//            if ((resolution.width <= renderer.screenWidth) && (resolution.height <= renderer.screenHeight))
               availResolutions[i++] = String.format(Locale.UK, "%dx%d", resolution.width, resolution.height);
         }
         renderer.activity.runOnUiThread(new Runnable()
         {
            @Override public void run() { renderer.activity.onResolutionChange(availResolutions); }
         });

         return;
      }
      catch (Exception e)
      {
         camera = null;
         renderer.toast(String.format("Could not obtain rear facing camera (%s). Check if it is in use by another application.",
                        e.getMessage()));
         Log.e(TAG, "Camera.open()", e);
         return;
      }
   }

   public void stopCamera()
   //----------------------
   {
      if (handler == null)
      {
         try
         {
            if (startMutex.tryAcquire(5, TimeUnit.SECONDS))
               startMutex.release();
            else
               return;
         }
         catch (InterruptedException e)
         {
            return;
         }
      }
      handler.dispatchMessage(Message.obtain(handler, MSG_STOP_CAMERA));
   }

   private void _stopCamera()
   //----------------------
   {
      if (camera != null)
      {
         if (isPreviewing)
         {
            try
            {
               camera.setPreviewCallbackWithBuffer(null);
               camera.stopPreview();
               isPreviewing = false;
            }
            catch (Exception e)
            {
               Log.e(TAG, "", e);
            }
         }
         try
         {
            camera.release();
         }
         catch (Exception _e)
         {
            Log.e(TAG, _e.getMessage());
         }
      }
      camera = null;
   }

   public void startPreview(int width, int height)
   //-----------------------------------------------------------------
   {
      if (handler == null)
      {
         try
         {
            if (startMutex.tryAcquire(5, TimeUnit.SECONDS))
               startMutex.release();
            else
               return;
         }
         catch (InterruptedException e)
         {
            return;
         }
      }
      handler.dispatchMessage(Message.obtain(handler, MSG_START_PREVIEW, width, height));
   }

   public void startFixedPreview()
   //-----------------------------------------------------------------
   {
      if (handler == null)
      {
         try
         {
            if (startMutex.tryAcquire(5, TimeUnit.SECONDS))
               startMutex.release();
            else
               return;
         }
         catch (InterruptedException e)
         {
            return;
         }
      }
      handler.dispatchMessage(Message.obtain(handler, MSG_START_PREVIEW2));
   }

   private void initPreview(int width, int height)
   //------------------------------------------------
   {
      if (camera == null) return;
      if (isPreviewing)
      {
         if ((previewWidth == width) && (previewHeight == height))
            return;
         try
         {
            camera.setPreviewCallbackWithBuffer(null);
            camera.stopPreview();
            isPreviewing = false;
         }
         catch (Exception e)
         {
            Log.e(TAG, "", e);
         }
      }
      renderer.previewWidth =  previewWidth = width;
      renderer.previewHeight = previewHeight = height;


      ActivityManager activityManager = (ActivityManager) renderer.activity.getSystemService(Context.ACTIVITY_SERVICE);
      ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
      activityManager.getMemoryInfo(memoryInfo);
      long availSize = memoryInfo.availMem/2;
      //int totalsize = RINGBUFFER_SIZE * bufferSize;
      int n = 20;
      for (; n>=3; n--)
      {
         long totalsize = n * renderer.nv21BufferSize;
         if (totalsize <= availSize)
            break;
      }
      ringBuffer = new RecorderRingBuffer(n, renderer.nv21BufferSize);
      Log.i(TAG, "Buffer size " + n + " x " + renderer.nv21BufferSize + " = " + n * renderer.nv21BufferSize);
      this.bufferSize = renderer.nv21BufferSize;
      initPreview2();
   }

   private void initPreview2()
   //-----------------------------
   {
      if (GLRecorderRenderer.DIRECT_TO_SURFACE)
      {
         if (renderer.previewTexture < 0)
         {
            Log.e(TAG, "Preview texture has not been initialised by OpenGL glGenTextures. (" + renderer.previewTexture + ")");
            renderer.isAwaitingTextureFix = true;
            renderer.requestRender();
            return; // try again from render thread
         }
         renderer.previewSurfaceTexture = new SurfaceTexture(renderer.previewTexture);
      }
      else
      {
         renderer.previewSurfaceTexture = new SurfaceTexture(10);
         renderer.isUpdateTexture = true;
      }
      try
      {
         Camera.Parameters cameraParameters = camera.getParameters();
         cameraParameters.setPreviewSize(previewWidth, previewHeight);

         if (GLRecorderRenderer.DIRECT_TO_SURFACE)
            cameraParameters.setPreviewFormat(ImageFormat.NV21);
         else
            cameraParameters.setPreviewFormat(ImageFormat.YV12);
         camera.setParameters(cameraParameters);
         camera.setPreviewCallbackWithBuffer(this);
         if (GLRecorderRenderer.DIRECT_TO_SURFACE)
            renderer.previewSurfaceTexture.setOnFrameAvailableListener(renderer);
         camera.addCallbackBuffer(renderer.cameraBuffer);
         camera.setPreviewTexture(renderer.previewSurfaceTexture);
         camera.startPreview();
         isPreviewing = true;
         renderer.isAwaitingTextureFix = false;
      }
      catch (final Exception e)
      {
         Log.e(TAG, "Initialising camera preview", e);
         renderer.toast("Error initialising camera preview: " + e.getMessage());
         return;
      }
   }

   public long getLastBuffer(byte[] buffer) { synchronized(this) { return ringBuffer.peek(buffer); } }

   public RecorderRingBuffer.RingBufferContent findFirstBufferAtTimestamp(long timestampNS, long epsilonNS, byte[] buffer)
   {
      return ringBuffer.findFirst(timestampNS, epsilonNS, buffer);
   }

   public int getBufferSize() { return ringBuffer.length; }

   public String dumpBuffer() { return ringBuffer.toString(); }

   public void clearBuffer() { ringBuffer.clear(); }

//   long lastTimestamp = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
//                        ? SystemClock.elapsedRealtimeNanos()
//                        : System.nanoTime();
//   long timestampCount  = 0, timestampTot;

   @Override
   public void onPreviewFrame(byte[] data, Camera camera)
   //----------------------------------------------------
   {
      if ( (data == null) || (data.length > bufferSize) )
      {
         if (camera != null)
            camera.addCallbackBuffer(renderer.cameraBuffer);
         return;
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
         timestamp = SystemClock.elapsedRealtimeNanos();
      else
         timestamp = System.nanoTime();

//      timestampTot += (timestamp - lastTimestamp);
//      timestampCount++;
//      lastTimestamp = timestamp;
//      if ( (timestampCount % 100) == 0)
//         Log.i(TAG, "Frame update speed average: " + timestampTot/timestampCount);

      try
      {
//         ain.copyFrom(data);
//         yuvToRgb.setInput(ain);
//         yuvToRgb.forEach(aOut);
//         synchronized (this) { aOut.copyTo(previewBuffer); }
//         Log.i(TAG, "NV21 to RGBA Time = " + (SystemClock.elapsedRealtimeNanos() - timestamp) + " NS");

         if (mustBuffer)
            ringBuffer.push(timestamp, data);
         frameAvailCondVar.open();
         if (previewListener != null)
            previewListener.onCameraFrame(timestamp, data);
      }
      catch (Throwable e)
      {
         Log.e(TAG, "", e);
      }
//      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
//         Log.i(TAG, "onPreviewFrame: Processing time " + (SystemClock.elapsedRealtimeNanos() - timestamp));
//      else
//         Log.i(TAG, "onPreviewFrame: Processing time " + (System.nanoTime() - timestamp));
   }

   public long awaitFrame(long frameBlockTimeMs, byte[] previewBuffer)
   //-----------------------------------------------------------------
   {
      frameAvailCondVar.close();
      if (frameAvailCondVar.block(frameBlockTimeMs))
      {
         synchronized (this)
         {
            return ringBuffer.peek(previewBuffer);
         }
//         return findFirstBufferAtTimestamp(targetTimeStamp, epsilon, previewBuffer);
      }
      return -1;
   }

   @Override
   public void pause(Bundle B)
   {
      B.putBoolean("isPreviewing", isPreviewing);
      B.putBoolean("mustBuffer", mustBuffer);
      B.putInt("previewWidth", previewWidth);
      B.putInt("previewHeight", previewHeight);
      B.putInt("bufferSize", bufferSize);
   }

   @Override
   public void restore(Bundle B)
   {
      isPreviewing = B.getBoolean("isPreviewing");
      mustBuffer = B.getBoolean("mustBuffer");
      previewWidth = B.getInt("previewWidth");
      previewHeight = B.getInt("previewHeight");
      bufferSize = B.getInt("bufferSize");
   }
}
