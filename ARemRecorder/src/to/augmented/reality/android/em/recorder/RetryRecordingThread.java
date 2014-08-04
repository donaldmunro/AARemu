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

public class RetryRecordingThread extends RecordingThread implements Runnable, Freezeable
//=======================================================================================
{
   static final private String TAG = RetryRecordingThread.class.getSimpleName();

   protected boolean  isCorrecting = false;
   protected float correctingBearing = 0;
   protected long writeFrameCount = 1;  // 0  is written in super.checkStartRecording

   protected RetryRecordingThread(RecorderActivity activity, GLRecorderRenderer renderer)
   //-------------------------------------------------------------------------------
   {
      super(activity, renderer);
   }

   protected RetryRecordingThread(RecorderActivity activity, GLRecorderRenderer renderer, int nv21BufferSize, float increment,
                                  CameraPreviewConvertCallback previewer,
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
   public void run()
   //--------------
   {
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
      if (! isStartRecording)
         startFrameWriter();
      renderer.arrowColor = GLRecorderRenderer.RED;
      if (previewBuffer == null)
         previewBuffer = new byte[renderer.nv21BufferSize];
      pause(renderer.lastInstanceState);
      try
      {
         float bearing =0, lastBearing;
         while (renderer.isRecording)
         {
            if (! bearingCondVar.block(400))
            {
               activity.onBearingChanged(renderer.currentBearing, recordingNextBearing, renderer.arrowColor, -1);
               continue;
            }
            bearingCondVar.close();
            lastBearing = bearing;
            bearing = bearingBuffer.peekLatestBearing();
            if ( (! renderer.isRecording) || (renderer.mustStopNow))
               break;
            if (bearing < 0)
               continue;
            if (isStartRecording)
               checkStartRecording(bearing);
            else
            {
               //               Log.d(TAG, "Search: " + recordingCurrentBearing + " - " + recordingNextBearing);
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
                     activity.onBearingChanged(bearing, recordingNextBearing, renderer.arrowColor, -1);
                  }
                  else
                  {
                     renderer.arrowRotation = 180;
                     renderer.arrowColor = GLRecorderRenderer.RED;
                     activity.onBearingChanged(bearing, correctingBearing, renderer.arrowColor, -1);
                  }
                  continue;
               }
               if ((bearing >= recordingCurrentBearing) && (bearing < recordingNextBearing))
               {
                  previewer.bufferOff();
                  previewer.clearBuffer();
                  frameCondVar.close();
                  final long now = SystemClock.elapsedRealtimeNanos();
                  previewer.bufferOn();
                  if (! frameCondVar.block(FRAME_BLOCK_TIME_MS))
                  {
                     activity.onBearingChanged(bearing, recordingNextBearing, renderer.arrowColor,
                                               (int) ((recordingCurrentBearing / 360.0f) * 100f));
                     renderer.requestRender();
                     continue;
                  }
                  frameCondVar.close();
                  final long ts = previewer.findBufferAtTimestamp(now, FRAME_BLOCK_TIME_NS + 10000000L, previewBuffer);
                  if (ts > now)
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
                        renderer.stopRecording(false);
                        break;
                     }
                     lastFrameTimestamp = ts;
                  }
                  else
                  {
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
               renderer.requestRender();
               activity.onBearingChanged(bearing, recordingNextBearing, renderer.arrowColor,
                                         (int) ((recordingCurrentBearing / 360.0f) * 100f));
            }
         }
         pause(renderer.lastInstanceState);
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         renderer.stopRecording(true);
         activity.updateStatus("Exception in Record thread", 100, true, Toast.LENGTH_LONG);
         renderer.isRecording = false;
      }
      finally
      {
         stopFrameWriter();
      }
   }

   protected boolean checkStartRecording(final float bearing)
   //------------------------------------------------------
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
            activity.onBearingChanged(bearing, 0, renderer.arrowColor, -1);
            renderer.requestRender();
            return false;
         }
         previewer.bufferOff();
         previewer.clearBuffer();
         frameCondVar.close();
         final long now = SystemClock.elapsedRealtimeNanos();
         previewer.bufferOn();
         if (! frameCondVar.block(FRAME_BLOCK_TIME_MS))
         {
            renderer.arrowColor = GLRecorderRenderer.RED;
            activity.onBearingChanged(bearing, 0, renderer.arrowColor, -1);
            return false;
         }
         frameCondVar.close();
//         Log.d(TAG, "Buffer: " + previewer.dumpBuffer());
         ts = previewer.findBufferAtTimestamp(now, FRAME_BLOCK_TIME_NS + 10000000L, previewBuffer);
         if (ts < now)
         {
            if (ts > 0)
               lastFrameTimestamp = ts;
            if (bearing > 270)
               renderer.arrowRotation = 0;
            else
               renderer.arrowRotation = 180;
            renderer.arrowColor = GLRecorderRenderer.RED;
            activity.onBearingChanged(bearing, 0, renderer.arrowColor, -1);
//                     try { Thread.sleep(100); } catch (InterruptedException e) { break; }
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
            renderer.arrowRotation = 180;
            renderer.arrowColor = GLRecorderRenderer.RED;
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
         activity.onBearingChanged(bearing, recordingCurrentBearing, renderer.arrowColor, -1);
      }
      renderer.requestRender();
      return false;
   }

}
