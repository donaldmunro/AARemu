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
import android.util.*;
import android.widget.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class RecordingThread implements Runnable, Freezeable
//==========================================================
{
   static final private String TAG = RecordingThread.class.getSimpleName();
   static final private long FRAME_BLOCK_TIME_MS = 110, FRAME_BLOCK_TIME_NS = 110000000L;
   static final private int FRAMEWRITE_QUEUE_SIZE = 3;

   private GLRecorderRenderer renderer;
   float recordingIncrement = 0, recordingCurrentBearing = 0, recordingNextBearing = 0;
   int recordingCount = 0;
   long lastFrameTimestamp = -1;
   final float dummyStartBearing = 355;
   boolean isStartRecording, isCorrecting = false;
   float correctingBearing = 0;
   private final RecorderActivity activity;
   private ConditionVariable recordingCondVar = null, frameCondVar = null;
   private CameraPreviewConvertCallback previewer;
   byte[] previewBuffer = null;
   BearingRingBuffer bearingBuffer = null;
   final ArrayBlockingQueue<byte[]> bufferQueue = new ArrayBlockingQueue<byte[]>(FRAMEWRITE_QUEUE_SIZE);
   private ExecutorService frameWriterExecutor;
   private Future<?> frameWriterFuture;
   long writeFrameCount = 0;
   private FrameWriterThread frameWriterThread = null;

   public RecordingThread(GLRecorderRenderer renderer, float increment, ConditionVariable recordingCondVar,
                          ConditionVariable frameCondVar, BearingRingBuffer bearingBuffer)
   //----------------------------------------------------------------------------------------------------------------
   {
      this.renderer = renderer;
      activity = renderer.activity;
      previewBuffer = renderer.previewBuffer;
      this.previewer = renderer.previewer;
      isStartRecording = true;
      this.recordingCondVar = recordingCondVar;
      this.frameCondVar = frameCondVar;
      this.bearingBuffer = bearingBuffer;
      recordingIncrement = (float) (Math.rint(increment*10.0f)/10.0);
      recordingCurrentBearing = dummyStartBearing;
      recordingNextBearing = (float) (Math.rint((recordingCurrentBearing + recordingIncrement) * 10.0f) / 10.0);
   }

   public RecordingThread(GLRecorderRenderer renderer, ConditionVariable recordingCondVar,
                          ConditionVariable frameCondVar, BearingRingBuffer bearingBuffer)
   //----------------------------------------------------------------------------------------------------------------
   {
      this.renderer = renderer;
      activity = renderer.activity;
      previewBuffer = renderer.previewBuffer;
      this.previewer = renderer.previewer;
      isStartRecording = true;
      this.recordingCondVar = recordingCondVar;
      this.frameCondVar = frameCondVar;
      this.bearingBuffer = bearingBuffer;
   }

   public RecordingThread(GLRecorderRenderer renderer)
   //-------------------------------------------------
   {
      this.renderer = renderer;
      activity = renderer.activity;
   }


   public RecordingThread setPreviewer(CameraPreviewConvertCallback previewer) { this.previewer = previewer; return this; }

   public RecordingThread setPreviewBuffer(byte[] previewBuffer) { this.previewBuffer = previewBuffer; return this; }

   public RecordingThread setRecordingCondVar(ConditionVariable recordingCondVar) { this.recordingCondVar = recordingCondVar; return this; }

   public RecordingThread setFrameCondVar(ConditionVariable frameCondVar) { this.frameCondVar = frameCondVar; return this; }

   public RecordingThread setBearingBuffer(BearingRingBuffer bearingBuffer) { this.bearingBuffer = bearingBuffer; return this; }

   public void pause(Bundle B)
   //-------------------------
   {
      if (B == null) return;
      B.putFloat("RecordingThread.recordingNextBearing", recordingNextBearing);
      B.putFloat("RecordingThread.recordingCurrentBearing", recordingCurrentBearing);
      B.putFloat("RecordingThread.recordingIncrement", recordingIncrement);
      B.putInt("RecordingThread.recordingCount", recordingCount);
      B.putBoolean("RecordingThread.isCorrecting", isCorrecting);
      B.putBoolean("RecordingThread.isStartRecording", isStartRecording);
      B.putFloat("RecordingThread.correctingBearing", correctingBearing);
      B.putLong("RecordingThread.writeFrameCount", writeFrameCount);
   }

   public void restore(Bundle B)
   //---------------------------
   {
      if (B != null)
      {
         recordingNextBearing = B.getFloat("RecordingThread.recordingNextBearing", 0);
         recordingCurrentBearing = B.getFloat("RecordingThread.recordingCurrentBearing", 0);
         recordingIncrement = B.getFloat("RecordingThread.recordingIncrement", 0);
         recordingCount = B.getInt("RecordingThread.recordingCount", 0);
         isStartRecording = B.getBoolean("RecordingThread.isStartRecording", true);
         isCorrecting = B.getBoolean("RecordingThread.isCorrecting", false);
         correctingBearing = B.getFloat("RecordingThread.correctingBearing", 0);
         writeFrameCount = B.getLong("RecordingThread.writeFrameCount", 0);
      }
   }

   @Override
   public void run()
   //--------------
   {
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
      if (! isStartRecording)
         startFrameWriter();
      renderer.isArrowRed = true;
      if (previewBuffer == null)
         previewBuffer = renderer.previewBuffer;
      pause(renderer.lastInstanceState);
      try
      {
         while (renderer.isRecording)
         {
            if (! recordingCondVar.block(400))
            {
               activity.onBearingChanged(renderer.currentBearing, recordingNextBearing, renderer.isArrowRed, false);
               continue;
            }
            recordingCondVar.close();
            float bearing = bearingBuffer.peekLatestBearing();
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
                     activity.onBearingChanged(bearing, recordingNextBearing, false, false);
                  }
                  else
                  {
                     renderer.arrowRotation = 180;
                     activity.onBearingChanged(bearing, correctingBearing, true, false);
                  }
                  renderer.isArrowRed = true;
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
                     activity.onBearingChanged(bearing, recordingNextBearing, renderer.isArrowRed, true);
                     renderer.requestRender();
                     continue;
                  }
                  frameCondVar.close();
                  final long ts = previewer.getBufferAtTimestamp(now, FRAME_BLOCK_TIME_NS + 10000000L, previewBuffer);
                  if (ts > now)
                  {
                     //                     Log.d(TAG, "Search: found " + bearing + " " + ts);
                     if (bufferQueue.offer(Arrays.copyOf(previewBuffer, previewBuffer.length)))
                     {
                        recordingCount--;
                        recordingCurrentBearing = recordingNextBearing;
                        //                  recordingNextBearing = (float) Math.floor(recordingNextBearing + recordingIncrement);
                        recordingNextBearing =
                              (float) (Math.rint((recordingNextBearing + recordingIncrement) * 10.0f) / 10.0);
                        if ((recordingNextBearing > 360) && (recordingCurrentBearing < 360))
                           recordingNextBearing = 360;
                        renderer.arrowRotation = 0;
                        renderer.isArrowRed = false;
                     }
                     else
                     {
                        renderer.arrowRotation = 180;
                        renderer.isArrowRed = true;
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
                     bearing = bearingBuffer.peekLatestBearing();
                     if (bearing > recordingNextBearing)
                     {
                        renderer.arrowRotation = 180;
                        renderer.isArrowRed = true;
                        lastFrameTimestamp++;
                     }
                     else
                     {
                        renderer.arrowRotation = 0;
                        renderer.isArrowRed = false;
                     }
                  }
               }
               else
               {
                  if (bearing > recordingNextBearing)
                  {
                     isCorrecting = true;
                     correctingBearing = recordingCurrentBearing - 5;
                     if (correctingBearing < 0)
                        correctingBearing += 360;
                     renderer.arrowRotation = 180;
                     renderer.isArrowRed = true;
                  }
                  else
                  {
                     renderer.arrowRotation = 0;
                     renderer.isArrowRed = false;
                  }
               }
               renderer.requestRender();
               activity.onBearingChanged(bearing, recordingNextBearing, renderer.isArrowRed, true);
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

   private boolean checkStartRecording(final float bearing)
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
            activity.onBearingChanged(bearing, 0, true, false);
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
            activity.onBearingChanged(bearing, 0, true, false);
            return false;
         }
         frameCondVar.close();
//         Log.d(TAG, "Buffer: " + previewer.dumpBuffer());
         ts = previewer.getBufferAtTimestamp(now, FRAME_BLOCK_TIME_NS + 10000000L, previewBuffer);
         if (ts < now)
         {
            if (ts > 0)
               lastFrameTimestamp = ts;
            if (bearing > 270)
               renderer.arrowRotation = 0;
            else
               renderer.arrowRotation = 180;
            renderer.isArrowRed = true;
            activity.onBearingChanged(bearing, 0, true, false);
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
         if (bufferQueue.offer(Arrays.copyOf(previewBuffer, previewBuffer.length)))
         {
            recordingCurrentBearing = recordingNextBearing;
            recordingNextBearing =
                  (float) (Math.rint((recordingNextBearing + recordingIncrement) * 10.0f) / 10.0);
            if ((recordingNextBearing > 360) && (recordingCurrentBearing < 360))
               recordingNextBearing = 360;
            renderer.arrowRotation = 0;
            renderer.isArrowRed = false;
            recordingCount--;
            lastFrameTimestamp = ts;
            return true;
         }
         else
         {
            Log.i(TAG, "Buffer queue full : " + bufferQueue.remainingCapacity());
            renderer.arrowRotation = 180;
            renderer.isArrowRed = true;
            return false;
         }
      }
      else
      {
         if (bearing < 0)
            return false;
         if (bearing > 300)
            renderer.arrowRotation = 0;
         else
            renderer.arrowRotation = 180;
         renderer.isArrowRed = true;
         activity.onBearingChanged(bearing, recordingCurrentBearing, true, false);
      }
      renderer.requestRender();
      return false;
   }

   public boolean startFrameWriter()
   //-----------------------------------
   {
      if (frameWriterFuture != null)
         stopFrameWriter();
      try
      {
         frameWriterThread = new FrameWriterThread();
      }
      catch (FileNotFoundException _e)
      {
         Log.e(TAG, "Frame file permission error ? (" + renderer.recordFramesFile.getAbsolutePath() + ")", _e);
         activity.updateStatus("Frame file permission error ? (" + renderer.recordFramesFile.getAbsolutePath() + ")",
                               100, true, Toast.LENGTH_LONG);
         return false;
      }
      frameWriterExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
      {
         @Override public Thread newThread(Runnable r)
         //-------------------------------------------
         {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("FrameWriter");
            return t;
         }
      });
      frameWriterFuture = frameWriterExecutor.submit(frameWriterThread);
      return true;
   }

   private void stopFrameWriter()
   //--------------------------
   {
      if ( (frameWriterThread != null) && (frameWriterFuture != null) && (! frameWriterFuture.isDone()))
      {  // @formatter:off
         frameWriterThread.mustStop = true;
         if (bufferQueue != null)
            try { bufferQueue.put(new byte[0]); } catch (InterruptedException _e) { Log.e(TAG, "", _e); }
         if (! frameWriterFuture.isDone())
         {
            try
            {
               frameWriterFuture.get(60, TimeUnit.MILLISECONDS);
            }
            catch (Exception e)
            {
               frameWriterFuture.cancel(true);
               try { frameWriterExecutor.shutdownNow(); } catch (Exception _e) { }
               if (frameWriterThread.framesRAF != null)
                  try { frameWriterThread.framesRAF.close(); } catch (Exception _e) { }
            }
         }
      } // @formatter:on
      if ( (frameWriterThread != null) && (frameWriterThread.framesRAF != null) )
         try { frameWriterThread.framesRAF.close(); } catch (Exception _e) { }
      frameWriterThread = null;
      frameWriterFuture = null;
   }

   public float getNextBearing() { return recordingNextBearing; }

   class FrameWriterThread implements Runnable
   //========================================
   {
      private final RandomAccessFile framesRAF;
      final int bufferSize = renderer.nv21BufferSize;
      boolean mustStop = false;

      FrameWriterThread() throws FileNotFoundException
      //---------------------------------------------
      {
         final String name;
         name = (renderer.recordFileName == null) ? "Unknown" : renderer.recordFileName;
         renderer.recordFramesFile = new File(renderer.recordDir, name + ".frames.part");
         framesRAF = new RandomAccessFile(renderer.recordFramesFile, "rw");
      }

      @Override
      public void run()
      //--------------
      {
         Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE);
         byte[] frameBuffer;
         try
         {
            while (true)
            {
               if ( (mustStop) && (bufferQueue.isEmpty()) )
                  break;
               try { frameBuffer = bufferQueue.take(); } catch (InterruptedException e) { break; }
               if ( (frameBuffer == null) || (frameBuffer.length < bufferSize) ) continue;
               try
               {
                  framesRAF.seek(writeFrameCount++*bufferSize);
                  framesRAF.write(frameBuffer);
               }
               catch (IOException e)
               {
                  Log.e(TAG, "Error seeking/writing frame", e);
                  throw (new RuntimeException("Error seeking/writing frame", e));
               }
            }
         }
         finally
         {
            try { framesRAF.close(); } catch (Exception _e) { Log.e(TAG, "", _e); }
//            Log.i(TAG, "Frame file: " + renderer.recordFramesFile.getAbsolutePath() + " " + renderer.recordFramesFile.length());
         }
      }
   }
}
