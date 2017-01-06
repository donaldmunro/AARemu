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

package to.augmented.reality.android.em.free;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.Camera;
import android.location.LocationListener;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.Type;
import android.util.Log;
import android.view.Surface;
import to.augmented.reality.android.common.sensor.orientation.OrientationListenable;
import to.augmented.reality.android.em.ARCameraDevice;
import to.augmented.reality.android.em.ARSensorManager;
import to.augmented.reality.android.em.AbstractARCamera;
import to.augmented.reality.android.em.FreePreviewListenable;
import to.augmented.reality.android.em.Latcheable;
import to.augmented.reality.android.em.Stoppable;
import to.augmented.reality.android.em.rs.ScriptC_rgba2argb;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

abstract public class PlaybackThreadFree implements Runnable, Stoppable, Latcheable
//=================================================================================
{
   final static private String TAG = "free/" + PlaybackThreadFree.class.getSimpleName();
   final static protected int PREALLOCATED_BUFFERS = 3;

   protected final File framesFile;
   protected final File orientationFile;
   protected final File locationFile;
   protected final AbstractARCamera.RecordFileFormat fileFormat;
   protected final boolean isRepeat;
   protected int fps;
   protected ArrayBlockingQueue<byte[]> bufferQueue;
   protected Camera.PreviewCallback cameraListener;
   protected OrientationListenable orientationListener;
   protected LocationListener locationListener;
   protected ARCameraDevice.ARCaptureCallback camera2Listener;
   protected final int bufferSize;
   protected FreePreviewListenable progress = null;

   protected CountDownLatch startLatch = null;
   @Override public void setLatch(CountDownLatch latch) { startLatch = latch; }

   protected ARSensorManager sensorManager = null;

   final protected boolean isUseBuffer;

   private RenderScript renderscript = null;
   protected Surface surface; // Only not null If the ARCamera had a setPreviewDisplay(holder) call. The surface is from the holder
   protected final Context context; // ""
   protected int width, height; // Only necessary where surface is not null
   protected int version;

   volatile protected boolean mustStop = false;
   volatile protected boolean isStarted = false;

   protected ExecutorService threadPool = null;

   protected ExecutorService createThreadPool()
   //--------------------------------------
   {
      threadPool = Executors.newCachedThreadPool(new ThreadFactory()
      {
         @Override
         public Thread newThread(Runnable r)
         {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("Playback-Orientation");
            return t;
         }
      });
      return threadPool;
   }

   protected List<Future<?>> futures = new ArrayList<>();

   public PlaybackThreadFree(File framesFile, File orientationFile, File locationFile, AbstractARCamera.RecordFileFormat fileFormat,
                             int bufferSize, int fps, boolean isRepeat, ArrayBlockingQueue<byte[]> bufferQueue,
                             ARSensorManager sensorManager,
                             int previewWidth, int previewHeight, Context context, Surface previewSurface,
                             int version, FreePreviewListenable progress)
   //-----------------------------------------------------------------------------------------------------------
   {
      this.framesFile = framesFile;
      this.orientationFile = orientationFile;
      this.locationFile = locationFile;
      this.fileFormat = fileFormat;
      this.isRepeat = isRepeat;
      this.fps = fps;
      this.bufferSize = bufferSize;
      this.bufferQueue = bufferQueue;
      this.progress = progress;
      isUseBuffer = (bufferQueue != null);
      this.surface = previewSurface;
      this.context = context;
      width = previewWidth;
      height = previewHeight;
      if (this.surface != null)
         renderscript = RenderScript.create(context);
      this.sensorManager = sensorManager;
      this.version = version;
      if (! isUseBuffer)
      {  // If not using user defined buffers create an internal buffer of buffers
         this.bufferQueue = new ArrayBlockingQueue<byte[]>(PREALLOCATED_BUFFERS);
         for (int i=0; i<PREALLOCATED_BUFFERS; i++)
            this.bufferQueue.add(new byte[bufferSize]);
      }
   }

   public void setCameraListener(Object callback)
   //--------------------------------------------
   {
      if (callback == null)
         throw new RuntimeException("Camera callback cannot be null");
      if (callback instanceof Camera.PreviewCallback)
         cameraListener = (Camera.PreviewCallback) callback;
      else if (callback instanceof ARCameraDevice.ARCaptureCallback)
         camera2Listener = (ARCameraDevice.ARCaptureCallback) callback;
      else
         throw new RuntimeException(callback.getClass().getName() + " must be one of " +
                                          Camera.PreviewCallback.class.getName() + " or " +
                                          ARCameraDevice.ARCaptureCallback.class.getName());
   }

   public void setOrientationListener(OrientationListenable listener) { orientationListener = listener; }

   public void setLocationListener(LocationListener listener) { this.locationListener = listener; }

   @Override public boolean isStarted() { return isStarted; }

   @Override public void stop() { mustStop = true; }

   @Override abstract public void run();

   static public int readBuffer(byte[] buffer, DataInputStream stream, long size) throws IOException
   //------------------------------------------------------------------------------------------------------
   {
      int offset = 0, count = (int) size;
      while (offset < size)
      {
         int c = stream.read(buffer, offset, count);
         count -= c;
         offset += c;
      }
      return offset;
   }

   protected void stopThreads(Stoppable... threads) throws InterruptedException
   //---------------------------------------------------------------------------
   {
      if ( (futures == null) || (futures.size() == 0) )
         return;
      for (Stoppable t : threads)
      {
         if (t != null)
            t.stop();
      }
      Thread.sleep(100);
      for (Future<?> f : futures)
      {
         if (! f.isDone())
            f.cancel(true);
      }
      Thread.sleep(100);
      threadPool.shutdownNow();
      futures.clear();
      threadPool = null;
   }

   public void drawToSurface(final byte[] buffer)
   //--------------------------------------------
   {
      int[] ARGB = new int[buffer.length/4];
      try
      {
         Type.Builder rgbaType = new Type.Builder(renderscript, Element.RGBA_8888(renderscript)).setX(width).
                                                               setY( height).setMipmaps(false);
         Allocation aIn = Allocation.createTyped(renderscript, rgbaType.create(), Allocation.USAGE_SCRIPT);
         Type.Builder argbType = new Type.Builder(renderscript, Element.U32(renderscript)).setX(width).
               setY( height).setMipmaps(false);
         Allocation aOut = Allocation.createTyped(renderscript, argbType.create(), Allocation.USAGE_SCRIPT);
         ScriptC_rgba2argb rs = new ScriptC_rgba2argb(renderscript);
         aIn.copyFrom(buffer);
         rs.set_in(aIn);
         rs.forEach_rgba2argb(aOut);
         aOut.copyTo(ARGB);
      }
      catch (Exception e)
      {
         Log.e("PlaybackThreadFree", "drawToSurface: Renderscript RGBA to ARGB error", e);
         int i=0, j = 0;
         while (i<buffer.length)
         {
            int r = (int) buffer[i++];
            if (r < 0) r = 256 + r; // Brain-dead Java has no unsigned char
            int g = (int) buffer[i++];
            if (g < 0) g = 256 + g;
            int b = (int) buffer[i++];
            if (b < 0) b = 256 + b;
            int a = buffer[i++];
            if (a < 0) a = 256 + a;
            ARGB[j++] = Color.argb(a, r, g, b);
         }
      }

      Bitmap bmp = Bitmap.createBitmap(ARGB, width, height, Bitmap.Config.ARGB_8888);
      Canvas canvas = surface.lockCanvas(null);
      canvas.drawBitmap(bmp, 0, 0, null);
      surface.unlockCanvasAndPost(canvas);
   }
}
