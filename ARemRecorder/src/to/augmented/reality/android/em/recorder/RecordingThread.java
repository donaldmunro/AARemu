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

import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.concurrent.*;

abstract public class RecordingThread extends AsyncTask<Void, ProgressParam, Boolean> implements Freezeable
//======================================================================================================================
{
   static final private String TAG = RecordingThread.class.getSimpleName();
   static final protected long FRAME_BLOCK_TIME_MS = 120, FRAME_BLOCK_TIME_NS = 120000000L;
   static final protected int FRAMEWRITE_QUEUE_SIZE = 3;
   static final protected int WRITE_BUFFER_ADD_RETRIES = 10;
   static final protected int WRITE_BUFFER_DRAIN_TIMEOUT = 20;

   private RecorderActivity activity = null;

   protected GLRecorderRenderer renderer;
   protected float recordingIncrement = 0, recordingCurrentBearing = 0, recordingNextBearing = 0;
   protected int recordingCount = 0;
   protected long lastFrameTimestamp = -1;
   protected final float dummyStartBearing = 355;
   protected boolean isStartRecording;
   protected ConditionVariable bearingCondVar = null, frameCondVar = null;
   protected CameraPreviewCallback previewer;
   protected byte[] previewBuffer = null;
   protected BearingRingBuffer bearingBuffer = null;
   protected ExecutorService frameWriterExecutor;
   protected Future<?> frameWriterFuture;
   protected FrameWriterThread frameWriterThread = null;
   protected ExecutorService processBearingExecutor;
   protected Future<?> processBearingFuture;
   final protected ArrayBlockingQueue<BearingRingBuffer.RingBufferContent> processBearingQueue =
         new ArrayBlockingQueue<BearingRingBuffer.RingBufferContent>(1000);

   static public RecordingThread createRecordingThread(GLRecorderRenderer.RecordMode mode, GLRecorderRenderer renderer,
                                                       float increment, ConditionVariable bearingCond,
                                                       ConditionVariable frameCond)
   //-----------------------------------------------------------------------------------------------------------------
   {
      switch (mode)
      {
         case RETRY: return new RetryRecordingThread(renderer, renderer.nv21BufferSize, increment,
                                                     renderer.previewer, bearingCond, frameCond, renderer.bearingBuffer);
         case TRAVERSE: return new TraverseRecordingThread(renderer, renderer.nv21BufferSize, increment,
                                                           renderer.previewer, bearingCond, frameCond, renderer.bearingBuffer);

      }
      return null;
   }

   public static RecordingThread createRecordingThread(GLRecorderRenderer.RecordMode mode, GLRecorderRenderer renderer)
   //------------------------------------------------------------------------------------------------------------------
   {
      switch (mode)
      {
         case RETRY:    return new RetryRecordingThread(renderer);
         case TRAVERSE: return new TraverseRecordingThread(renderer);
      }
      return null;
   }

   protected RecordingThread(GLRecorderRenderer renderer, int nv21BufferSize, float increment,
                             CameraPreviewCallback previewer,
                             ConditionVariable recordingCondVar, ConditionVariable frameCondVar,
                             BearingRingBuffer bearingBuffer)
   //----------------------------------------------------------------------------------------------------------------
   {
      this.renderer = renderer;
      this.activity = renderer.activity;
      this.previewBuffer = new byte[nv21BufferSize];
      this.previewer = previewer;
      this.isStartRecording = true;
      this.bearingCondVar = recordingCondVar;
      this.frameCondVar = frameCondVar;
      this.bearingBuffer = bearingBuffer;
      this.recordingIncrement = (float) (Math.rint(increment*10.0f)/10.0);
      this.recordingCurrentBearing = dummyStartBearing;
      this.recordingNextBearing = (float) (Math.rint((recordingCurrentBearing + recordingIncrement) * 10.0f) / 10.0);
   }

   protected RecordingThread(GLRecorderRenderer renderer) { this.renderer = renderer; }

   public RecordingThread setPreviewer(CameraPreviewCallback previewer) { this.previewer = previewer; return this; }

   public RecordingThread setPreviewBuffer(byte[] previewBuffer) { this.previewBuffer = previewBuffer; return this; }

   public RecordingThread setBearingCondVar(ConditionVariable bearingCondVar) { this.bearingCondVar = bearingCondVar; return this; }

   public RecordingThread setFrameCondVar(ConditionVariable frameCondVar) { this.frameCondVar = frameCondVar; return this; }

   public RecordingThread setBearingBuffer(BearingRingBuffer bearingBuffer) { this.bearingBuffer = bearingBuffer; return this; }

   @Override abstract protected Boolean doInBackground(Void... params);

   @Override
   protected void onProgressUpdate(ProgressParam... values) { activity.onStatusUpdate(values[0]); }

   @Override protected void onPostExecute(Boolean B)
   //----------------------------------------------
   {
      if (renderer.isRecording)
         renderer.stopRecording(false);
   }

   public void pause(Bundle B)
   //-------------------------
   {
      if (B == null) return;
      B.putFloat("RecordingThread.recordingNextBearing", recordingNextBearing);
      B.putFloat("RecordingThread.recordingCurrentBearing", recordingCurrentBearing);
      B.putFloat("RecordingThread.recordingIncrement", recordingIncrement);
      B.putInt("RecordingThread.recordingCount", recordingCount);
      B.putBoolean("RecordingThread.isStartRecording", isStartRecording);

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
      }
   }

   public interface Stoppable
   //========================
   {
      public void stop();
   }

   protected void startBearingProcessor(Runnable thread)
   //-------------------------------------------------
   {
      if (processBearingFuture != null)
         stopBearingProcessor((Stoppable) thread);
      processBearingExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
      {
         @Override
         public Thread newThread(Runnable r)
         //-------------------------------------------
         {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("ProcessBearing");
            return t;
         }
      });
      processBearingFuture = processBearingExecutor.submit(thread);
      return;
   }

   protected void stopBearingProcessor(Stoppable thread)
   //---------------------------------------------------
   {
      if ( (processBearingFuture != null) && (! processBearingFuture.isDone()))
      {  // @formatter:off
         thread.stop();
         if (processBearingQueue != null)
            try { processBearingQueue.put(bearingBuffer.createContent()); } catch (InterruptedException _e) { Log.e(TAG, "", _e); }
         if (! processBearingFuture.isDone())
         {
            try
            {
               processBearingFuture.get(60, TimeUnit.MILLISECONDS);
            }
            catch (Exception e)
            {
               processBearingFuture.cancel(true);
               try { processBearingExecutor.shutdownNow(); } catch (Exception _e) { }
            }
         }
      } // @formatter:on
      processBearingFuture = null;
   }


   protected boolean startFrameWriter()
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
         ProgressParam params = new ProgressParam();
         params.setStatus("Frame file permission error ? (" + renderer.recordFramesFile.getAbsolutePath() + ")",
                          100, true, Toast.LENGTH_LONG);
         publishProgress(params);
         return false;
      }
      frameWriterExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
      {
         @Override
         public Thread newThread(Runnable r)
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

   protected void stopFrameWriter()
   //--------------------------
   {
      if ( (frameWriterThread != null) && (frameWriterFuture != null) && (! frameWriterFuture.isDone()))
      {  // @formatter:off
         frameWriterThread.mustStop = true;
         if (bufferQueue != null)
            try { bufferQueue.put(new Frame()); } catch (InterruptedException _e) { Log.e(TAG, "", _e); }
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

   protected class Frame
   //===================
   {
      long offset;
      byte[] buffer;

      public Frame() { this.offset = -1; this.buffer = null; }


      public Frame(long offset)
      //-----------------------
      {
         this.offset = offset;
         this.buffer = Arrays.copyOf(previewBuffer, previewBuffer.length);
      }
   }

   protected final ArrayBlockingQueue<Frame> bufferQueue = new ArrayBlockingQueue<Frame>(FRAMEWRITE_QUEUE_SIZE);

   protected boolean addFrameToWriteBuffer(long offset)
   //--------------------------------------------------
   {
      boolean isAdded = false;
      for (int retry = 0; retry<WRITE_BUFFER_ADD_RETRIES; retry++)
      {
         isAdded = bufferQueue.offer(new Frame(offset));
         if (isAdded)
            break;
         while (bufferQueue.remainingCapacity() <= 0)
            try { Thread.sleep(WRITE_BUFFER_DRAIN_TIMEOUT); } catch (Exception _e) {}
      }
      return isAdded;
   }

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
         Frame frameBuffer;
         try
         {
            while (true)
            {
               if ( (mustStop) && (bufferQueue.isEmpty()) )
                  break;
               try { frameBuffer = bufferQueue.take(); } catch (InterruptedException e) { break; }
               if ( (frameBuffer == null) || (frameBuffer.buffer == null) || (frameBuffer.offset < 0) ) continue;
               try
               {
                  framesRAF.seek(frameBuffer.offset*bufferSize);
                  framesRAF.write(frameBuffer.buffer);
               }
               catch (IOException e)
               {
                  Log.e(TAG, "Error seeking/writing frame", e);
                  throw (new RuntimeException("Error seeking/writing frame", e));
               }
            }
         }
         catch (Exception e)
         {
            Log.e(TAG, "", e);
         }
         finally
         {
            try { framesRAF.close(); } catch (Exception _e) { Log.e(TAG, "", _e); }
//            Log.i(TAG, "Frame file: " + renderer.recordFramesFile.getAbsolutePath() + " " + renderer.recordFramesFile.length());
         }
      }
   }

}
