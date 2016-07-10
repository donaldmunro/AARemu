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

import android.hardware.Camera;
import android.location.LocationListener;
import to.augmented.reality.android.common.sensor.orientation.OrientationListenable;
import to.augmented.reality.android.em.AbstractARCamera;
import to.augmented.reality.android.em.ARCameraDevice;
import to.augmented.reality.android.em.Latcheable;
import to.augmented.reality.android.em.FreePreviewListenable;
import to.augmented.reality.android.em.Stoppable;

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
   final static private int PREALLOCATED_BUFFERS = 3;

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

   final protected boolean isUseBuffer;

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

   public PlaybackThreadFree(File framesFile, File orientationFile, File locationFile,
                             AbstractARCamera.RecordFileFormat fileFormat, int bufferSize)
   {
      this(framesFile, orientationFile, locationFile, fileFormat, bufferSize, -1, false, null, null);
   }

   public PlaybackThreadFree(File framesFile, File orientationFile, File locationFile, AbstractARCamera.RecordFileFormat fileFormat,
                             int bufferSize, int fps, boolean isRepeat, ArrayBlockingQueue<byte[]> bufferQueue,
                             FreePreviewListenable progress)
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
}
