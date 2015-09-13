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

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

abstract public class RecordingThread extends AsyncTask<Void, ProgressParam, Boolean> implements Freezeable
//======================================================================================================================
{
   static final private String TAG = RecordingThread.class.getSimpleName();
   static final protected long FRAME_BLOCK_TIME_MS = 120, FRAME_BLOCK_TIME_NS = 120000000L;
   static final protected int FRAMEWRITE_QUEUE_SIZE = 5;
   static final protected int WRITE_BUFFER_ADD_RETRIES = 10;
   static final protected int WRITE_BUFFER_DRAIN_TIMEOUT = 20;
   static final public boolean IS_LOGCAT_GOT = false;

   private RecorderActivity activity = null;

   protected GLRecorderRenderer renderer;
   protected float recordingIncrement = 0, recordingCurrentBearing = 0, recordingNextBearing = -1;
   protected int recordingCount = 0;
   protected int no;
   protected long lastFrameTimestamp = -1;
   protected boolean isStartRecording;
   protected ConditionVariable bearingCondVar = null, frameCondVar = null;
   protected RecorderRingBuffer frameBuffer;
   protected CameraPreviewThread previewer;
   protected byte[] previewBuffer = null, previousBuffer = null;
   protected BearingRingBuffer bearingBuffer = null;
   protected ExecutorService frameWriterExecutor;
   protected Future<?> frameWriterFuture;
   protected FrameAndOffsetWriterThread frameWriterThread = null;
   protected ExecutorService processBearingExecutor;
   protected Future<?> processBearingFuture;
   final protected ArrayBlockingQueue<BearingRingBuffer.RingBufferContent> processBearingQueue =
                                                                           new ArrayBlockingQueue<>(1000);
   protected Set<Long> completedBearings = null;
   protected String error = null;
   public String getError() { return error; }
//   protected File recordCompleteBearingsFile = null;

   static public RecordingThread createRecordingThread(GLRecorderRenderer.RecordMode mode, GLRecorderRenderer renderer,
                                                       float increment, ConditionVariable bearingCond,
                                                       RecorderRingBuffer frameBuffer,
                                                       ConditionVariable frameCond)
   //-----------------------------------------------------------------------------------------------------------------
   {
      switch (mode)
      {
         case TRAVERSE:   return new TraverseRecordingThread(renderer, renderer.nv21BufferSize, increment,
                                                             renderer.previewer, bearingCond, frameCond,
                                                             frameBuffer, renderer.bearingBuffer);
         case MERGE:       return new MergeRecordingThread(renderer, renderer.nv21BufferSize, increment,
                                                           renderer.previewer, bearingCond, frameCond,
                                                           frameBuffer, renderer.bearingBuffer);
      }
      return null;
   }

   public static RecordingThread createRecordingThread(GLRecorderRenderer.RecordMode mode, GLRecorderRenderer renderer,
                                                       RecorderRingBuffer frameBuffer)
   //-----------------------------------------------------------------------------------------------------------------
   {
      switch (mode)
      {
         case TRAVERSE:   return new TraverseRecordingThread(renderer, frameBuffer);
         case MERGE:      return new MergeRecordingThread(renderer, frameBuffer);
      }
      return null;
   }

   protected RecordingThread(GLRecorderRenderer renderer, int nv21BufferSize, float increment,
                             CameraPreviewThread previewer,
                             ConditionVariable recordingCondVar, ConditionVariable frameCondVar,
                             RecorderRingBuffer frameBuffer, BearingRingBuffer bearingBuffer)
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
      this.frameBuffer = frameBuffer;
      this.recordingIncrement = (float) (Math.rint(increment*10.0f)/10.0);
      completedBearings = Collections.synchronizedSet(new HashSet<Long>((int) (360.0/recordingIncrement)));
      no = (int) (Math.floor(360.0 / recordingIncrement));
   }

   protected RecordingThread(GLRecorderRenderer renderer, RecorderRingBuffer frameBuffer)
   {
      this.renderer = renderer;
      this.frameBuffer = frameBuffer;
   }

   public RecordingThread setPreviewer(CameraPreviewThread previewer) { this.previewer = previewer; return this; }

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
      long[] bearings = new long[completedBearings.size()];
      int i = 0;
      for (long bearing : completedBearings)
         bearings[i++] = bearing;
      B.putLongArray("RecordingThread.completedBearings", bearings);

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
         long[] bearings = B.getLongArray("RecordingThread.completedBearings");
         if (bearings != null)
         {
            for (long bearing : bearings)
               completedBearings.add(bearing);
         }
         no = (int) (Math.floor(360.0 / recordingIncrement));
      }
   }

   static public float distance(float bearing1, float bearing2)
   //-----------------------------------------------------------
   {
      final float dist;
      if ((bearing1 >= 350) && (bearing2 <= 10))
         dist = (360 - bearing1) + bearing2;
      else if ((bearing1 <= 10) && (bearing2 >= 350))
         dist = -((360 - bearing2) + bearing1);
      else
         dist = bearing2 - bearing1;
      return dist;
   }

   protected long nextCompleteOffset(float bearing)
   //-----------------------------------------------
   {
      long offset = -1;
      for (float bb=bearing; bb<360.0f; bb+=recordingIncrement)
      {
         long off = (long) Math.floor(bb / recordingIncrement);
         if (completedBearings.contains(off))
         {
            offset = off;
            break;
         }
      }
      if (offset < 0)
      {
         for (float bb=0.0f; bb<bearing; bb+=recordingIncrement)
         {
            long off = (long) Math.floor(bb / recordingIncrement);
            if (completedBearings.contains(off))
            {
               offset = off;
               break;
            }
         }
      }
      return offset;
   }

   protected long previousCompleteOffset(float bearing)
   //---------------------------------------------------
   {
      long offset = -1;
      for (float bb=bearing; bb>=0; bb-=recordingIncrement)
      {
         long off = (long) Math.floor(bb / recordingIncrement);
         if (completedBearings.contains(off))
         {
            offset = off;
            break;
         }
      }
      if (offset < 0)
      {
         for (float bb=360.0f; bb>bearing; bb-=recordingIncrement)
         {
            long off = (long) Math.floor(bb / recordingIncrement);
            if (completedBearings.contains(off))
            {
               offset = off;
               break;
            }
         }
      }
      return offset;
   }

   protected long nextOffset(float nextBearing, Set<Long> completedBearings)
   //---------------------------------------------------------------------
   {
      long offset = -1;
      for (float bb=nextBearing; bb<360.0f; bb+=recordingIncrement)
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
         for (float bb=0.0f; bb<nextBearing; bb+=recordingIncrement)
         {
            long off = (long) Math.floor(bb / recordingIncrement);
            if (! completedBearings.contains(off))
            {
               offset = off;
               break;
            }
         }
      }
      return offset;
   }

   protected long previousOffset(float bearing, Set<Long> completedBearings)
   //---------------------------------------------------------------------
   {
      long offset = -1;
      for (float bb=bearing; bb>=0; bb-=recordingIncrement)
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
         for (float bb=360.0f; bb>bearing; bb-=recordingIncrement)
         {
            long off = (long) Math.floor(bb / recordingIncrement);
            if (! completedBearings.contains(off))
            {
               offset = off;
               break;
            }
         }
      }
      return offset;
   }

   protected double filePSNR(File f, ByteBuffer frame, ByteBuffer emptyframe)
   //------------------------------------------------------------------------
   {
      FileInputStream fis = null;
      FileChannel channel = null;
      try
      {
         fis = new FileInputStream(f);
         channel = fis.getChannel();
         emptyframe.rewind();
         int n = channel.read(emptyframe);
         if (n <= 0)
            throw new IOException("0 bytes read");
         return FrameDiff.PSNR(renderer.previewWidth, renderer.previewHeight, frame, emptyframe);
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         return -1;
      }
      finally
      {
         if (channel != null)
            try { channel.close(); } catch (Exception _e) {}
         if (fis != null)
            try { fis.close(); } catch (Exception _e) {}
      }
   }

   protected double fileMSSIM(File f, ByteBuffer frame, ByteBuffer emptyframe)
   //-------------------------------------------------------------------
   {
      FileInputStream fis = null;
      FileChannel channel = null;
      try
      {
         fis = new FileInputStream(f);
         channel = fis.getChannel();
         emptyframe.rewind();
         int n = channel.read(emptyframe);
         if (n <= 0)
            throw new IOException("0 bytes read");
         return FrameDiff.MSSIM(renderer.previewWidth, renderer.previewHeight, frame, emptyframe);
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         return -1;
      }
      finally
      {
         if (channel != null)
            try { channel.close(); } catch (Exception _e) {}
         if (fis != null)
            try { fis.close(); } catch (Exception _e) {}
      }
   }

   public interface Stoppable
   //========================
   {
      void stop();
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
         frameWriterThread = new FrameAndOffsetWriterThread();
      }
      catch (IOException _e)
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
            try { bufferQueue.put(new FrameAndOffset()); } catch (InterruptedException _e) { Log.e(TAG, "", _e); }
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

   protected class FrameAndOffset
   //=============================
   {
      long timestamp = -1;
      long offset =-1;
      byte[] buffer = null;

      public FrameAndOffset() {}

      public FrameAndOffset(long offset, long timestamp)
      //--------------------------------------
      {
         this.offset = offset;
         this.timestamp = timestamp;
         this.buffer = Arrays.copyOf(previewBuffer, previewBuffer.length);
      }
   }

   protected final ArrayBlockingQueue<FrameAndOffset> bufferQueue = new ArrayBlockingQueue(FRAMEWRITE_QUEUE_SIZE);

   protected boolean addFrameToWriteBuffer(long offset, long timestamp)
   //------------------------------------------------------------------
   {
      boolean isAdded = false;
      for (int retry = 0; retry<WRITE_BUFFER_ADD_RETRIES; retry++)
      {
         isAdded = bufferQueue.offer(new FrameAndOffset(offset, timestamp));
         if (isAdded)
            break;
         while (bufferQueue.remainingCapacity() <= 0)
            try { Thread.sleep(WRITE_BUFFER_DRAIN_TIMEOUT); } catch (Exception _e) {}
      }
      return isAdded;
   }

   final static private boolean WRITE_FRAME_FILES = false;

   class FrameAndOffsetWriterThread implements Runnable
   //==================================================
   {
      private final RandomAccessFile framesRAF;
//      private final PrintWriter timestampWriter;
      final int bufferSize = renderer.nv21BufferSize;
      boolean mustStop = false;
      long cachedOffset = -1;
      int count = 0;
      public int getCount() { return count; }

      public FrameAndOffsetWriterThread() throws IOException
      //----------------------------------------------------
      {
         final String name;
         name = (renderer.recordFileName == null) ? "Unknown" : renderer.recordFileName;
         renderer.recordFramesFile = new File(renderer.recordDir, name + ".frames.part");
         framesRAF = new RandomAccessFile(renderer.recordFramesFile, "rw");
//         recordCompleteBearingsFile = new File(renderer.recordDir, name + ".complete.bearings");
//         timestampWriter = new PrintWriter(new BufferedWriter(new FileWriter(recordCompleteBearingsFile), 32768));
      }

      private boolean read(long offset, byte[] buf)
      //-------------------------------------------------------
      {
         try
         {
            framesRAF.seek(offset * bufferSize);
            framesRAF.read(buf);
            return true;
         }
         catch (Exception e)
         {
            Log.e(TAG, "", e);
            return false;
         }
      }

      @Override
      public void run()
      //--------------
      {
         Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE);
         FrameAndOffset frameBuffer;
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
                  long off = frameBuffer.offset - 1;
                  if (off < 0)
                     off = (long) (Math.floor(360.0/recordingIncrement) - 1);
                  if (off != cachedOffset)
                  {
                     if (this.read(off, previousBuffer))
                        cachedOffset = off;
                  }
                  if ( (completedBearings.contains(off)) &&  (off == cachedOffset) )
                  {
                     double psnr = FrameDiff.psnr(renderer.previewWidth, renderer.previewHeight, previewBuffer, previousBuffer);
                     if (psnr < 13.5)
                     {
                        Log.w(TAG, "Rejected " + frameBuffer.offset + " PSNR = " + psnr);
                        continue;
                     }
                  }
                  System.arraycopy(previewBuffer, 0, previousBuffer, 0, previewBuffer.length);
                  cachedOffset = frameBuffer.offset;

                  count++;
                  framesRAF.seek(frameBuffer.offset * bufferSize);
                  framesRAF.write(frameBuffer.buffer);
                  completedBearings.add(frameBuffer.offset);

//                  timestampWriter.printf("%d=%d\n", frameBuffer.offset, frameBuffer.timestamp);
                  if (WRITE_FRAME_FILES)
                  {
                     BufferedOutputStream bos = null;
                     try
                     {
                        bos = new BufferedOutputStream(new FileOutputStream(new File(renderer.recordDir,
                                                                                     Long.toString(frameBuffer.offset))), 32768);
                        bos.write(frameBuffer.buffer);
                     }
                     catch (Exception _e)
                     {
                        Log.e(TAG, "", _e);
                     }
                     finally
                     {
                        if (bos != null)
                           try { bos.close(); } catch (Exception _e) {}
                     }
                  }
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
//            try { timestampWriter.close();} catch (Exception _e) { Log.e(TAG, "", _e); }
         }
      }
   }

}
