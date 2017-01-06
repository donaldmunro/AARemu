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
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import to.augmented.reality.android.em.ARSensorManager;
import to.augmented.reality.android.em.AbstractARCamera;
import to.augmented.reality.android.em.FreePreviewListenable;
import to.augmented.reality.android.em.Latcheable;
import to.augmented.reality.android.em.QueuedRawSensorPlaybackThread;
import to.augmented.reality.android.em.Stoppable;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class DirtyPlaybackThreadFree extends PlaybackThreadFree implements Runnable, Stoppable
//============================================================================================
{
   final static private String TAG = "free/" + DirtyPlaybackThreadFree.class.getSimpleName();
   private static final int DEFAULT_FPS = 24;

   public DirtyPlaybackThreadFree(File framesFile, File orientationFile, File locationFile,
                                  AbstractARCamera.RecordFileFormat fileFormat, int bufferSize, int fps,
                                  boolean isRepeat,
                                  ArrayBlockingQueue<byte[]> bufferQueue, ARSensorManager sensorManager,
                                  int previewWidth, int previewHeight, Context context, Surface surface,
                                  int version, FreePreviewListenable progress)
   //---------------------------------------------------------------------------------------------------------------
   {
      super(framesFile, orientationFile, locationFile, fileFormat, bufferSize, fps, isRepeat, bufferQueue, sensorManager,
            previewWidth, previewHeight, context, surface, version, progress);
   }


   @Override
   public void run()
   //--------------
   {
      if (startLatch != null)
      {
         startLatch.countDown();
         try { startLatch.await(); } catch (InterruptedException e) { return; }
      }
      long fpsInterval = -1L;
      if (fps < 0)
         fps = DEFAULT_FPS;
      else if (fps > 1000)
         fps /= 1000;
      fpsInterval = 1000000000L / fps;
      ConcurrentLinkedQueue<Long> orientationTimestampQueue = null, locationTimestampQueue = null, sensorTimestampQueue = null;
      isStarted = true;
      mustStop = false;
      OrientationQueuedCallbackThread orientationThread = null;
      LocationQueuedCallbackThread locationThread = null;
      QueuedRawSensorPlaybackThread sensorThread = null;
      int readlen = 0, iter = 0;
      boolean again;
      do
      {
         if (futures.size() > 0)
         {
            try { stopThreads(orientationThread, locationThread); } catch (InterruptedException _e) { break; }
         }
         int tc = 0;
         if ( (orientationFile != null) && (orientationFile.length() > 0) && (orientationListener != null) )
         {
            orientationTimestampQueue = new ConcurrentLinkedQueue<>();
            orientationThread = new OrientationQueuedCallbackThread(orientationFile, orientationTimestampQueue,
                                                                    orientationListener, version);
            tc++;
         }
         if ( (locationFile != null) && (locationFile.length() > 0) && (locationListener != null) )
         {
            locationTimestampQueue = new ConcurrentLinkedQueue<>();
            locationThread = new LocationQueuedCallbackThread(locationFile, locationTimestampQueue, locationListener);
            tc++;
         }
         if (sensorManager != null)
         {
            sensorTimestampQueue = new ConcurrentLinkedQueue<>();
            sensorThread = new QueuedRawSensorPlaybackThread(sensorManager.getSensorFile(), sensorManager.getSensorMao(),
                                                             sensorManager.getObservers(), null, sensorTimestampQueue);
            tc++;
         }
         CountDownLatch startLatch = new CountDownLatch(tc + 1);
         createThreadPool();
         if (orientationThread != null)
         {
            orientationThread.setLatch(startLatch);
            Future<?> future = threadPool.submit(orientationThread);
            futures.add(future);
         }
         if (locationThread != null)
         {
            locationThread.setLatch(startLatch);
            Future<?> future = threadPool.submit(locationThread);
            futures.add(future);
         }
         if (sensorThread != null)
         {
            ((Latcheable) sensorThread).setLatch(startLatch);
            Future<?> future = threadPool.submit(sensorThread);
            futures.add(future);
         }
         Thread.yield();
         startLatch.countDown();
         try { startLatch.await(); } catch (InterruptedException e) { mustStop = true; isStarted = false; return; }
         long startTime;
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
            startTime = SystemClock.elapsedRealtimeNanos();
         else
            startTime = System.nanoTime();
         DataInputStream framesStream = null;
         try
         {
            framesStream = new DataInputStream(new BufferedInputStream(new FileInputStream(framesFile), 65535));
            if (progress != null)
               progress.onStarted();
            long frameStartTime, frameEndTime, frameTimestamp, frameSize;
            byte[] buffer = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
               frameStartTime = SystemClock.elapsedRealtimeNanos();
            else
               frameStartTime = System.nanoTime();
            try
            {
               frameTimestamp = framesStream.readLong();
               if (orientationTimestampQueue != null)
                  orientationTimestampQueue.offer(frameTimestamp);
               if (locationTimestampQueue != null)
                  locationTimestampQueue.offer(frameTimestamp);
               if (sensorTimestampQueue != null)
                  sensorTimestampQueue.offer(frameTimestamp);
               frameSize = framesStream.readLong();
               if (frameSize > 0)
               {
                  buffer = bufferQueue.poll(500, TimeUnit.MILLISECONDS);
                  if (buffer != null)
                  {
                     readlen = readBuffer(buffer, framesStream, frameSize); //framesStream.read(buffer)
                     if (readlen != frameSize)
                        Log.w(TAG, "Short read " + readlen);
                  }
                  else
                     Log.w(TAG, "Timed out waiting for a buffer. Check if buffers are being replenished using addCallbackBuffer");
               }
            }
            catch (EOFException | InterruptedException ee)
            {
               mustStop = true;
            }

//            int framecount = 0;
//            long now = System.currentTimeMillis();
//            long then =  now + 1000L;

            while (! mustStop)
            {
               try
               {
                  if (buffer != null)
                  {
                     if (cameraListener != null)
                     {
                        cameraListener.onPreviewFrame(buffer, null);
                        if (surface != null)
                           super.drawToSurface(buffer);
                     }
                     else
                        camera2Listener.onPreviewFrame(buffer);
                     if ( (!isUseBuffer) && (bufferQueue.size() < PREALLOCATED_BUFFERS) )
                        bufferQueue.add(buffer);

//                     framecount++;
//                     now = System.currentTimeMillis();
//                     if (now >= then)
//                     {
//                        Log.i(TAG, "Frame count: " + framecount);
//                        framecount = 0;
//                        then = System.currentTimeMillis() + 1000L;
//                     }

                     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                        frameEndTime = SystemClock.elapsedRealtimeNanos();
                     else
                        frameEndTime = System.nanoTime();
                     long dt = frameEndTime - frameStartTime;
                     while (dt < fpsInterval)
                     {
                        Thread.yield();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                           frameEndTime = SystemClock.elapsedRealtimeNanos();
                        else
                           frameEndTime = System.nanoTime();
                        dt = frameEndTime - frameStartTime;
                     }
                     frameStartTime = frameEndTime;
                  }
                  else
                  {
                     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                        frameStartTime = SystemClock.elapsedRealtimeNanos();
                     else
                        frameStartTime = System.nanoTime();
                  }

                  frameTimestamp = framesStream.readLong();
                  if (orientationTimestampQueue != null)
                     orientationTimestampQueue.offer(frameTimestamp);
                  if (locationTimestampQueue != null)
                     locationTimestampQueue.offer(frameTimestamp);
                  if (sensorTimestampQueue != null)
                     sensorTimestampQueue.offer(frameTimestamp);
                  frameSize = framesStream.readLong();
                  if (frameSize == 0)
                  {
                     buffer = null;
                     continue;
                  }
                  buffer = bufferQueue.poll(500, TimeUnit.MILLISECONDS);
                  if (buffer != null)
                  {
                     readlen = readBuffer(buffer, framesStream, frameSize);
                     if (readlen != frameSize)
                        Log.w(TAG, "Short read " + readlen);
                  }
                  else
                     Log.w(TAG, "Timed out waiting for a buffer. Check if buffers are being replenished using addCallbackBuffer");
               }
               catch (InterruptedException e)
               {
                  mustStop = true;
                  if (progress != null)
                     progress.onError("Interrupted", e);
                  break;
               }
               catch (EOFException ee)
               {
                  if (! isRepeat)
                     mustStop = true;
                  break;
               }
            }
         }
         catch (IOException e)
         {
            Log.e(TAG, "", e);
            if (progress != null)
               progress.onError(framesFile.getAbsolutePath(), e);
         }
         catch (Exception e)
         {
            Log.e(TAG, "", e);
            if (progress != null)
               progress.onError("ABEND", e); // Android/360
            throw new RuntimeException(e);
         }
         finally
         {
            if (framesStream != null)
               try { framesStream.close(); } catch (Exception _e) {}
         }
         iter++;
         if (progress != null)
            again = progress.onComplete(iter);
         else
            again = isRepeat;
      } while ( (! mustStop) && (again) );
      try { stopThreads(orientationThread, locationThread); } catch (InterruptedException _e) { }
      isStarted = false;
   }
}
