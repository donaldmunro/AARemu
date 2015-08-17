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

import java.util.SortedSet;
import java.util.TreeSet;

public class TraverseRecordingThread2 extends RecordingThread implements Freezeable
//================================================================================
{
   static final private String TAG = TraverseRecordingThread.class.getSimpleName();
   SortedSet<Long> remainingBearings;
   int totalCount = 0, writtenCount = 0;

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
      final long hundredMs = 100000000L, epsilon = 60000000L;
      boolean b;
      try
      {
         float bearing = 0, lastBearing = -1, lastUIBearing = 1000;
         if (remainingBearings == null)
            initBearings();
         recordingCurrentBearing = -1;
         previewer.clearBuffer();
         startFrameWriter();
         long offset;
         float roundedBearing;
         long ts;
         previewer.awaitFrame(400, previewBuffer);
         while ( (! remainingBearings.isEmpty()) && (renderer.isRecording) && (! isCancelled()) )
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
               if ((remainingBearings.contains(offset)) && (addFrameToWriteBuffer(offset)))
               {
                  writtenCount++;
                  remainingBearings.remove(offset);
                  lastFrameTimestamp = ts;
                  if (IS_LOGCAT_GOT)
                     Log.i(TAG, "TraverseRecordingThread2: Got " + bearing);
                  renderer.arrowColor = GLRecorderRenderer.GREEN;
               } else if (! remainingBearings.contains(offset))
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
               else
                  renderer.arrowColor = GLRecorderRenderer.RED;
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

            if (remainingBearings.isEmpty())
               break;
            roundedBearing = (float) (offset + 1) * recordingIncrement;
            if (roundedBearing != recordingNextBearing)
            {
               if (roundedBearing >= 360)
                  roundedBearing -= 360;
               offset = (long) (Math.floor(roundedBearing / recordingIncrement));
               SortedSet<Long> subset = remainingBearings.tailSet(offset);
               if (subset.isEmpty())
               {
                  if (roundedBearing > 300)
                     subset = remainingBearings.tailSet(0L);
                  if (subset.isEmpty())
                     offset = - 1;
                  else
                     try { offset = subset.first(); } catch (Exception _e) { offset = - 1; }
               }
               else
                  try { offset = subset.first(); } catch (Exception _e) { offset = - 1; }
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
            else
            {
               bearing = (float) Math.floor(bearingBuffer.peekLatestBearing());
               if (Math.abs(bearing - lastUIBearing) >= 0.5)
               {
                  lastUIBearing = bearing;
                  progress.set(bearing, recordingNextBearing, renderer.arrowColor,
                               (writtenCount * 100) / totalCount);
                  publishProgress(progress);
               }
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
}
