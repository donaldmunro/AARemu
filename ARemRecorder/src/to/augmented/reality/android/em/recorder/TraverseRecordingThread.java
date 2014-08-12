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
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.util.*;

public class TraverseRecordingThread extends RecordingThread implements Runnable, Freezeable
//=======================================================================================
{
   static final private String TAG = RetryRecordingThread.class.getSimpleName();
   SortedSet<Long> remainingBearings;
   int totalCount = 0, writtenCount = 0;

   protected TraverseRecordingThread(RecorderActivity activity, GLRecorderRenderer renderer)
   //-------------------------------------------------------------------------------
   {
      super(activity, renderer);
   }

   protected TraverseRecordingThread(RecorderActivity activity, GLRecorderRenderer renderer, int nv21BufferSize,
                                     float increment, CameraPreviewCallback previewer,
                                     ConditionVariable recordingCondVar, ConditionVariable frameCondVar,
                                     BearingRingBuffer bearingBuffer)
   //----------------------------------------------------------------------------------------------------------------
   {
      super(activity, renderer, nv21BufferSize, increment, previewer, recordingCondVar, frameCondVar, bearingBuffer);
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
   public void run()
   //--------------
   {
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
      renderer.arrowColor = GLRecorderRenderer.RED;
      if (previewBuffer == null)
         previewBuffer = new byte[renderer.nv21BufferSize];
      totalCount = (int) (360.0f / recordingIncrement);
      try
      {
         float bearing = 0, lastBearing;
         if (remainingBearings == null)
            initBearings();
         recordingCurrentBearing = -1;
         startFrameWriter();
         while ( (! remainingBearings.isEmpty()) && (renderer.isRecording) )
         {
            bearingCondVar.close();
            if (!bearingCondVar.block(400))
            {
               activity.onBearingChanged(renderer.currentBearing, recordingNextBearing, renderer.arrowColor, -1);
               continue;
            }
            lastBearing = bearing;
            BearingRingBuffer.RingBufferContent bearingInfo = bearingBuffer.peekHead();
            bearing = bearingInfo.bearing;
            long bearingTimeStamp = bearingInfo.timestamp;
            if ((!renderer.isRecording) || (renderer.mustStopNow))
               break;
            if (bearing < 0)
               continue;
            if (recordingCurrentBearing < 0)
               recordingCurrentBearing = bearing;
            long offset = (long) (Math.floor(bearing / recordingIncrement));
            if (remainingBearings.contains(offset))
            {
               long targetTimeStamp = bearingTimeStamp, epsilon = 50000000L;
               long ts = previewer.findBufferAtTimestamp(targetTimeStamp, epsilon, previewBuffer);
               if (ts < 0)
               {
                  previewer.bufferOff();
                  previewer.clearBuffer();
                  frameCondVar.close();
                  targetTimeStamp = SystemClock.elapsedRealtimeNanos();
                  previewer.bufferOn();
                  if (!frameCondVar.block(FRAME_BLOCK_TIME_MS))
                  {
                     renderer.arrowColor = GLRecorderRenderer.GREEN;
                     activity.onBearingChanged(bearing, recordingCurrentBearing, renderer.arrowColor, -1);
                     renderer.requestRender();
                     continue;
                  }
                  frameCondVar.close();
                  epsilon = FRAME_BLOCK_TIME_NS + 10000000L;
                  ts = previewer.findBufferAtTimestamp(targetTimeStamp, epsilon, previewBuffer);
               }
               if ( (ts >= (targetTimeStamp - epsilon)) && (ts <= (targetTimeStamp + epsilon)) )
               {
                  if (addFrameToWriteBuffer(offset))
                  {
                     remainingBearings.remove(offset);
                     writtenCount++;
                     renderer.arrowColor = GLRecorderRenderer.GREEN;
                     lastFrameTimestamp = ts;
                  }
                  else
                     renderer.arrowColor = GLRecorderRenderer.RED;
               }
               else
                  renderer.arrowColor = GLRecorderRenderer.RED;
            }
            if (! remainingBearings.isEmpty())
            {
               float nextBearing = (offset + 1) * recordingIncrement;
               if (nextBearing >= 360)
                  nextBearing -= 360;
               offset = (long) (Math.floor(nextBearing / recordingIncrement));
               SortedSet<Long> subset = remainingBearings.tailSet(offset);
               if (subset.isEmpty())
               {
                  if (nextBearing > 300)
                     subset = remainingBearings.tailSet(0L);
                  if (subset.isEmpty())
                     offset = -1;
                  else
                     offset = subset.first();
               }
               else
                  offset = subset.first();

//             offset = remainingBearings.tailSet(++offset).first();
               if (offset >= 0)
                  recordingNextBearing = offset * recordingIncrement;
               if (Math.abs(recordingNextBearing - bearing) < 5)
                  renderer.arrowColor = GLRecorderRenderer.GREEN;
               else
                  renderer.arrowColor = GLRecorderRenderer.BLUE;
            }
            renderer.requestRender();
            activity.onBearingChanged(bearing, recordingNextBearing, renderer.arrowColor,
                                      (writtenCount * 100) / totalCount);
         }
         if (remainingBearings.isEmpty())
         {
            stopFrameWriter();
            renderer.lastSaveBearing = 0;
            remainingBearings = null;
            renderer.stopRecording(false);
         }
         pause(renderer.lastInstanceState);
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         renderer.stopRecording(true);
         activity.updateStatus("Exception in Record thread: " + e.getMessage(), 100, true, Toast.LENGTH_LONG);
         renderer.isRecording = false;
      }
      finally
      {
         stopFrameWriter();
      }
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
}
