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

import android.os.Build;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.*;

public class TraverseRecordingThread extends RecordingThread implements Freezeable
//================================================================================
{
   static final private String TAG = TraverseRecordingThread.class.getSimpleName();
   SortedSet<Long> remainingBearings;
   int totalCount = 0, writtenCount = 0;
   protected ProcessBearingThread processBearingThread = null;

   protected TraverseRecordingThread(GLRecorderRenderer renderer) { super(renderer); }

   protected TraverseRecordingThread(GLRecorderRenderer renderer, int nv21BufferSize,
                                     float increment, CameraPreviewThread previewer,
                                     ConditionVariable recordingCondVar, ConditionVariable frameCondVar,
                                     BearingRingBuffer bearingBuffer)
   //----------------------------------------------------------------------------------------------------------------
   {
      super(renderer, nv21BufferSize, increment, previewer, recordingCondVar, frameCondVar, bearingBuffer);
   }

   @Override
   public void pause(Bundle B)
   //-------------------------
   {
      super.pause(B);
      if (B == null) return;
      if ( (remainingBearings != null) && (! remainingBearings.isEmpty()) )
      {
         long[] bearings = new long[remainingBearings.size()];
         int i = 0;
         for (long bearing : remainingBearings)
            bearings[i++] = bearing;
         B.putLongArray("TraverseRecordingThread.remainingBearings", bearings);
      }
      else if (remainingBearings == null)
         B.putLongArray("TraverseRecordingThread.remainingBearings", null);
   }

   @Override
   public void restore(Bundle B)
   //---------------------------
   {
      super.restore(B);
      if (B != null)
      {
         long[] bearings = B.getLongArray("TraverseRecordingThread.remainingBearings");
         if (bearings != null)
         {
            if (remainingBearings == null)
               remainingBearings = new TreeSet<Long>();
            for (long bearing : bearings)
               remainingBearings.add(bearing);
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
         if (remainingBearings == null)
            initBearings();
         recordingCurrentBearing = -1;
         previewer.clearBuffer();
         previewer.awaitFrame(400, previewBuffer);
         startFrameWriter();
         processBearingThread = new ProcessBearingThread(processBearingQueue);
         startBearingProcessor(processBearingThread);
         long now;
         float roundedBearing = -1;
         while ( (! processBearingThread.isComplete) && (renderer.isRecording) && (! isCancelled()) )
         {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
               now = SystemClock.elapsedRealtimeNanos();
            else
               now = System.nanoTime();
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
                  progress.set(lastBearing, recordingNextBearing, renderer.arrowColor);
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
               progress.set(lastBearing, recordingNextBearing, renderer.arrowColor);
               publishProgress(progress);
               continue;
            }
//            if (bearing < lastBearing)
//            {
//               boolean isWrapped =  ( ( (lastBearing > 350) && (lastBearing <= 360) ) &&
//                                   ( (bearing >= 0) && (bearing < 10) ) );
//               if (! isWrapped)
//               {
//                  renderer.arrowColor = GLRecorderRenderer.RED;
//                  progress.set(lastBearing, recordingNextBearing, renderer.arrowColor);
//                  publishProgress(progress);
//                  continue;
//               }
//            }
            long offset = (long) (Math.floor(bearing / recordingIncrement));
            synchronized(this) { b = remainingBearings.contains(offset); }
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
               roundedBearing = (float) (offset + 1) * recordingIncrement;
            }


            synchronized(this) { b = remainingBearings.isEmpty(); }
            if ( (! b) && (roundedBearing != recordingNextBearing) )
            {
               if (roundedBearing >= 360)
                  roundedBearing -= 360;
               offset = (long) (Math.floor(roundedBearing / recordingIncrement));
               synchronized(this)
               {
                  SortedSet<Long> subset = remainingBearings.tailSet(offset);
                  if (subset.isEmpty())
                  {
                     if (roundedBearing > 300)
                        subset = remainingBearings.tailSet(0L);
                     if (subset.isEmpty())
                        offset = -1;
                     else
                        try { offset = subset.first(); } catch (Exception _e) { offset = - 1; }
                  } else
                     try { offset = subset.first(); } catch (Exception _e) { offset = - 1; }
               }

               if (offset >= 0)
               {
                  recordingNextBearing = offset * recordingIncrement;
                  progress.set(bearing, recordingNextBearing, renderer.arrowColor,
                               (writtenCount * 100) / totalCount);
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
         remainingBearings = null;
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

   private void initBearings()
   //-------------------------
   {
      remainingBearings = new TreeSet<Long>();
      float bearing = 0;
      long offset;
      while (bearing < 360)
      {
         offset = (long) (Math.floor(bearing / recordingIncrement));
         remainingBearings.add(offset);
         bearing = (float) (Math.rint((bearing + recordingIncrement) * 10.0f) / 10.0);
      }
      pause(renderer.lastInstanceState);
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
         Map<Long, Void> completed = new HashMap<Long, Void>();
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
            if (completed.containsKey(offset))
               continue;
            final long bearingTimeStamp = bi.timestamp;
            RecorderRingBuffer.RingBufferContent rbc = previewer.findFirstBufferAtTimestamp(bearingTimeStamp, epsilon,
                                                                                            previewBuffer);
            long ts = (rbc == null) ? -1 : rbc.timestamp;
            if (ts < 0) // && (bearingTimestamp < lastFrameTimestamp) )
            {
               final long now;
               if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                  now = SystemClock.elapsedRealtimeNanos();
               else
                  now = System.nanoTime();
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
                  writtenCount++;
                  synchronized (this) { remainingBearings.remove(offset);  }
                  completed.put(offset, null);
                  lastFrameTimestamp = ts;
                  isComplete = remainingBearings.isEmpty();
                  Log.i(TAG, "ProcessBearingThread: Got " + bearing);
                  continue;
               }
            }
            if (rbc != null)
               rbc.isUsed = false;
            Log.i(TAG, "ProcessBearingThread: Missed " + bearing);
         }
      }
   }
}
