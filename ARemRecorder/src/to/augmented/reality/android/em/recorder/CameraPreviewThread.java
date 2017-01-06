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

import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

class CameraPreviewThread extends HandlerThread implements Camera.PreviewCallback, Freezeable
//===========================================================================================
{
   final static private String TAG = CameraPreviewThread.class.getSimpleName();
   public static final int MSG_START_CAMERA = 1;
   public static final int MSG_STOP_PREVIEW = 2;
   public static final int MSG_START_PREVIEW = 3;
   public static final int MSG_START_PREVIEW2 = 4;

   private int bufferSize = -1, format;

   private Handler handler;
//   private final int previewWidth, previewHeight;
   private NativeFrameBuffer frameBuffer;

   private Semaphore startMutex = new Semaphore(1);

   private volatile boolean mustBuffer = false;
   public void bufferOn() { frameBuffer.bufferOn(); mustBuffer = true; }
   public void bufferOff() { frameBuffer.bufferOff(); mustBuffer = false; }
   public void bufferClear() { frameBuffer.bufferClear(); }
   public boolean bufferEmpty() { return frameBuffer.bufferEmpty(); }
   ConditionVariable frameAvailCondVar;
   boolean isPreviewing = false;
   public boolean isPreviewing() { return isPreviewing; }

   private int previewWidth = -1, previewHeight = -1;
   int getPreviewWidth() { return previewWidth; }
   int getPreviewHeight() { return previewHeight; }

   private GLRecorderRenderer renderer;
//   RenderScript rs = null;
//   ScriptIntrinsicYuvToRGB yuvToRgb = null;

   Camera camera = null;

   public interface FrameListenable
   {
      void onFrameAvailable(byte[] data, long timestamp);
   }

   volatile private FrameListenable frameListener = null;
   public void setFrameListener(FrameListenable listener) { frameListener = listener; }
   public FrameListenable getFrameListener() { return frameListener; }

   public CameraPreviewThread(GLRecorderRenderer renderer, Camera camera, int width, int height, int bufferSize,
                              int format, NativeFrameBuffer frameBuffer, ConditionVariable frameAvailCondVar)
   //-------------------------------------------------------------------------------
   {
      super("CameraPreview", Process.THREAD_PRIORITY_MORE_FAVORABLE);
      try { startMutex.acquire(); } catch (InterruptedException e) { return; }
      this.renderer = renderer;
      this.camera = camera;
      this.previewWidth = width;
      this.previewHeight = height;
      this.bufferSize = bufferSize;
      this.frameBuffer = frameBuffer;
      this.frameAvailCondVar = frameAvailCondVar;
      this.format = format;
   }

   @Override
   protected void onLooperPrepared()
   //-------------------------------
   {
      super.onLooperPrepared();
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
                     initPreview();
                     break;
                  case MSG_STOP_PREVIEW:
                     _stopPreview();
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

   public void stopPreview()
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
      handler.dispatchMessage(Message.obtain(handler, MSG_STOP_PREVIEW));
   }

   private void _stopPreview()
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

   public void startPreview()
   //------------------------
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
      handler.dispatchMessage(Message.obtain(handler, MSG_START_PREVIEW));
   }

   private void initPreview()
   //------------------------
   {
//      if (renderer.previewTexture < 0)
//      {
//         Log.e(TAG, "Preview texture has not been initialised by OpenGL glGenTextures. (" + renderer.previewTexture + ")");
//         renderer.isAwaitingTextureFix = true;
//         renderer.requestRender();
//         return; // try again from render thread
//      }
//      else if (renderer.isAwaitingTextureFix)
//         renderer.isAwaitingTextureFix = false;
      if (renderer.previewTexture < 0)
         throw new RuntimeException("Preview texture not initialized");
      renderer.previewSurfaceTexture = new SurfaceTexture(renderer.previewTexture);
      try
      {
         Camera.Parameters cameraParameters = camera.getParameters();
         cameraParameters.setPreviewSize(previewWidth, previewHeight);

         cameraParameters.setPreviewFormat(format);
         camera.setParameters(cameraParameters);
         camera.setPreviewCallbackWithBuffer(this);
         renderer.previewSurfaceTexture.setOnFrameAvailableListener(renderer);
         camera.addCallbackBuffer(renderer.cameraBuffer);
         camera.setPreviewTexture(renderer.previewSurfaceTexture);
         camera.startPreview();
         isPreviewing = true;
      }
      catch (final Exception e)
      {
         Log.e(TAG, "Initialising camera preview", e);
         renderer.toast("Error initialising camera preview: " + e.getMessage());
      }
   }

   public void suspendPreview()
   //--------------------------
   {
      if (camera != null)
         camera.setPreviewCallbackWithBuffer(null);
   }

   public void restartPreview()
   //--------------------------
   {
      if (camera != null)
         camera.setPreviewCallbackWithBuffer(this);
   }

   public void releaseFrame()
   //------------------------
   {
      if (camera != null)
         camera.addCallbackBuffer(renderer.cameraBuffer);
   }

//   long lastTimestamp = SystemClock.elapsedRealtimeNanos();
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
      long timestamp = SystemClock.elapsedRealtimeNanos();
      frameBuffer.push(timestamp, data, 5);
      frameAvailCondVar.open();

//      try (FileOutputStream fw = new FileOutputStream(new File("/sdcard/Documents/ARRecorder/t/frame.nv21"))
//      {
//         fw.write(data);
//         fw.close();
//      }
//      catch (Exception e)
//      {
//
//      }
//      timestampTot += (timestamp - lastTimestamp);
//      timestampCount++;
//      lastTimestamp = timestamp;
//      if ( (timestampCount % 100) == 0)
//         Log.i(TAG, "Frame update speed average: " + timestampTot/timestampCount);

//      try
//      {
//         if (frameListener != null)
//            frameListener.onFrameAvailable(data, timestamp);
//         if (mustBuffer)
//            frameBuffer.push(timestamp, data);
//         frameAvailCondVar.open();
//      }
//      catch (Throwable e)
//      {
//         Log.e(TAG, "", e);
//      }
   }

   @Override
   public void freeze(Bundle B)
   //--------------------------
   {
      B.putBoolean("isPreviewing", isPreviewing);
      B.putBoolean("mustBuffer", mustBuffer);
      B.putInt("previewWidth", previewWidth);
      B.putInt("previewHeight", previewHeight);
      B.putInt("bufferSize", bufferSize);
   }

   @Override
   public void thaw(Bundle B)
   //------------------------
   {
      isPreviewing = B.getBoolean("isPreviewing");
      mustBuffer = B.getBoolean("mustBuffer");
      previewWidth = B.getInt("previewWidth");
      previewHeight = B.getInt("previewHeight");
      bufferSize = B.getInt("bufferSize");
   }
}
