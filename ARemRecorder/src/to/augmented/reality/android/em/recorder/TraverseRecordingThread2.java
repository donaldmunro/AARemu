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
import android.util.Log;
import android.widget.Toast;

import java.util.HashSet;
import java.util.Set;

public class TraverseRecordingThread2 extends RecordingThread implements Freezeable
//================================================================================
{
   static final private String TAG = TraverseRecordingThread.class.getSimpleName();
   Set<Long> completedBearings;
   int totalCount = 0;

   protected TraverseRecordingThread2(GLRecorderRenderer renderer, RecorderRingBuffer frameBuffer) { super(renderer, frameBuffer); }

   protected TraverseRecordingThread2(GLRecorderRenderer renderer, int nv21BufferSize,
                                      float increment, CameraPreviewThread previewer,
                                      ConditionVariable recordingCondVar, ConditionVariable frameCondVar,
                                      RecorderRingBuffer frameBuffer, BearingRingBuffer bearingBuffer)
   //----------------------------------------------------------------------------------------------------------------
   {
      super(renderer, nv21BufferSize, increment, previewer, recordingCondVar, frameCondVar, frameBuffer, bearingBuffer);
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
      final long hundredMs = 100000000L, epsilon = 60000000L;
      boolean b;
      try
      {
         float bearing = 0, lastBearing = -1, lastUIBearing = 1000;
         if (completedBearings == null)
            completedBearings = new HashSet<Long>();
         recordingCurrentBearing = -1;
         previewer.clearBuffer();
         startFrameWriter();
         long offset;
         float roundedBearing;
         int no = (int) (Math.floor(360.0 / recordingIncrement)), count = 0;
         long ts;
         previewer.awaitFrame(400, previewBuffer);
         while ( (count < no) && (renderer.isRecording) && (! isCancelled()) )
         {
            ts = frameBuffer.peek(previewBuffer);
            if (ts < 0)
               ts = previewer.awaitFrame(200, previewBuffer);
            if (ts >= 0)
            {
               BearingRingBuffer.RingBufferContent bearingInfo = bearingBuffer.find(ts, epsilon);
               if (bearingInfo == null)
               {
                  bearingCondVar.close();
                  if (! bearingCondVar.block(100))
                  {
                     if (Math.abs(lastBearing - lastUIBearing) >= 0.5)
                     {
                        lastUIBearing = lastBearing;
                        progress.set(lastBearing, recordingNextBearing, renderer.arrowColor, -1);
                        publishProgress(progress);
                     }
                     continue;
                  } else
                     bearingInfo = bearingBuffer.peekHead();
               }
               if (bearingInfo == null)
               {
                  if (Math.abs(lastBearing - lastUIBearing) >= 0.5)
                  {
                     lastUIBearing = lastBearing;
                     progress.set(lastBearing, recordingNextBearing, renderer.arrowColor, -1);
                     publishProgress(progress);
                  }
                  continue;
               }
               lastBearing = bearing;
               bearing = bearingInfo.bearing;
               if ((! renderer.isRecording) || (renderer.mustStopNow) || (isCancelled()))
                  break;
               if (bearing < 0)
               {
                  renderer.arrowColor = GLRecorderRenderer.RED;
                  progress.set(lastBearing, recordingNextBearing, renderer.arrowColor, -1);
                  publishProgress(progress);
                  continue;
               }
//               if (bearing < lastBearing)
//               {
//                  boolean isWrapped = (((lastBearing > 350) && (lastBearing <= 360)) &&
//                        ((bearing >= 0) && (bearing < 10)));
//                  if (! isWrapped)
//                  {
//                     renderer.arrowColor = GLRecorderRenderer.RED;
//                     progress.set(lastBearing, recordingNextBearing, renderer.arrowColor);
//                     publishProgress(progress);
//                     continue;
//                  }
//               }
               offset = (long) (Math.floor(bearing / recordingIncrement));
               if (! completedBearings.contains(offset))
               {
                  if (addFrameToWriteBuffer(offset))
                  {
                     count++;
                     completedBearings.add(offset);
                     lastFrameTimestamp = ts;
                     if (IS_LOGCAT_GOT)
                        Log.i(TAG, "TraverseRecordingThread2: Got " + bearing);
                     renderer.arrowColor = GLRecorderRenderer.GREEN;
                  }
                  else
                     renderer.arrowColor = GLRecorderRenderer.RED;
               }
               else
               {
                  renderer.arrowColor = GLRecorderRenderer.BLUE;
                  if (Math.abs(bearing - lastUIBearing) >= 0.5)
                  {
                     lastUIBearing = bearing;
                     progress.set(bearing, recordingNextBearing, renderer.arrowColor, -1);
                     publishProgress(progress);
                  }
                  continue;
               }
            }
            else
            {
               BearingRingBuffer.RingBufferContent bearingInfo = bearingBuffer.peekHead();
               if (bearingInfo == null)
               {
                  bearingCondVar.close();
                  if (! bearingCondVar.block(100))
                  {
                     if (Math.abs(lastBearing - lastUIBearing) >= 0.5)
                     {
                        lastUIBearing = lastBearing;
                        progress.set(lastBearing, recordingNextBearing, renderer.arrowColor, -1);
                        publishProgress(progress);
                     }
                     continue;
                  }
                  bearingInfo = bearingBuffer.peekHead();
               }
               lastBearing = bearing;
               bearing = bearingInfo.bearing;
               offset = (long) (Math.floor(bearing / recordingIncrement));
            }

            if (count >= no)
               break;
            roundedBearing = ((++offset) * recordingIncrement) % 360;
            if (roundedBearing != recordingNextBearing)
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
            else
            {
               bearing = (float) Math.floor(bearingBuffer.peekLatestBearing());
               if (Math.abs(bearing - lastUIBearing) >= 0.5)
               {
                  lastUIBearing = bearing;
                  progress.set(bearing, recordingNextBearing, renderer.arrowColor,
                               (count * 100) / totalCount);
                  publishProgress(progress);
               }
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
      }
      return null;
   }

//   private void initBearings()
//   //-------------------------
//   {
//      remainingBearings = new TreeSet<Long>();
//      float bearing = 0;
//      long offset;
//      while (bearing < 360)
//      {
//         offset = (long) (Math.floor(bearing / recordingIncrement));
//         remainingBearings.add(offset);
//         bearing = (float) (Math.rint((bearing + recordingIncrement) * 10.0f) / 10.0);
//      }
//      pause(renderer.lastInstanceState);
//   }
}
