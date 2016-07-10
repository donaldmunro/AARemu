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

import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import to.augmented.reality.android.em.AbstractARCamera;
import to.augmented.reality.android.em.Latcheable;
import to.augmented.reality.android.em.FreePreviewListenable;
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

/**
 * Plays back Free recording mode files in continuous mode.
 * In all cases all frames including duplicate frames are played back (as opposed to DirtyPlaybackThread* which only
 * play back one frame where duplicate frames occur).
 * If the fps (frames per second) parameter is zero or negative then it will play back at approximately the same rate
 * as it was recorded at using the timestamps in the file. If fps is positive then it plays back at the specified
 * frame rate.
 */
public class ContinuousPlaybackThreadFree extends PlaybackThreadFree implements Runnable, Stoppable
//=================================================================================================
{
   final static private String TAG = "free/" + ContinuousPlaybackThreadFree.class.getSimpleName();

   public ContinuousPlaybackThreadFree(File framesFile, File orientationFile, File locationFile, AbstractARCamera.RecordFileFormat fileFormat,
                                       int bufferSize)
   {
      super(framesFile, orientationFile, locationFile, fileFormat, bufferSize);
   }

   public ContinuousPlaybackThreadFree(File framesFile, File orientationFile, File locationFile,
                                       AbstractARCamera.RecordFileFormat fileFormat, int bufferSize, int fps, boolean isRepeat,
                                       ArrayBlockingQueue<byte[]> bufferQueue, FreePreviewListenable progress)
   {
      super(framesFile, orientationFile, locationFile, fileFormat, bufferSize, fps, isRepeat, bufferQueue, progress);
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

      Runnable orientationThread = null, locationThread = null;
      ConcurrentLinkedQueue<Long> orientationTimestampQueue = null, locationTimestampQueue = null;
      long fpsInterval = -1L;
      if (fps > 0)
      {
         if (fps > 1000)
            fps /= 1000; // In case legacy camera API value wasn't scaled
         fpsInterval = 1000000000L / fps;
      }
      isStarted = true;
      mustStop = false;
      boolean again;
      do
      {
         if (futures.size() > 0)
         {
            try { stopThreads((Stoppable) orientationThread, (Stoppable) locationThread); } catch (InterruptedException _e) { break; }
         }
         int tc = 0;
         if ( (orientationFile != null) && (orientationFile.length() > 0) && (orientationListener != null) )
         {
            if (fps > 0)
            {
               orientationTimestampQueue = new ConcurrentLinkedQueue<>();
               orientationThread = new OrientationQueuedCallbackThread(orientationFile, orientationTimestampQueue,
                                                                       orientationListener);
            }
            else
               orientationThread = new OrientationCallbackThread(orientationFile, orientationListener);
            tc++;
         }
         if ( (locationFile != null) && (locationFile.length() > 0) && (locationListener != null) )
         {
            if (fps > 0)
            {
               locationTimestampQueue = new ConcurrentLinkedQueue<>();
               locationThread = new LocationQueuedCallbackThread(locationFile, locationTimestampQueue, locationListener);
            }
            else
               locationThread = new LocationCallbackThread(locationFile, locationListener);
            tc++;
         }
         CountDownLatch startLatch = new CountDownLatch(tc + 1);
         createThreadPool();
         if (orientationThread != null)
         {
            ((Latcheable) orientationThread).setLatch(startLatch);
            Future<?> future = threadPool.submit(orientationThread);
            futures.add(future);
         }
         if (locationThread != null)
         {
            ((Latcheable) locationThread).setLatch(startLatch);
            Future<?> future = threadPool.submit(locationThread);
            futures.add(future);
         }
         Thread.yield();
         startLatch.countDown();
         try { startLatch.await(); } catch (InterruptedException e) { mustStop = true; isStarted = false; return; }
         long startTime, processStart, timestamp = 0, lastTimestamp = 0;
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
            processStart = startTime = SystemClock.elapsedRealtimeNanos();
         else
            processStart = startTime = System.nanoTime();
         int readlen, iter = 0;
         try (DataInputStream framesStream = new DataInputStream(new BufferedInputStream(new FileInputStream(framesFile), 65535)))
         {
            if (progress != null)
               progress.onStarted();
            long frameStartTime = startTime, frameEndTime, size;
            byte[] buffer = null;
            try
            {
               lastTimestamp = timestamp = framesStream.readLong();
               if (fps > 0)
               {
                  if (orientationTimestampQueue != null)
                     orientationTimestampQueue.offer(timestamp);
                  if (locationTimestampQueue != null)
                     locationTimestampQueue.offer(timestamp);
               }
               size = framesStream.readLong();
               if (size > 0)
               {
                  buffer = bufferQueue.poll(500, TimeUnit.MILLISECONDS);
                  if (buffer != null)
                  {
                     readlen = readBuffer(buffer, framesStream, size); //framesStream.read(buffer)
                     if (readlen != size)
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
            if (fps > 0)
            {
               if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                  frameStartTime = SystemClock.elapsedRealtimeNanos();
               else
                  frameStartTime = System.nanoTime();
            }
            while (! mustStop)
            {
               if (fps <= 0)
               {
                  long timediff;
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                     timediff = (timestamp - lastTimestamp - (SystemClock.elapsedRealtimeNanos() - processStart) - 1000L); // / 1000000L;
                  else
                     timediff = (timestamp - lastTimestamp - (System.nanoTime() - processStart) - 1000L);// / 1000000L;
                  Log.i(TAG, "timediff = " + timediff);
                  long now, then;
                  if ( (timediff > 0) && (! mustStop) )
                  {
                     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                        now = SystemClock.elapsedRealtimeNanos();
                     else
                        now = System.nanoTime();
                     then = now + timediff;
                     while ( (then > now) && (! mustStop) )
                     {
                        Thread.yield();
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                           now = SystemClock.elapsedRealtimeNanos();
                        else
                           now = System.nanoTime();
                     }
                  }
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                     processStart = SystemClock.elapsedRealtimeNanos();
                  else
                     processStart = System.nanoTime();
               }

               try
               {
                  if (buffer != null)
                  {
                     if (cameraListener != null)
                        cameraListener.onPreviewFrame(buffer, null);
                     else
                        camera2Listener.onPreviewFrame(buffer);
                     if (!isUseBuffer)
                        bufferQueue.add(buffer);
                  }

                  if (fps > 0)
                  {
                     if (buffer != null)
                     {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                           frameEndTime = SystemClock.elapsedRealtimeNanos();
                        else
                           frameEndTime = System.nanoTime();
                        long dt = frameEndTime - frameStartTime;
                        while (dt < fpsInterval)
                        {
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
                  }
                  else
                     lastTimestamp = timestamp;

                  timestamp = framesStream.readLong();
                  size = framesStream.readLong();
                  if (fps > 0)
                  {
                     if (orientationTimestampQueue != null)
                        orientationTimestampQueue.offer(timestamp);
                     if (locationTimestampQueue != null)
                        locationTimestampQueue.offer(timestamp);
                     if (size == 0)
                     {
                        buffer = null;
                        continue;
                     }
                  }
                  else
                  {
                     if (size == 0)
                        continue;
                  }
                  buffer = bufferQueue.poll(500, TimeUnit.MILLISECONDS);
                  if (buffer != null)
                  {
                     readlen = readBuffer(buffer, framesStream, size); //framesStream.read(buffer)
                     if (readlen != size)
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
         iter++;
         if (progress != null)
            again = progress.onComplete(iter);
         else
            again = isRepeat;
      } while ( (! mustStop) && (again) );

      try { stopThreads((Stoppable) orientationThread, (Stoppable) locationThread); } catch (InterruptedException _e) {}
      isStarted = false;
   }


}
