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
   protected long writeFrameCount = 1;  // 0  is written in super.checkStartRecording
   protected ProcessBearingThread processBearingThread = null;

   protected RetryRecordingThread(GLRecorderRenderer renderer)
   //-------------------------------------------------------------------------------
   {
      super(renderer);
   }

   protected RetryRecordingThread(GLRecorderRenderer renderer, int nv21BufferSize, float increment,
                                  CameraPreviewThread previewer,
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
      {
         startFrameWriter();
         processBearingThread = new ProcessBearingThread(processBearingQueue, progress);
         startBearingProcessor(processBearingThread);
      }
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
         while ( (renderer.isRecording) && (! isCancelled()) )
         {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
               now = SystemClock.elapsedRealtimeNanos();
            else
               now = System.nanoTime();
            if ( (processBearingThread != null) && (processBearingThread.isComplete) )
               break;
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
            if (isStartRecording)
               checkStartRecording(bearing, progress);
            else
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
         stopBearingProcessor(processBearingThread);
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
            progress.set(bearing, 0, renderer.arrowColor, -1);
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
            progress.set(bearing, 0, renderer.arrowColor, -1);
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
            progress.set(bearing, 0, renderer.arrowColor, -1);
            publishProgress(progress);
            rbc.isUsed = false;
            return false;
         }

         if (! startFrameWriter())
         {
            renderer.isRecording = false;
            return false;
         }
         processBearingThread = new ProcessBearingThread(processBearingQueue, progress);
         startBearingProcessor(processBearingThread);

         recordingCurrentBearing = 0;
         recordingNextBearing = recordingIncrement;
         isStartRecording = false;
         recordingCount = (int) (360.0f / recordingIncrement);
         if (addFrameToWriteBuffer(0))
         {
            recordingCurrentBearing = recordingNextBearing;
            recordingNextBearing =
                  (float) (Math.rint((recordingNextBearing + recordingIncrement) * 10.0f) / 10.0);
            if ((recordingNextBearing > 360) && (recordingCurrentBearing < 360))
               recordingNextBearing = 360;
            renderer.arrowRotation = 0;
            renderer.arrowColor = GLRecorderRenderer.GREEN;
            recordingCount--;
            lastFrameTimestamp = ts;
            return true;
         }
         else
         {
            renderer.isRecording = false;
            return false;
         }
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
         progress.set(bearing, recordingCurrentBearing, renderer.arrowColor, -1);
         publishProgress(progress);
      }
      renderer.requestRender();
      return false;
   }

   class ProcessBearingThread implements Runnable, Stoppable
   //=======================================================
   {
      final ArrayBlockingQueue<BearingRingBuffer.RingBufferContent> queue;
      volatile boolean mustStop = false, isComplete = false;
      ProgressParam progress;

      ProcessBearingThread(ArrayBlockingQueue<BearingRingBuffer.RingBufferContent> queue, ProgressParam progress)
      //---------------------------------------------------------------------------------------------------------
      {
         this.queue = queue;
         this.progress = progress;
      }

      @Override public void stop() { mustStop = true; }

      @Override
      public void run()
      //-------------------------
      {
         BearingRingBuffer.RingBufferContent bearingInfo = null;
         final long epsilon = 65000000L;
         float bearing = -1, lastBearing;
         Map<Long, Void> completed = new HashMap<Long, Void>();
         while ((! mustStop) && (! isComplete))
         {
            try
            {
               bearingInfo = queue.poll(50, TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e)
            {
               break;
            }
            if ((bearingInfo == null) || (bearingInfo.bearing < 0))
               continue;
            lastBearing = bearing;
            bearing = bearingInfo.bearing;

            if (isCorrecting)
            {
               if ( ( ( (correctingBearing >= 355) && (correctingBearing <= 360) ) &&
                     ( (bearing > 340) && (bearing < correctingBearing) ) ) ||
                     ( (correctingBearing > 0) && (bearing < correctingBearing) ) ||
                     ( (correctingBearing == 0) && ( (bearing > 340) && (bearing < 360) ) )
                     )
               {
                  isCorrecting = false;
                  renderer.arrowRotation = 0;
                  renderer.arrowColor = GLRecorderRenderer.GREEN;
                  progress.set(bearing, recordingNextBearing, renderer.arrowColor, -1);
                  publishProgress(progress);
               }
               else
               {
                  renderer.arrowRotation = 180;
                  renderer.arrowColor = GLRecorderRenderer.RED;
                  progress.set(bearing, correctingBearing, renderer.arrowColor, -1);
                  publishProgress(progress);
               }
               continue;
            }
            if ((bearing >= recordingCurrentBearing) && (bearing < recordingNextBearing))
            {
               long targetTimeStamp = bearingInfo.timestamp;
               RecorderRingBuffer.RingBufferContent rbc = previewer.findFirstBufferAtTimestamp(targetTimeStamp, epsilon,
                                                                                               previewBuffer);
               long ts = (rbc == null) ? -1 : rbc.timestamp;
               if (ts < 0)
               {
                  final long now;
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                     now = SystemClock.elapsedRealtimeNanos();
                  else
                     now = System.nanoTime();
                  if ((now - targetTimeStamp) <= epsilon)
                     ts = previewer.awaitFrame(90, previewBuffer);
               }
               if ( (ts >= (targetTimeStamp - epsilon)) && (ts <= (targetTimeStamp + epsilon)) )
               {
                  if (addFrameToWriteBuffer(writeFrameCount))
                  {
                     if (IS_LOGCAT_GOT)
                        Log.i(TAG, "RetryRecordingThread: Got " + recordingCurrentBearing + " (" + bearing + ")");
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
                  boolean isWrapped =  ( ( (bearing > 340) && ( (lastBearing >= 0) && (lastBearing < 20) ) ) ||
                        ( (bearing < 20) && (lastBearing >= 350) ) );
                  if ( (bearing > recordingNextBearing) && (! isWrapped) )
                  {
                     if (IS_LOGCAT_GOT)
                        Log.i(TAG, "RetryRecordingThread: Missed " + recordingCurrentBearing + " (" + bearing + ")");
                     renderer.arrowRotation = 180;
                     renderer.arrowColor = GLRecorderRenderer.RED;
                     lastFrameTimestamp++;
                  }
                  else
                  {
                     renderer.arrowRotation = 0;
                     renderer.arrowColor = GLRecorderRenderer.GREEN;
                  }
                  if (rbc != null)
                     rbc.isUsed = false;
               }
            }
            else
            {
               boolean isWrapped =  ( (bearing > 300) && ( (lastBearing >= 0) && (lastBearing < 100) ) );
               if ( (bearing > recordingNextBearing) && (! isWrapped) )
               {
                  isCorrecting = true;
                  correctingBearing = recordingCurrentBearing - 5;
                  if (correctingBearing < 0)
                     correctingBearing += 360;
                  renderer.arrowRotation = 180;
                  renderer.arrowColor = GLRecorderRenderer.RED;
                  if (IS_LOGCAT_GOT)
                     Log.i(TAG, "RetryRecordingThread: Missed " + recordingCurrentBearing + " (" + bearing + ")");

               }
               else
               {
                  renderer.arrowRotation = 0;
                  renderer.arrowColor = GLRecorderRenderer.GREEN;
               }
            }
            renderer.requestRender();
            progress.set(bearing, recordingNextBearing, renderer.arrowColor,
                         (int) ((recordingCurrentBearing / 360.0f) * 100f));
            publishProgress(progress);
         }
      }
   }
}
