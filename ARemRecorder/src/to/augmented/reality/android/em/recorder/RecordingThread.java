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
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;

abstract public class RecordingThread extends AsyncTask<Void, ProgressParam, Boolean> implements Freezeable
//======================================================================================================================
{
   static final private String TAG = RecordingThread.class.getSimpleName();
   static final protected long FRAME_BLOCK_TIME_MS = 120, FRAME_BLOCK_TIME_NS = 120000000L;
   static final protected int FRAMEWRITE_QUEUE_SIZE = 5;
   static final protected int WRITE_BUFFER_ADD_RETRIES = 20;
   static final protected int WRITE_BUFFER_DRAIN_TIMEOUT = 5;
   static final public boolean IS_LOGCAT_GOT = false;

   private RecorderActivity activity = null;

   protected GLRecorderRenderer renderer;
   protected float recordingIncrement = 0, recordingCurrentBearing = 0, recordingNextBearing = -1, startBearing = 1001;
   protected int recordingCount = 0;
   protected int no;
   protected long largestBearing = -1;;
   protected boolean isStartRecording;
   protected ConditionVariable bearingCondVar = null, frameCondVar = null;
   protected RecorderRingBuffer frameBuffer;
   protected CameraPreviewThread previewer;
   protected BearingRingBuffer bearingBuffer = null;
   protected ExecutorService frameWriterExecutor;
   protected Future<?> frameWriterFuture;
   protected FrameWriteable frameWriterThread = null;
//   protected ExecutorService frameCheckExecutor;
//   protected Future<?> frameCheckFuture;
//   protected FrameCheckThread frameCheckerThread = null;
   protected ExecutorService processBearingExecutor;
   protected Future<?> processBearingFuture;
   final protected ArrayBlockingQueue<BearingRingBuffer.RingBufferContent> processBearingQueue =
                                                                           new ArrayBlockingQueue<>(1000);
   protected File logFile = null;
   protected BufferedWriter logWriter = null;
   protected AtomicLongArray completedBearings = null;
   protected String error = null;

   protected boolean isPostProcess = true;
   public void setPostProcess(boolean postProcess) { this.isPostProcess = postProcess; }

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
         case TRAVERSE: return new TraverseRecordingThread(renderer, renderer.nv21BufferSize, increment,
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
         case TRAVERSE: return new TraverseRecordingThread(renderer, frameBuffer);
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
      this.previewer = previewer;
      this.isStartRecording = true;
      this.bearingCondVar = recordingCondVar;
      this.frameCondVar = frameCondVar;
      this.bearingBuffer = bearingBuffer;
      this.frameBuffer = frameBuffer;
      this.recordingIncrement = (float) (Math.rint(increment * 10.0f)/10.0);
      this.largestBearing = (long) (Math.floor(360.0 / recordingIncrement) - 1);
      no = (int) (Math.floor(360.0 / recordingIncrement));
      long[] al = new long[no];
      Arrays.fill(al, -1);
      completedBearings = new AtomicLongArray(al);
      logFile = new File(renderer.recordDir, "log");
      try { logWriter = new BufferedWriter(new FileWriter(logFile)); } catch (Exception e) { logWriter = null; Log.e(TAG, "", e); }
   }

   public int completedLength()
   //--------------------------
   {
      int count = 0;
      for (int i = 0; i < no; i++)
      {
         if (completedBearings.get(i) >= 0)
            count++;
      }
      return count;
   }

   public long[] completedArray()
   //----------------------------
   {
      long[] al = new long[no];
      for (int i=0; i<no; i++)
         al[i] = completedBearings.get(i);
      return al;
   }

   protected RecordingThread(GLRecorderRenderer renderer, RecorderRingBuffer frameBuffer)
   {
      this.renderer = renderer;
      this.frameBuffer = frameBuffer;
      logFile = new File(renderer.recordDir, "log");
      try { logWriter = new BufferedWriter(new FileWriter(logFile)); } catch (Exception e) { logWriter = null; Log.e(TAG, "", e); }
   }

   public RecordingThread setPreviewer(CameraPreviewThread previewer) { this.previewer = previewer; return this; }

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
      try
      {
         B.putFloat("RecordingThread.recordingNextBearing", recordingNextBearing);
         B.putFloat("RecordingThread.recordingCurrentBearing", recordingCurrentBearing);
         B.putFloat("RecordingThread.recordingIncrement", recordingIncrement);
         B.putInt("RecordingThread.recordingCount", recordingCount);
         B.putBoolean("RecordingThread.isStartRecording", isStartRecording);
         long[] al = new long[no];
         for (int i=0; i<no; i++)
            al[i] = completedBearings.get(i);
         B.putLongArray("RecordingThread.completedBearings", al);
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
      }

   }

   public void restore(Bundle B)
   //---------------------------
   {
      if (B != null)
      {
         try
         {
            recordingNextBearing = B.getFloat("RecordingThread.recordingNextBearing", 0);
            recordingCurrentBearing = B.getFloat("RecordingThread.recordingCurrentBearing", 0);
            recordingIncrement = B.getFloat("RecordingThread.recordingIncrement", 0);
            recordingCount = B.getInt("RecordingThread.recordingCount", 0);
            isStartRecording = B.getBoolean("RecordingThread.isStartRecording", true);
            no = (int) (Math.floor(360.0 / recordingIncrement));
            long[] al = B.getLongArray("RecordingThread.completedBearings");
            if (al == null)
            {
               al = new long[no];
               Arrays.fill(al, -1);
            }
            completedBearings = new AtomicLongArray(al);
         }
         catch (Exception e)
         {
            Log.e(TAG, "", e);
         }

      }
   }

   static public float distance(float bearing1, float bearing2)
   //-----------------------------------------------------------
   {
      if ((bearing1 >= 270) && (bearing2 <= 90))
         return  (360 - bearing1) + bearing2;
      else if ((bearing1 <= 90) && (bearing2 >= 270))
         return  -((360 - bearing2) + bearing1);
      else
         return bearing2 - bearing1;
   }

   static public NavigableMap<Long, File> createFrameTimesList(File framesDirectory, NavigableMap<Long, File> frameFileMap)
   //----------------------------------------------------------------------------------------------------------------------
   {
      if (frameFileMap == null)
         frameFileMap = new TreeMap<>();
      File[] frameFiles = framesDirectory.listFiles();
      long timestamp;
      for (File f : frameFiles)
      {
         try { timestamp = Long.parseLong(f.getName().trim()); } catch (Exception _e) { continue; }
         frameFileMap.put(timestamp, f);
      }
      return frameFileMap;
   }

   public static void delDirContents(File dir)
   //------------------------------------
   {
      if (dir.isDirectory())
      {
         for (File f : dir.listFiles())
         {
            if (f.isDirectory())
               delDirContents(f);
            else
               f.delete();
         }
         dir.delete();
      }
      else
         dir.delete();
   }

   protected long nextCompleteOffset(float bearing)
   //-----------------------------------------------
   {
      long offset = -1, contents;
      for (float bb=bearing; bb<360.0f; bb+=recordingIncrement)
      {
         int off = (int) Math.floor(bb / recordingIncrement);
         if (completedBearings.get(off) >= 0)
         {
            offset = off;
            break;
         }
      }
      if (offset < 0)
      {
         for (float bb=0.0f; bb<bearing; bb+=recordingIncrement)
         {
            int off = (int) Math.floor(bb / recordingIncrement);
            if (completedBearings.get(off) >= 0)
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
      long offset = -1, contents;
      for (float bb=bearing; bb>=0; bb-=recordingIncrement)
      {
         int off = (int) Math.floor(bb / recordingIncrement);
         if (completedBearings.get(off) >= 0)
         {
            offset = off;
            break;
         }
      }
      if (offset < 0)
      {
         for (float bb=(360.0f - recordingIncrement); bb>bearing; bb-=recordingIncrement)
         {
            int off = (int) Math.floor(bb / recordingIncrement);
            if (completedBearings.get(off) >= 0)
            {
               offset = off;
               break;
            }
         }
      }
      return offset;
   }

   protected long nextIncompleteOffset(final float nextBearing, final long[] completedBearings)
   //------------------------------------------------------------------------------------------------------------
   {
      long offset = -1, contents;
      for (float bb=nextBearing; bb<360.0f; bb+=recordingIncrement)
      {
         int off = (int) Math.floor(bb / recordingIncrement);
         if (completedBearings[off] < 0)
         {
            offset = off;
            break;
         }
      }
      if (offset < 0)
      {
         for (float bb=0.0f; bb<nextBearing; bb+=recordingIncrement)
         {
            int off = (int) Math.floor(bb / recordingIncrement);
            if (completedBearings[off] < 0)
            {
               offset = off;
               break;
            }
         }
      }
      return offset;
   }

   protected long nextIncompleteOffset(final float nextBearing)
   //----------------------------------------------------------
   {
      long offset = -1;
      for (float bb=nextBearing; bb<360.0f; bb+=recordingIncrement)
      {
         int off = (int) Math.floor(bb / recordingIncrement);
         if (completedBearings.get(off) < 0)
         {
            offset = off;
            break;
         }
      }
      if (offset < 0)
      {
         for (float bb=0.0f; bb<nextBearing; bb+=recordingIncrement)
         {
            int off = (int) Math.floor(bb / recordingIncrement);
            if (completedBearings.get(off) < 0)
            {
               offset = off;
               break;
            }
         }
      }
      return offset;
   }

//   protected long previousIncompleteOffset(final float bearing, final long[] completedBearings)
//   //------------------------------------------------------------------------------------------
//   {
//      long offset = -1, contents;
//      for (float bb=bearing; bb>=0; bb-=recordingIncrement)
//      {
//         int off = (int) Math.floor(bb / recordingIncrement);
//         if (completedBearings[off] < 0)
//         {
//            offset = off;
//            break;
//         }
//      }
//      if (offset < 0)
//      {
//         for (float bb=360.0f; bb>bearing; bb-=recordingIncrement)
//         {
//            int off = (int) Math.floor(bb / recordingIncrement);
//            if (completedBearings[off] < 0)
//            {
//               offset = off;
//               break;
//            }
//         }
//      }
//      return offset;
//   }
//
//   protected double filePSNR(File f, ByteBuffer frame, ByteBuffer emptyframe)
//   //------------------------------------------------------------------------
//   {
//      FileInputStream fis = null;
//      FileChannel channel = null;
//      try
//      {
//         fis = new FileInputStream(f);
//         channel = fis.getChannel();
//         emptyframe.rewind();
//         int n = channel.read(emptyframe);
//         if (n <= 0)
//            throw new IOException("0 bytes read");
//         return CV.PSNR(renderer.previewWidth, renderer.previewHeight, frame, emptyframe);
//      }
//      catch (Exception e)
//      {
//         Log.e(TAG, "", e);
//         return -1;
//      }
//      finally
//      {
//         if (channel != null)
//            try { channel.close(); } catch (Exception _e) {}
//         if (fis != null)
//            try { fis.close(); } catch (Exception _e) {}
//      }
//   }
//
//   protected double fileMSSIM(File f, ByteBuffer frame, ByteBuffer emptyframe)
//   //-------------------------------------------------------------------
//   {
//      FileInputStream fis = null;
//      FileChannel channel = null;
//      try
//      {
//         fis = new FileInputStream(f);
//         channel = fis.getChannel();
//         emptyframe.rewind();
//         int n = channel.read(emptyframe);
//         if (n <= 0)
//            throw new IOException("0 bytes read");
//         return CV.MSSIM(renderer.previewWidth, renderer.previewHeight, frame, emptyframe);
//      }
//      catch (Exception e)
//      {
//         Log.e(TAG, "", e);
//         return -1;
//      }
//      finally
//      {
//         if (channel != null)
//            try { channel.close(); } catch (Exception _e) {}
//         if (fis != null)
//            try { fis.close(); } catch (Exception _e) {}
//      }
//   }

   protected int fileShift(File f, ByteBuffer frame, ByteBuffer emptyframe)
   //------------------------------------------------------------------------
   {
      FileInputStream fis = null;
      FileChannel channel = null;
      int[] result = new int[2];
      try
      {
         fis = new FileInputStream(f);
         channel = fis.getChannel();
         emptyframe.rewind();
         int n = channel.read(emptyframe);
         if (n <= 0)
            throw new IOException("0 bytes read");
         CV.SHIFT(renderer.previewWidth, renderer.previewHeight, frame, emptyframe, result);
         return result[0];
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

   protected void logException(String errm, Exception e)
   //---------------------------------------------------
   {
      if (logWriter == null)
         return;
      try
      {
         if ( (errm != null) && (! errm.isEmpty()) )
         {
            logWriter.write(errm);
            logWriter.newLine();
         }
         if (e != null)
         {
            StringBuilder sb = new StringBuilder();
            for (StackTraceElement el : e.getStackTrace())
               sb.append(el.toString()).append(System.getProperty("line.separator"));
            logWriter.write(sb.toString());
         }
      }
      catch (Exception ee)
      {
         Log.e(TAG, "logException", ee);
         Log.e(TAG, "", e);
      }
      finally
      {
         try { logWriter.flush(); } catch (Exception _e) {}
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
         frameWriterThread = new SimpleFrameWriterThread();
//         frameCheckerThread = new FrameCheckThread();
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

//      frameCheckExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
//      {
//         @Override
//         public Thread newThread(Runnable r)
//         //-------------------------------------------
//         {
//            Thread t = new Thread(r);
//            t.setDaemon(true);
//            t.setName("FrameChecker");
//            return t;
//         }
//      });
//      frameCheckFuture = frameCheckExecutor.submit(frameCheckerThread);
      return true;
   }

   protected void stopFrameWriter()
   //--------------------------
   {
      if ( (frameWriterThread != null) && (frameWriterFuture != null) && (! frameWriterFuture.isDone()))
      {
         frameWriterThread.stop();
         if (bufferQueue != null)
         {
            try { bufferQueue.put(new FrameAndOffset()); } catch (InterruptedException _e) { Log.e(TAG, "", _e); }
            while (bufferQueue.size() > 0)
               try { Thread.sleep(20); } catch (Exception _e) {}
         }
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
               if (frameWriterThread.getFile() != null)
                  try { frameWriterThread.getFile().close(); } catch (Exception _e) { }
            }
         }
      }
      if ( (frameWriterThread != null) && (frameWriterThread.getFile() != null) )
         try { frameWriterThread.getFile().close(); } catch (Exception _e) { }
      frameWriterThread = null;
      frameWriterFuture = null;

//      if ( (frameCheckerThread != null) && (frameCheckFuture != null) && (! frameCheckFuture.isDone()))
//      {  // @formatter:off
//         frameCheckerThread.mustStop = true;
//         if (offsetQueue != null)
//            try { offsetQueue.put(-1L); } catch (InterruptedException _e) { Log.e(TAG, "", _e); }
//         if (! frameCheckFuture.isDone())
//         {
//            try
//            {
//               frameCheckFuture.get(100, TimeUnit.MILLISECONDS);
//            }
//            catch (Exception e)
//            {
//               frameCheckFuture.cancel(true);
//               try { frameCheckExecutor.shutdownNow(); } catch (Exception _e) { }
//            }
//         }
//      } // @formatter:on
//      frameCheckerThread = null;
//      frameCheckFuture = null;
   }

   public float getNextBearing() { return recordingNextBearing; }

   protected class FrameAndOffset
   //=============================
   {
      long timestamp = -1;
      long offset =-1;
      byte[] buffer = null;

      public FrameAndOffset() {}

      public FrameAndOffset(long offset, long timestamp, byte [] data)
      //--------------------------------------------------------------
      {
         this.offset = offset;
         this.timestamp = timestamp;
         this.buffer = data;
      }
   }

   protected final ArrayBlockingQueue<FrameAndOffset> bufferQueue = new ArrayBlockingQueue(FRAMEWRITE_QUEUE_SIZE);

//   protected final ArrayBlockingQueue<Long> offsetQueue = new ArrayBlockingQueue(100);


   protected boolean addFrameToWriteBuffer(FrameAndOffset item)
   //----------------------------------------------------------
   {
      boolean isAdded = bufferQueue.offer(item);
      if (! isAdded)
      {
         int retry = 0;
         while ( (bufferQueue.remainingCapacity() <= 0) && (retry++ < WRITE_BUFFER_ADD_RETRIES) )
            try { Thread.sleep(WRITE_BUFFER_DRAIN_TIMEOUT); } catch (Exception _e) { break; }
         isAdded = bufferQueue.offer(item);
      }
      return isAdded;
   }

   protected boolean addFrameToWriteBufferNoWait(FrameAndOffset item) { return bufferQueue.offer(item); }

   static public boolean read(final RandomAccessFile framesRAF, final long bufferSize, final long offset, byte[] buf)
   //-----------------------------------------------------------------------------------------------------------------
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

   static public boolean read(final RandomAccessFile framesRAF, final long bufferSize, final long offset, ByteBuffer buf)
   //-----------------------------------------------------------------------------------------------------------------
   {
      try
      {
         FileChannel channel = framesRAF.getChannel();
         framesRAF.seek(offset * bufferSize);
         buf.rewind();
         channel.read(buf);
         return true;
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         return false;
      }
   }

   final static private boolean WRITE_FRAME_FILES = false;

   public interface FrameWriteable extends Runnable
   //==============================================
   {
      int getCount();

      void incrementCount();

      RandomAccessFile getFile();

      void stop();
   }

   class SimpleFrameWriterThread implements FrameWriteable
   //=====================================================
   {
      private final RandomAccessFile framesRAF;

      @Override public RandomAccessFile getFile() { return framesRAF; }

      final int bufferSize = renderer.nv21BufferSize;
      boolean mustStop = false;
      public void stop() { mustStop = true; }
//      long cachedOffset = -1;
      int count = 0;
      public int getCount() { return count; }
      public void incrementCount() { count++; }

      public SimpleFrameWriterThread() throws IOException
      //----------------------------------------------------
      {
         final String name;
         name = (renderer.recordFileName == null) ? "Unknown" : renderer.recordFileName;
         renderer.recordFramesFile = new File(renderer.recordDir, name + ".frames.part");
         framesRAF = new RandomAccessFile(renderer.recordFramesFile, "rws");
      }

//      public boolean flush()
//      //-----------------
//      {
//         try { framesRAF.getFD().sync(); return true; } catch (Exception _ee) { Log.e(TAG, "", _ee); return false; }
//      }

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
         long contents;
         try
         {
            while (true)
            {
               if ( (mustStop) && (bufferQueue.isEmpty()) )
                  break;
               try { frameBuffer = bufferQueue.take(); } catch (InterruptedException e) { break; }
               if ( (frameBuffer == null) || (frameBuffer.buffer == null) || (frameBuffer.offset < 0) ) continue;

               if (completedBearings.get((int)frameBuffer.offset) >= 0) continue;
               try
               {
                  framesRAF.seek(frameBuffer.offset * bufferSize);
                  framesRAF.write(frameBuffer.buffer);
                  count++;
                  completedBearings.set((int)frameBuffer.offset, frameBuffer.timestamp);
                  frameBuffer.buffer = null;

//                  logWriter.write("Wrote " + frameBuffer.offset + " timestamp " + frameBuffer.timestamp); logWriter.newLine();
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

//   class FrameCheckThread implements Runnable
//   //========================================
//   {
//      private final RandomAccessFile framesRAF;
//      int minShift = 2, maxShift = 20;
//      long cachedOffset = -1;
//      final int bufferSize = renderer.nv21BufferSize;
//      public volatile boolean mustStop = false;
//      //int cacheHits = 0;
//
//      public FrameCheckThread()throws FileNotFoundException { this(2, 22); }
//
//      public FrameCheckThread(int minshift, int maxshift) throws FileNotFoundException
//      //-------------------------------------------------------------------------------
//      {
//         framesRAF = new RandomAccessFile(renderer.recordFramesFile, "r");
//         minShift = minshift;
//         maxShift = maxshift;
//      }
//
//      @Override
//      public void run()
//      //---------------
//      {
//         Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
//         Long offset = null;
//         final Set<Long> checkedOffsets = new HashSet<>();
//         int[] shiftResult = new int[2];
//         ByteBuffer frame = ByteBuffer.allocateDirect(bufferSize), cachedFrame = ByteBuffer.allocateDirect(bufferSize);
//         while (true)
//         {
//            if (mustStop)
//            {
//               if (offsetQueue.isEmpty())
//                  break;
//               Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
//            }
//            try { offset = offsetQueue.take(); } catch (InterruptedException e) { break; }
//            if ( (offset == null) || (offset < 0) ) continue;
//            if (! completedBearings.containsKey(offset)) continue;
//
//            boolean isPrevious = false, isNext = false;
//            long previousOffset = offset - 1;
//            if (previousOffset < 0)
//               previousOffset = largestBearing;
//            if (completedBearings.containsKey(previousOffset))
//            {
//               if (previousOffset == cachedOffset)
//               {
//                  //Log.i(TAG, "Cache hits " + ++cacheHits);
//                  cachedFrame.rewind();
//                  frame.rewind();
//                  frame.put(cachedFrame);
//                  cachedFrame.rewind();
//                  isPrevious = read(framesRAF, bufferSize, offset, cachedFrame);
//                  if (isPrevious)
//                     cachedOffset = offset;
//                  else
//                     cachedOffset = -1;
//               }
//               else
//               {
//                  isPrevious = read(framesRAF, bufferSize, previousOffset, frame);
//                  if (offset != cachedOffset)
//                  {
//                     isPrevious = read(framesRAF, bufferSize, offset, cachedFrame);
//                     if (isPrevious)
//                        cachedOffset = offset;
//                     else
//                        cachedOffset = -1;
//                  }
////                  else
////                     Log.i(TAG, "Cache hits " + ++cacheHits);
//               }
//               if (isPrevious)
//               {
//                  CV.SHIFT(renderer.previewWidth, renderer.previewHeight, frame, cachedFrame, shiftResult);
//                  final int shift = shiftResult[0];
//                  if ((shift < minShift) || (shift > maxShift))
//                  {
//                     Log.w(TAG, "Rejected " + offset + " shift = " + shift);
//                     completedBearings.remove(offset);
//                  }
//               }
//            }
//            long nextOffset = (offset + 1) % 360;
//            if (completedBearings.containsKey(nextOffset))
//            {
//               if (nextOffset == cachedOffset)
//               {
//                  isNext = read(framesRAF, bufferSize, offset, frame);
//                  //Log.i(TAG, "Cache hits " + ++cacheHits);
//               }
//               else
//               {
//                  if (offset == cachedOffset)
//                  {
////                     Log.i(TAG, "Cache hits " + ++cacheHits);
//                     cachedFrame.rewind();
//                     frame.rewind();
//                     frame.put(cachedFrame);
//                     cachedFrame.rewind();
//                  }
//                  else
//                     read(framesRAF, bufferSize, offset, frame);
//                  isNext = read(framesRAF, bufferSize, nextOffset, cachedFrame);
//                  if (isNext)
//                     cachedOffset = nextOffset;
//                  else
//                     cachedOffset = -1;
//
//               }
//               if (isNext)
//               {
//                  CV.SHIFT(renderer.previewWidth, renderer.previewHeight, frame, cachedFrame, shiftResult);
//                  final int shift = shiftResult[0];
//                  if ((shift < minShift) || (shift > maxShift))
//                  {
//                     Log.w(TAG, "Rejected " + nextOffset + " shift = " + shift);
//                     completedBearings.remove(nextOffset);
//                  }
//               }
//            }
//         }
//      }
//   }
//
//   class ShiftCheckFrameWriterThread implements FrameWriteable
//   //=========================================================
//   {
//      private final RandomAccessFile framesRAF;
//      @Override public RandomAccessFile getFile() { return framesRAF; }
//      final int bufferSize = renderer.nv21BufferSize;
//      boolean mustStop = false;
//      public void stop() { mustStop = true; }
//      long cachedOffset = -1;
//      int minShift = 2, maxShift = 20;
//      volatile int count = 0;
//      public int getCount() { return count; }
//      public void incrementCount() { count++; }
//
//      public ShiftCheckFrameWriterThread(int minshift, int maxshift) throws IOException
//      //----------------------------------------------------
//      {
//         final String name;
//         name = (renderer.recordFileName == null) ? "Unknown" : renderer.recordFileName;
//         renderer.recordFramesFile = new File(renderer.recordDir, name + ".frames.part");
//         framesRAF = new RandomAccessFile(renderer.recordFramesFile, "rw");
//         minShift = minshift;
//         maxShift = maxshift;
//      }
//
//      public boolean flush()
//      //-----------------
//      {
//         try { framesRAF.getFD().sync(); return true; } catch (Exception _ee) { Log.e(TAG, "", _ee); return false; }
//      }
//
//      @Override
//      public void run()
//      //--------------
//      {
//         Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE);
//         FrameAndOffset frameBuffer;
//         int[] shiftResult = new int[2];
//         try
//         {
//            while (true)
//            {
//               if ( (mustStop) && (bufferQueue.isEmpty()) )
//                  break;
//               try { frameBuffer = bufferQueue.take(); } catch (InterruptedException e) { break; }
//               if ( (frameBuffer == null) || (frameBuffer.buffer == null) || (frameBuffer.offset < 0) ) continue;
//               if (completedBearings.containsKey(frameBuffer.offset)) continue;
//               try
//               {
//                  long off = frameBuffer.offset - 1;
//                  if (off < 0)
//                     off = largestBearing;
//                  if (completedBearings.containsKey(off))
//                  {
//                     if (off != cachedOffset)
//                     {
//                        if (read(framesRAF, bufferSize, off, previousBuffer))
//                           cachedOffset = off;
//                     }
//                     if (off == cachedOffset)
//                     {
//                        CV.shifted(renderer.previewWidth, renderer.previewHeight, previousBuffer, previewBuffer,
//                                   shiftResult);
//                        int shift = shiftResult[0];
//                        if ((shift < minShift) || (shift > maxShift))
//                        {
//                           Log.d(TAG, "Rejected " + frameBuffer.offset + " shift = " + shift);
//                           continue;
//                        }
//                     }
//                  }
//                  System.arraycopy(previewBuffer, 0, previousBuffer, 0, previewBuffer.length);
//                  cachedOffset = frameBuffer.offset;
//
//                  framesRAF.seek(frameBuffer.offset * bufferSize);
//                  framesRAF.write(frameBuffer.buffer);
//                  count++;
//                  completedBearings.put(frameBuffer.offset, frameBuffer.timestamp);
//
////                  logWriter.write("Wrote " + frameBuffer.offset + " timestamp " + frameBuffer.timestamp); logWriter.newLine();
////                  timestampWriter.printf("%d=%d\n", frameBuffer.offset, frameBuffer.timestamp);
//                  if (WRITE_FRAME_FILES)
//                  {
//                     BufferedOutputStream bos = null;
//                     try
//                     {
//                        bos = new BufferedOutputStream(new FileOutputStream(new File(renderer.recordDir,
//                                                                                     Long.toString(frameBuffer.offset))), 32768);
//                        bos.write(frameBuffer.buffer);
//                     }
//                     catch (Exception _e)
//                     {
//                        Log.e(TAG, "", _e);
//                     }
//                     finally
//                     {
//                        if (bos != null)
//                           try { bos.close(); } catch (Exception _e) {}
//                     }
//                  }
//               }
//               catch (IOException e)
//               {
//                  Log.e(TAG, "Error seeking/writing frame", e);
//                  throw (new RuntimeException("Error seeking/writing frame", e));
//               }
//            }
//         }
//         catch (Exception e)
//         {
//            Log.e(TAG, "", e);
//         }
//         finally
//         {
//            try { framesRAF.close(); } catch (Exception _e) { Log.e(TAG, "", _e); }
////            try { timestampWriter.close();} catch (Exception _e) { Log.e(TAG, "", _e); }
//         }
//      }
//   }
}
