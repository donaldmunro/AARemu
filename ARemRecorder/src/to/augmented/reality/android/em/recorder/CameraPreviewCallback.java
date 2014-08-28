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
import android.hardware.Camera;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.SystemClock;
import android.util.Log;

public class CameraPreviewCallback implements Camera.PreviewCallback
//==================================================================
{
   final static private String TAG = CameraPreviewCallback.class.getSimpleName();

   public interface Previewable
   {
      public void onCameraFrame(long timestamp, byte[] frame);
   }

//   private final int previewWidth, previewHeight;
//   private final int bufferSize;

   byte[] previewBuffer = null;
   long timestamp =-1;
   RecorderRingBuffer ringBuffer;

   private volatile boolean mustBuffer = false;
   protected void bufferOn() { mustBuffer = true; }
   protected void bufferOff() { mustBuffer = false; }

   private volatile ConditionVariable frameAvailCondVar = null;
   protected void setFrameAvailableCondVar(ConditionVariable recordingCondVar) { this.frameAvailCondVar = recordingCondVar; }

   Previewable previewListener = null;
   public void setPreviewListener(Previewable listener) { previewListener = listener; }

   private GLRecorderRenderer renderer;
//   RenderScript rs = null;
//   ScriptIntrinsicYuvToRGB yuvToRgb = null;


   public CameraPreviewCallback(GLRecorderRenderer renderer, int bufferSize)
   //-------------------------------------------------------------------------------
   {
      this.renderer = renderer;
      previewBuffer = new byte[bufferSize];
      ActivityManager activityManager = (ActivityManager) renderer.activity.getSystemService(Context.ACTIVITY_SERVICE);
      ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
      activityManager.getMemoryInfo(memoryInfo);
      long availSize = memoryInfo.availMem/2;
      //int totalsize = RINGBUFFER_SIZE * bufferSize;
      int n = 20;
      for (; n>=3; n--)
      {
         long totalsize = n * bufferSize;
         if (totalsize <= availSize)
            break;
      }
      ringBuffer = new RecorderRingBuffer(n, bufferSize);
      Log.i(TAG, "Buffer size " + n + " x " + bufferSize + " = " + n * bufferSize);
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

   public long getLastTimestamp() { synchronized(this) { return ringBuffer.peekHeadTime(); } }

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
      if ( (data == null) || (data.length > previewBuffer.length) )
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
//      if ( (timestampCount % 200) == 0)
//         Log.i(TAG, "Frame update speed average: " + timestampTot/timestampCount);

      try
      {
//         ain.copyFrom(data);
//         yuvToRgb.setInput(ain);
//         yuvToRgb.forEach(aOut);
//         synchronized (this) { aOut.copyTo(previewBuffer); }
//         Log.i(TAG, "NV21 to RGBA Time = " + (SystemClock.elapsedRealtimeNanos() - timestamp) + " NS");

         synchronized (this) { System.arraycopy(data, 0, previewBuffer, 0, data.length); }
         if (mustBuffer)
         {
            ringBuffer.push(timestamp, previewBuffer);
            if (frameAvailCondVar != null)
               try { synchronized (this) { frameAvailCondVar.open(); } } catch (Exception _e) { Log.e(TAG, "", _e); }
         }
         if (previewListener != null)
            previewListener.onCameraFrame(timestamp, previewBuffer);
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
      if (frameAvailCondVar == null)
         return -1;
      synchronized (this) { frameAvailCondVar.close(); }
      if (frameAvailCondVar.block(frameBlockTimeMs))
      {
         synchronized (this)
         {
            System.arraycopy(this.previewBuffer, 0, previewBuffer, 0, this.previewBuffer.length);
            return timestamp;
         }
//         return findFirstBufferAtTimestamp(targetTimeStamp, epsilon, previewBuffer);
      }
      return -1;
   }

   public long findBufferGreater(long timestamp, long frameBlockTimeMs, byte[] buffer)
   //---------------------------------------------------------------------------------
   {
      long ts = ringBuffer.findGreater(timestamp, buffer);
      return (ts < 0) ? awaitFrame(frameBlockTimeMs, buffer) : ts;
   }

}
