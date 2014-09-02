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

import android.os.*;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

public class RetryRecordingThread extends RecordingThread implements Freezeable
//=============================================================================
{
   static final private String TAG = RetryRecordingThread.class.getSimpleName();

   protected boolean  isCorrecting = false;
   protected float correctingBearing = 0;
   protected long writeFrameCount = 1;

   protected RetryRecordingThread(GLRecorderRenderer renderer)
   //-------------------------------------------------------------------------------
   {
      super(renderer);
   }

   protected RetryRecordingThread(GLRecorderRenderer renderer, int nv21BufferSize, float increment,
                                  CameraPreviewCallback previewer,
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
      B.putBoolean("RetryRecordingThread.isCorrecting", isCorrecting);
      B.putFloat("RetryRecordingThread.correctingBearing", correctingBearing);
      B.putLong("RetryRecordingThread.writeFrameCount", writeFrameCount);
   }

   @Override
   public void restore(Bundle B)
   //---------------------------
   {
      super.restore(B);
      if (B != null)
      {
         isCorrecting = B.getBoolean("RetryRecordingThread.isCorrecting", false);
         correctingBearing = B.getFloat("RetryRecordingThread.correctingBearing", 0);
         writeFrameCount = B.getLong("RetryRecordingThread.writeFrameCount", 0);
      }
   }

   @Override
   protected Boolean doInBackground(Void... params)
   //-------------------------------------------
   {
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
      ProgressParam progress = new ProgressParam();
      if (! isStartRecording)
         startFrameWriter();
      renderer.arrowColor = GLRecorderRenderer.RED;
      if (previewBuffer == null)
         previewBuffer = new byte[renderer.nv21BufferSize];
      pause(renderer.lastInstanceState);
      try
      {
         previewer.clearBuffer();
         previewer.awaitFrame(400, previewBuffer);
         float bearing =0, lastBearing = -1;
         long now;
         while ( (isStartRecording) && (renderer.isRecording) && (! isCancelled()) )
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
               if (!bearingCondVar.block(100))
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
            if ((!renderer.isRecording) || (renderer.mustStopNow) || (isCancelled()))
               break;
            if (bearing < 0)
            {
               renderer.arrowColor = GLRecorderRenderer.RED;
               progress.set(lastBearing, recordingNextBearing, renderer.arrowColor);
               publishProgress(progress);
               continue;
            }
            checkStartRecording(bearing, progress);
         }

         long ts;
         boolean isComplete = false;
         final long epsilon = 65000000L;
         while ( (! isComplete) && (renderer.isRecording) && (! isCancelled()) )
         {
            if (isCorrecting)
            {
               BearingRingBuffer.RingBufferContent bearingInfo = bearingBuffer.peekHead();
               if (bearingInfo == null)
               {
                  bearingCondVar.close();
                  if (! bearingCondVar.block(100))
                  {
                     progress.set(lastBearing, recordingNextBearing, renderer.arrowColor);
                     publishProgress(progress);
                     continue;
                  }
                  bearingInfo = bearingBuffer.peekHead();
               }
               lastBearing = bearing;
               bearing = bearingInfo.bearing;
               if ( ( ( (correctingBearing >= 355) && (correctingBearing <= 360) ) &&
                     ( (bearing > 340) && (bearing < correctingBearing) ) ) ||
                     ( (correctingBearing > 0) && (bearing < correctingBearing) ) ||
                     ( (correctingBearing == 0) && ( (bearing > 340) && (bearing < 360) ) )
                     )
               {
                  isCorrecting = false;
                  renderer.arrowRotation = 0;
                  renderer.arrowColor = GLRecorderRenderer.GREEN;
                  progress.set(bearing, recordingNextBearing, renderer.arrowColor);
                  publishProgress(progress);
               }
               else
               {
                  renderer.arrowRotation = 180;
                  renderer.arrowColor = GLRecorderRenderer.RED;
                  progress.set(bearing, correctingBearing, renderer.arrowColor);
                  publishProgress(progress);
                  renderer.requestRender();
                  continue;
               }
            }
            ts = previewer.getLastBuffer(previewBuffer);
            if (ts < 0)
               ts = previewer.awaitFrame(200, previewBuffer);
            if (ts >= 0)
            {
               BearingRingBuffer.RingBufferContent bearingInfo = bearingBuffer.find(ts, epsilon);
               if (bearingInfo == null)
               {
                  bearingCondVar.close();
                  if (!bearingCondVar.block(100))
                  {
                     progress.set(lastBearing, recordingNextBearing, renderer.arrowColor);
                     publishProgress(progress);
                     continue;
                  }
                  else
                     bearingInfo = bearingBuffer.peekHead();
               }
               if (bearingInfo == null)
               {
                  progress.set(lastBearing, recordingNextBearing, renderer.arrowColor);
                  publishProgress(progress);
                  continue;
               }
               lastBearing = bearing;
               bearing = bearingInfo.bearing;
               final long bearingTimestamp = bearingInfo.timestamp;
               if ((!renderer.isRecording) || (renderer.mustStopNow) || (isCancelled()))
                  break;
               if (bearing < 0)
               {
                  renderer.arrowColor = GLRecorderRenderer.RED;
                  progress.set(lastBearing, recordingNextBearing, renderer.arrowColor);
                  publishProgress(progress);
                  continue;
               }
               if ((bearing >= recordingCurrentBearing) && (bearing < recordingNextBearing))
               {
                  if ( (ts >= (bearingTimestamp - epsilon)) && (ts <= (bearingTimestamp + epsilon)) )
                  {
                     if (addFrameToWriteBuffer(writeFrameCount))
                     {
                        recordingCount--;
                        writeFrameCount++;
                        recordingCurrentBearing = recordingNextBearing;
                        //                  recordingNextBearing = (float) Math.floor(recordingNextBearing + recordingIncrement);
                        recordingNextBearing = (float) (Math.rint((recordingNextBearing + recordingIncrement) * 10.0f) / 10.0);
                        if ((recordingNextBearing > 360) && (recordingCurrentBearing < 360))
                           recordingNextBearing = 360;
                        renderer.arrowRotation = 0;
                        renderer.arrowColor = GLRecorderRenderer.GREEN;
                     }
                     else
                     {
                        renderer.arrowRotation = 180;
                        renderer.arrowColor = GLRecorderRenderer.RED;
                     }
                     if ((recordingCount < 0) || (recordingNextBearing > 360))
                     {
                        stopFrameWriter();
                        renderer.lastSaveBearing = 0;
                        isComplete = true;
                     }
                     lastFrameTimestamp = ts;
                  }
                  else
                  {
                     bearing = bearingBuffer.peekLatestBearing();
                     lastBearing = bearingBuffer.findLess(bearing);
                     boolean isWrapped =  ( ( (bearing > 340) && ( (lastBearing >= 0) && (lastBearing < 20) ) ) ||
                           ( (bearing < 20) && (lastBearing >= 350) ) );
                     if ( (bearing > recordingNextBearing) && (! isWrapped) )
                     {
                        renderer.arrowRotation = 180;
                        renderer.arrowColor = GLRecorderRenderer.RED;
                        lastFrameTimestamp++;
                     }
                     else
                     {
                        renderer.arrowRotation = 0;
                        renderer.arrowColor = GLRecorderRenderer.GREEN;
                     }
                  }
               }
               else
               {
                  bearing = bearingBuffer.peekLatestBearing();
                  lastBearing = bearingBuffer.findLess(bearing);
                  boolean isWrapped =  ( (bearing > 300) && ( (lastBearing >= 0) && (lastBearing < 100) ) );
                  if ( (bearing > recordingNextBearing) && (! isWrapped) )
                  {
                     isCorrecting = true;
                     correctingBearing = recordingCurrentBearing - 5;
                     if (correctingBearing < 0)
                        correctingBearing += 360;
                     renderer.arrowRotation = 180;
                     renderer.arrowColor = GLRecorderRenderer.RED;
                  }
                  else
                  {
                     renderer.arrowRotation = 0;
                     renderer.arrowColor = GLRecorderRenderer.GREEN;
                  }
               }
               progress.set(lastBearing, recordingNextBearing, renderer.arrowColor);
               publishProgress(progress);
               renderer.requestRender();
            }
            else
            {
               bearing = bearingBuffer.peekLatestBearing();
               lastBearing = bearingBuffer.findLess(bearing);
               boolean isWrapped =  ( (bearing > 300) && ( (lastBearing >= 0) && (lastBearing < 100) ) );
               if ( (bearing > recordingNextBearing) && (! isWrapped) )
               {
                  isCorrecting = true;
                  correctingBearing = recordingCurrentBearing - 5;
                  if (correctingBearing < 0)
                     correctingBearing += 360;
                  renderer.arrowRotation = 180;
                  renderer.arrowColor = GLRecorderRenderer.RED;
               }
               else
               {
                  renderer.arrowRotation = 0;
                  renderer.arrowColor = GLRecorderRenderer.GREEN;
               }
               progress.set(lastBearing, recordingNextBearing, renderer.arrowColor);
               publishProgress(progress);
               renderer.requestRender();
            }
         }
         pause(renderer.lastInstanceState);
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
      return false;
   }

   protected boolean checkStartRecording(final float bearing, final ProgressParam progress)
   //--------------------------------------------------------------------------------------
   {
      long ts;
      if ( (bearing >= recordingCurrentBearing) && (bearing < recordingNextBearing) )
      {
         if (recordingCurrentBearing == dummyStartBearing)
         {
            recordingCurrentBearing = 0;
            recordingNextBearing = recordingIncrement;
            renderer.arrowRotation = 0;
            renderer.arrowColor = GLRecorderRenderer.GREEN;
            progress.set(bearing, 0, renderer.arrowColor);
            publishProgress(progress);
            renderer.requestRender();
            return false;
         }
         previewer.bufferOff();
         previewer.clearBuffer();
         final long now;
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
            now = SystemClock.elapsedRealtimeNanos();
         else
            now = System.nanoTime();
         previewer.bufferOn();
         frameCondVar.close();
         if (! frameCondVar.block(FRAME_BLOCK_TIME_MS))
         {
            renderer.arrowColor = GLRecorderRenderer.RED;
            progress.set(bearing, 0, renderer.arrowColor);
            publishProgress(progress);
            return false;
         }
         frameCondVar.close();
//         Log.d(TAG, "Buffer: " + previewer.dumpBuffer());
         RecorderRingBuffer.RingBufferContent rbc = previewer.findFirstBufferAtTimestamp(now,
                                                                                         FRAME_BLOCK_TIME_NS + 10000000L,
                                                                                         previewBuffer);
         ts = (rbc == null) ? -1 : rbc.timestamp;
         if (ts < now)
         {
            if (ts > 0)
               lastFrameTimestamp = ts;
            if (bearing > 270)
               renderer.arrowRotation = 0;
            else
               renderer.arrowRotation = 180;
            renderer.arrowColor = GLRecorderRenderer.RED;
            progress.set(bearing, 0, renderer.arrowColor);
            publishProgress(progress);
            rbc.isUsed = false;
            return false;
         }

         if (! startFrameWriter())
         {
            renderer.isRecording = false;
            return false;
         }
         recordingCurrentBearing = 0;
         recordingNextBearing = recordingIncrement;
         isStartRecording = false;
         recordingCount = (int) (360.0f / recordingIncrement);
         renderer.arrowRotation = 0;
         renderer.arrowColor = GLRecorderRenderer.GREEN;
         return true;
      }
      else
      {
         if (bearing < 0)
            return false;
         if ( (bearing > recordingIncrement) && (bearing < 180) )
         {
            renderer.arrowRotation = 180;
            renderer.arrowColor = GLRecorderRenderer.RED;
         }
         else
         {
            renderer.arrowRotation = 0;
            renderer.arrowColor = GLRecorderRenderer.GREEN;
         }
         progress.set(bearing, recordingCurrentBearing, renderer.arrowColor);
         publishProgress(progress);
      }
      renderer.requestRender();
      return false;
   }
}
