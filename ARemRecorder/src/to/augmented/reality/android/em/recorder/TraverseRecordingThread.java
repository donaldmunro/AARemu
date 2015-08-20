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

import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class TraverseRecordingThread extends RecordingThread implements Freezeable
//================================================================================
{
   static final private String TAG = TraverseRecordingThread.class.getSimpleName();
   int no, totalCount = 0;
   volatile int count = 0;
   Set<Long> completedBearings;
   protected ProcessBearingThread processBearingThread = null;

   protected TraverseRecordingThread(GLRecorderRenderer renderer, RecorderRingBuffer frameBuffer)
   {
      super(renderer, frameBuffer);
      no = (int) (Math.floor(360.0 / recordingIncrement));
   }

   protected TraverseRecordingThread(GLRecorderRenderer renderer, int nv21BufferSize,
                                     float increment, CameraPreviewThread previewer,
                                     ConditionVariable recordingCondVar, ConditionVariable frameCondVar,
                                     RecorderRingBuffer frameBuffer, BearingRingBuffer bearingBuffer)
   //----------------------------------------------------------------------------------------------------------------
   {
      super(renderer, nv21BufferSize, increment, previewer, recordingCondVar, frameCondVar, frameBuffer, bearingBuffer);
      no = (int) (Math.floor(360.0 / recordingIncrement));
   }

   @Override
   public void pause(Bundle B)
   //-------------------------
   {
      super.pause(B);
      if (B == null) return;
      if ( (completedBearings != null) && (! completedBearings.isEmpty()) )
      {
         long[] bearings = new long[completedBearings.size()];
         int i = 0;
         for (long bearing : completedBearings)
            bearings[i++] = bearing;
         B.putLongArray("TraverseRecordingThread.completedBearings", bearings);
      }
      else if (completedBearings == null)
         B.putLongArray("TraverseRecordingThread.completedBearings", null);
   }

   @Override
   public void restore(Bundle B)
   //---------------------------
   {
      super.restore(B);
      if (B != null)
      {
         long[] bearings = B.getLongArray("TraverseRecordingThread.completedBearings");
         if (bearings != null)
         {
            if (completedBearings == null)
               completedBearings = new HashSet<Long>();
            for (long bearing : bearings)
               completedBearings.add(bearing);
         }
      }
   }

   @Override
   protected Boolean doInBackground(Void... params)
   //--------------------------------------------
   {
//      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
      renderer.arrowColor = GLRecorderRenderer.GREEN;
      if (previewBuffer == null)
         previewBuffer = new byte[renderer.nv21BufferSize];
      totalCount = (int) (360.0f / recordingIncrement);
      ProgressParam progress = new ProgressParam();
      final long hundredMs = 100000000L;
      boolean b;
      try
      {
         float bearing = 0, lastBearing = -1;
         if (completedBearings == null)
            completedBearings = new HashSet<Long>();
         recordingCurrentBearing = -1;
         previewer.clearBuffer();
         previewer.awaitFrame(400, previewBuffer);
         startFrameWriter();
         processBearingThread = new ProcessBearingThread(processBearingQueue);
         startBearingProcessor(processBearingThread);
         long now;
         float roundedBearing = -1;
         count = 0;
         while ( (count < no) && (! processBearingThread.isComplete) && (renderer.isRecording) && (! isCancelled()) )
         {
            now = SystemClock.elapsedRealtimeNanos();
            BearingRingBuffer.RingBufferContent bearingInfo = bearingBuffer.pop();
            if (bearingInfo == null)
            {
               try { Thread.sleep(25); } catch (Exception _e) { break; }
               continue;
            }
            if ((now - bearingInfo.timestamp) > 200000000L)
            {
               bearingCondVar.close();
               if (! bearingCondVar.block(100))
               {
                  progress.set(lastBearing, recordingNextBearing, renderer.arrowColor, -1);
                  publishProgress(progress);
                  continue;
               }
               else
                  bearingInfo = bearingBuffer.pop();
               if (bearingInfo == null)
                  continue;
            }
            lastBearing = bearing;
            bearing = bearingInfo.bearing;
            if ((!renderer.isRecording) || (renderer.mustStopNow) || (isCancelled()) )
               break;
            if (bearing < 0)
            {
               renderer.arrowColor = GLRecorderRenderer.RED;
               progress.set(lastBearing, recordingNextBearing, renderer.arrowColor, -1);
               publishProgress(progress);
               continue;
            }

            long offset = (long) (Math.floor(bearing / recordingIncrement));
            synchronized(this) { b = ! completedBearings.contains(offset); }
            if (b)
            {
               try
               {
                  processBearingQueue.add(bearingInfo);
//                     if (! remainingBearings.remove(offset))
//                        Log.w(TAG, "No remove bearing " + bearing);
               }
               catch (Exception _e)
               {
                  Log.e(TAG, "", _e);
                  continue;
               }
               roundedBearing = ((++offset) * recordingIncrement) % 360;
            }
            else
            {
               progress.set(bearing, recordingNextBearing, renderer.arrowColor, -1);
               publishProgress(progress);
               renderer.arrowColor = GLRecorderRenderer.BLUE;
               renderer.requestRender();
               continue;
            }

            if ( (count < no) && (roundedBearing != recordingNextBearing) )
            {
               offset = -1;
               for (float bb=roundedBearing; bb<360.0f; bb+=recordingIncrement)
               {
                  long off = (long) Math.floor(bb / recordingIncrement);
                  if (! completedBearings.contains(off))
                  {
                     offset = off;
                     break;
                  }
               }
               if (offset < 0)
               {
                  for (float bb=0.0f; bb<roundedBearing; bb+=recordingIncrement)
                  {
                     long off = (long) Math.floor(bb / recordingIncrement);
                     if (! completedBearings.contains(off))
                     {
                        offset = off;
                        break;
                     }
                  }
                  if (offset < 0)
                  {
                     count = no;
                     break;
                  }
               }

               if (offset >= 0)
               {
                  recordingNextBearing = offset * recordingIncrement;
                  progress.set(bearing, recordingNextBearing, renderer.arrowColor,
                               (count * 100) / totalCount);
                  publishProgress(progress);
               }
               if ((bearing > 354) && ((recordingNextBearing >= 0) && (recordingNextBearing <= 5)))
               {
                  if (((360 - bearing) + recordingNextBearing) < 5)
                     renderer.arrowColor = GLRecorderRenderer.GREEN;
                  else
                     renderer.arrowColor = GLRecorderRenderer.BLUE;
               } else
               {
                  if (Math.abs(recordingNextBearing - bearing) < 5)
                     renderer.arrowColor = GLRecorderRenderer.GREEN;
                  else
                     renderer.arrowColor = GLRecorderRenderer.BLUE;
               }

               renderer.requestRender();
            }
         }
         stopFrameWriter();
         renderer.lastSaveBearing = 0;
         completedBearings.clear();
         completedBearings = null;
         pause(renderer.lastInstanceState);
         return true;
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         progress.setStatus("Exception in Record thread: " + e.getMessage(), 100, true, Toast.LENGTH_LONG);
         publishProgress(progress);
         renderer.isRecording = false;
      }
      finally
      {
         stopFrameWriter();
         stopBearingProcessor(processBearingThread);
      }
      return null;
   }


   class ProcessBearingThread implements Runnable, Stoppable
   //=======================================================
   {
      final ArrayBlockingQueue<BearingRingBuffer.RingBufferContent> queue;
      volatile boolean mustStop = false, isComplete = false;

      ProcessBearingThread(ArrayBlockingQueue<BearingRingBuffer.RingBufferContent> queue)
      //---------------------------------------------------------------------------------
      {
         this.queue = queue;
      }

      @Override public void stop() { mustStop = true; }

      @Override
      public void run()
      //-------------------------
      {
         BearingRingBuffer.RingBufferContent bi = null;
         final long epsilon = 65000000L;
         while ( (! mustStop) && (! isComplete) )
         {
            try
            {
               bi = queue.poll(50, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e)
            {
               break;
            }
            if ( (bi == null) || (bi.bearing < 0) )
               continue;
            float bearing = bi.bearing;
            long offset = (long) (Math.floor(bearing / recordingIncrement));
            if (completedBearings.contains(offset))
               continue;
            final long bearingTimeStamp = bi.timestamp;
            RecorderRingBuffer.RingBufferContent rbc = frameBuffer.findFirst(bearingTimeStamp, epsilon,
                                                                             previewBuffer);
            long ts = (rbc == null) ? -1 : rbc.timestamp;
            if (ts < 0) // && (bearingTimestamp < lastFrameTimestamp) )
            {
               final long now;
               now = SystemClock.elapsedRealtimeNanos();
               if ((now - bearingTimeStamp) <= epsilon)
               {
                  ts = previewer.awaitFrame(100, previewBuffer);
                  if (ts >= 0)
                     ts = bearingTimeStamp;
               }
            }
            if ((ts >= (bearingTimeStamp - epsilon)) && (ts <= (bearingTimeStamp + epsilon)))
            {
               if (addFrameToWriteBuffer(offset))
               {
                  if (IS_LOGCAT_GOT)
                     Log.i(TAG, "TraverseRecordingThread: Got " + bearing);
                  count++;
                  synchronized (this) { completedBearings.add(offset); }
                  lastFrameTimestamp = ts;
                  isComplete = (count >= no);
                  continue;
               }
            }
            if (rbc != null)
               rbc.isUsed = false;
            if (IS_LOGCAT_GOT)
               Log.i(TAG, "ProcessBearingThread: Missed " + bearing);
         }
      }
   }
}
