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

import android.content.Context;
import android.os.ConditionVariable;
import android.os.PowerManager;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;
import to.augmented.reality.android.em.recorder.util.MutablePair;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLongArray;

public class TraverseRecordingThread extends RecordingThread implements Freezeable
//================================================================================
{
   static final private String TAG = TraverseRecordingThread.class.getSimpleName();
   static final long EPSILON = 40000000L;
   public static final boolean DESKTOP_UNIT_TEST = true;
   public static final boolean DESKTOP_SERIALIZE = true;
   private static final double DEFAULT_SHIFT_AVERAGE = 10;
   private static final double DEFAULT_SHIFT_DEVIATION = 4;

   private float bearing = 0, lastBearing = Float.MIN_VALUE, diff = 0;
   private Set<Long> skippedBearings  = new HashSet<>();
   private BearingRingBuffer.RingBufferContent bearingInfo;
   private ExecutorService matchFramesExecutor = null;
   private Future<?> matchFrameFuture = null;
   private FrameWriterThread matchFramesThread = null;
   File recordTimestampsFile = null;
   private final ArrayBlockingQueue<Float> bearingUIQueue = new ArrayBlockingQueue(100);
//   private final ArrayBlockingQueue<ProgressParam> bearingProgressQueue = new ArrayBlockingQueue(100);

   protected TraverseRecordingThread(GLRecorderRenderer renderer, RecorderRingBuffer frameBuffer)
   {
      super(renderer, frameBuffer);
   }

   protected TraverseRecordingThread(GLRecorderRenderer renderer, int nv21BufferSize,
                                     float increment, CameraPreviewThread previewer,
                                     ConditionVariable recordingCondVar, ConditionVariable frameCondVar,
                                     RecorderRingBuffer frameBuffer, BearingRingBuffer bearingBuffer)
   //----------------------------------------------------------------------------------------------------------------
   {
      super(renderer, nv21BufferSize, increment, previewer, recordingCondVar, frameCondVar, frameBuffer, bearingBuffer);
   }

//   @Override public void pause(Bundle B) { super.pause(B); }

   //@Override public void restore(Bundle B) { super.restore(B); }

   class BearingUIThread implements Runnable
   //=======================================
   {
      @Override
      public void run()
      //---------------
      {
         Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE);
         float bearing;//, lastBearing = Float.MAX_VALUE; //, lastUIBearing = 1000;
         ProgressParam progress = new ProgressParam();
         int pass2Count = 0, count  = 0;
         while ( (pass <= 3) && (renderer.isRecording) && (! isCancelled()) )
         {
            try { bearing = bearingUIQueue.take(); } catch (InterruptedException e) { break; }
            if (bearing == Float.MIN_VALUE) break;
            if (bearing < 0)
            {
               bearing = -bearing;
               long offset = (long) (Math.floor(bearing / recordingIncrement));
               float nextBearing = ((++offset) * recordingIncrement) % 360;
               offset = nextIncompleteOffset(nextBearing);
               if (offset >= 0)
               {
                  recordingNextBearing = offset * recordingIncrement;
                  progress.set(bearing, recordingNextBearing, renderer.recordColor,
                               (count * 100) / no);
               }
               int perc = (++count*100)/no;
               progress.setStatus("Pass " + pass + " (" + perc + "%)", perc, false, 0);
               publishProgress(progress);
            }
            float lastDist = distance(bearing, lastBearing);
            if (lastDist > 0)
            {
               renderer.recordColor = GLRecorderRenderer.RED;
               if (pass == 2)
                  pass2Count++;
            }
            else
               renderer.recordColor = GLRecorderRenderer.GREEN;

//            long offset = (long) (Math.floor(bearing / recordingIncrement));
//            float nextBearing = ((++offset) * recordingIncrement) % 360;
//            if (distance(bearing, recordingNextBearing) <= 0)
//            {
//               offset = nextIncompleteOffset(nextBearing, completedBearings);
//               if (offset >= 0)
//               {
////                  recordingLastBearing = recordingNextBearing;
//                  recordingNextBearing = offset * recordingIncrement;
////                  recordingNextOffset = offset;
//   //               recordingNextBearing = recordingNextOffset * recordingIncrement;
//   //               logWriter.write("NextBearing: " + recordingNextBearing + " (" + recordingLastBearing + " " + bearing + ")"); logWriter.newLine();
//               }
//            }
//            else
//               renderer.recordColor = GLRecorderRenderer.BLUE;

            float dist = distance(lastBearing, startBearing);
            if ( (pass == 1) && (frameWriterThread.getCount() > 30) && ( dist >= 0) && (dist <= 2*recordingIncrement) )
            {
               pass++;
               renderer.isPause = true;
               renderer.requestRender();
            }
            else if ( (pass > 1) && (pass2Count > 30) && ( dist >= 0) && (dist <= 2*recordingIncrement) )
            {
               pass++;
               pass2Count = 0;
            }
//            if (Math.abs(bearing - lastUIBearing) >= recordingIncrement)
//            {
//               lastUIBearing = bearing;
//               ProgressParam progress = new ProgressParam();
//               progress.set(bearing, recordingNextBearing, renderer.recordColor,
//                            (frameWriterThread.getCount() * 100) / no);
////               publishProgress(progress);
//               if (! bearingProgressQueue.offer(progress))
//               {
//                  bearingProgressQueue.clear();
//                  bearingProgressQueue.offer(progress);
//               }
//            }
            renderer.requestRender();
         }
      }
   }

   volatile private int pass = 1;

   @Override
   protected Boolean doInBackground(Void... params)
   //--------------------------------------------
   {
      PowerManager powerManager = (PowerManager) renderer.activity.getSystemService(Context.POWER_SERVICE);
      PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakelock");
      wakeLock.acquire();
      renderer.recordColor = GLRecorderRenderer.RED;
      renderer.isPause = true;
      renderer.requestRender();
      ExecutorService bearingUIExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
      {
         @Override
         public Thread newThread(Runnable r)
         //-------------------------------------------
         {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("TraverseRecordingThread.UI");
            return t;
         }
      });
      BearingUIThread bearingUIThread = new BearingUIThread();
      Future<?> bearingUIFuture = null;
      byte[] data;
      try
      {
         recordingCurrentBearing = -1;
//         previewer.clearBuffer();
         startFrameWriter();
         long frameTimestamp;
         previewer.clearBuffer();
         bearingBuffer.clear();
         data = new byte[renderer.nv21BufferSize];
         previewer.awaitFrame(1000, data);
         lastBearing = bearingBuffer.peekBearing();
         while (lastBearing == Float.MIN_VALUE)
         {
            bearingCondVar.close();
            bearingCondVar.block(20);
            lastBearing = bearingBuffer.peekBearing();
         }
         long recordingNextOffset = (long) (Math.floor(lastBearing / recordingIncrement));
         recordingNextBearing = ++recordingNextOffset*recordingIncrement;
         if (recordingNextBearing >= 360)
            recordingNextBearing -= 360;
         File matchFramesDir = null;
         renderer.recordColor = GLRecorderRenderer.GREEN;
         renderer.isPause = false;
         renderer.requestRender();
         bearingUIFuture = bearingUIExecutor.submit(bearingUIThread);
         ProgressParam progress = new ProgressParam();
         progress.setStatus("Follow the arrow", 0, true, Toast.LENGTH_LONG);
         publishProgress(progress);
         int[] completed = new int[no];
         Arrays.fill(completed, -1);
         while (pass == 1)
         {
            data = new byte[renderer.nv21BufferSize];
            frameTimestamp = frameBuffer.peek(data);
            int offset;
            if (frameTimestamp >= 0)
            {
               for (int retry=0; retry<3; retry++)
               {
                  bearingInfo = bearingBuffer.findClosest(frameTimestamp, 5000000L, EPSILON);
                  if (bearingInfo == null)
                  {
                     long bearingTimestamp = bearingBuffer.peekTime();
                     if ( (bearingTimestamp >= 0) && (frameTimestamp > bearingTimestamp) )
                     {
                        bearingCondVar.close();
                        bearingCondVar.block(20);
                     }
                     else
                        break;
                  }
                  else
                     break;
               }
               if (bearingInfo != null)
               {
                  bearing = bearingInfo.bearing;
                  offset = (int) (Math.floor(bearing / recordingIncrement));
                  if ( (completed[offset] < 0) && (distance(lastBearing, bearing) >= 0) )
                  {
                     FrameAndOffset item = new FrameAndOffset(offset, bearingInfo.timestamp, data);
                     if (addFrameToWriteBuffer(item))
                     {
                        lastBearing = bearing;
                        completed[offset] = 1;
                        if (startBearing > 1000)
                           startBearing = offset * recordingIncrement;
                        bearing = -bearing; // negative to indicate UI update
                     }
                     else
                     {
                        renderer.recordColor = GLRecorderRenderer.RED;
                        if (IS_LOGCAT_GOT)
                           Log.i(TAG, "TraverseRecordingThread: Error writing " + bearing);
                     }
                  }
                  bearingUIQueue.put(bearing);
                  Thread.yield();
               }
            }
            else
               Thread.yield();
         }

         renderer.isPause = true;
         renderer.requestRender();
         captureSkipped(startBearing, progress);
         renderer.isPause = true;
         renderer.recordColor = GLRecorderRenderer.GREEN;
         renderer.requestRender();
         stopFrameWriter();
         bearingUIQueue.put(Float.MIN_VALUE);
         bearingUIFuture.get();
         saveCompleted();

         if ( (DESKTOP_SERIALIZE) || (DESKTOP_UNIT_TEST) ) serialize();
         if (isPostProcess)
         {
            kludge(startBearing, progress);
            renderer.isRecording = true;
         }
         else
            renderer.isRecording = false;
         renderer.lastSaveBearing = 0;
         return true;
      }
      catch (Exception e)
      {
         final String errm = "Exception in " + this.getClass().getSimpleName() + " thread: " + e.getMessage();
         logException(errm, e);
         Log.e(TAG, errm, e);
         ProgressParam progress = new ProgressParam();
         progress.setStatus(errm, 100, true, Toast.LENGTH_LONG);
         publishProgress(progress);
         renderer.isRecording = false;
      }
      finally
      {
         wakeLock.release();
         stopFrameWriter();
         if (logWriter != null)
            try { logWriter.close(); logWriter = null; } catch (Exception e) {}
      }
      return null;
   }

   public void kludge(float startBearing, ProgressParam progress) throws FileNotFoundException
   //------------------------------------------------------------------------------------------
   {
      final int bufferSize = renderer.nv21BufferSize;
      final String name;
      name = (renderer.recordFileName == null) ? "Unknown" : renderer.recordFileName;
      renderer.recordFramesFile = new File(renderer.recordDir, name + ".frames.part");
      if (! renderer.recordFramesFile.exists())
         throw new FileNotFoundException(renderer.recordFramesFile.getAbsolutePath() + " not found");
      skippedBearings.clear();
      for (int off=0; off<no; off++)
      {
         if (completedBearings.get(off) < 0)
            skippedBearings.add((long) off);
      }
      RandomAccessFile framesRAF = null;
      try
      {
         framesRAF = new RandomAccessFile(renderer.recordFramesFile, "rws");
         FileChannel channel = framesRAF.getChannel();
         ByteBuffer frame = ByteBuffer.allocateDirect(bufferSize),
                    nextFrame = ByteBuffer.allocateDirect(bufferSize),
                    rgbaFrame = ByteBuffer.allocateDirect(renderer.rgbaBufferSize);
         MutablePair<Double, Double> stats = stats(startBearing, channel, bufferSize, progress, 1, 40, null,
                                                   "Validating frames");
         final double averageShift, deviation;
         if (stats == null)
         {
            averageShift = DEFAULT_SHIFT_AVERAGE;
            deviation = DEFAULT_SHIFT_DEVIATION;
         }
         else
         {
            averageShift = stats.one;
            deviation = stats.two;
         }
         long startOffset = (long) (Math.floor(startBearing / recordingIncrement));
         long offset = startOffset;
         while (skippedBearings.contains(offset))
         {
            startBearing += recordingIncrement;
            if (startBearing >= 360)
               startBearing -= 360;
            offset = (long) (Math.floor(startBearing / recordingIncrement));
            if (offset == startOffset)
               return;
         }
         startOffset = offset;
         float bearing = startBearing, previousBearing;
         channel.position(offset * bufferSize);
         frame.rewind();
         channel.read(frame);
         double badmaxshift = (int) Math.round(averageShift + deviation * 4.5);
         double badminshift = (int) Math.max(Math.round(averageShift - deviation * 4.5), 1);
         int[] result = new int[2];
         int count = 0;
         // Kludge out of range shifts
         do
         {
            int perc = (++count*100)/no;
            progress.setStatus("Fixing shifts: (" + perc + "%)", perc, true, Toast.LENGTH_LONG);
            publishProgress(progress);
            previousBearing = bearing;
            bearing += recordingIncrement;
            if (bearing >= 360)
               bearing -= 360;
            offset = (long) (Math.floor(bearing / recordingIncrement));
            if (offset == startOffset) break;
            if (skippedBearings.contains(offset))
            {
               while ( (skippedBearings.contains(offset)) && (offset != startOffset) )
               {
                  bearing += recordingIncrement;
                  if (bearing >= 360)
                     bearing -= 360;
                  offset = (long) (Math.floor(bearing / recordingIncrement));
               }
               if (offset == startOffset)
                  break;
               channel.position(offset * bufferSize);
               frame.rewind();
               channel.read(frame);
               continue;
            }
            channel.position(offset * bufferSize);
            nextFrame.rewind();
            channel.read(nextFrame);
            CV.SHIFT(renderer.previewWidth, renderer.previewHeight, frame, nextFrame, result);
            int shift = result[0];
            if ( (shift < badminshift) || (shift > badmaxshift) )
            {
               MutablePair<Float, Integer> pp = checkRot(channel, frame, bearing, averageShift, deviation, startOffset,
                                                         bufferSize);
               float nextBearing = pp.one;
               if (nextBearing < 0)
                  break;
               int bearingDist = pp.two;
               long nextOffset = (long) (Math.floor(nextBearing / recordingIncrement));
               channel.position(nextOffset * bufferSize);
               nextFrame.rewind();
               channel.read(nextFrame);
               CV.SHIFT(renderer.previewWidth, renderer.previewHeight, frame, nextFrame, result);
               shift = result[0] / bearingDist;
               if (shift < 1)
                  break;
               float shiftBearing = bearing;
               offset = (long) (Math.floor(shiftBearing / recordingIncrement));
               StringBuilder msg = new StringBuilder();
               int kc = 1;
               do
               {
                  if (! CV.KLUDGE(renderer.previewWidth, renderer.previewHeight, frame, shift*kc++, true, rgbaFrame))
                  {
                     msg.append("Error shifting image for skipped bearing ").append(shiftBearing).append("\n");
                     nextBearing = shiftBearing;
                     nextOffset = (long) (Math.floor(nextBearing / recordingIncrement));
                     channel.position(nextOffset * bufferSize);
                     nextFrame.rewind();
                     channel.read(nextFrame);
                     break;
                  }
                  else
                     msg.append("kludged frame at: ").append(shiftBearing).append("\n");
                  if (skippedBearings.contains(offset))
                  {
                     skippedBearings.remove(offset);
                     completedBearings.set((int) offset, 1);
                  }
                  if (! writeKludgeFile(shiftBearing, rgbaFrame))
                     msg.append("Error writing Kludge file for ").append(shiftBearing).append("\n");
                  shiftBearing += recordingIncrement;
                  if (shiftBearing >= 360)
                     shiftBearing -= 360;
                  offset = (long) (Math.floor(shiftBearing / recordingIncrement));
               } while ( (offset != nextOffset) && (offset != startOffset) );
               if (logWriter != null)
               {
                  logWriter.write(msg.toString());
                  logWriter.newLine();
               }
               if (offset == startOffset) break;
               bearing = nextBearing;
               offset = (long) (Math.floor(bearing / recordingIncrement));
            }
            frame.rewind();nextFrame.rewind();
            frame.put(nextFrame);
         } while (offset != startOffset);

         bearing = startBearing;
         StringBuilder msg = new StringBuilder();
         count = 0;
         do
         {
            int perc = (++count*100)/skippedBearings.size();
            progress.setStatus("Fixing skipped: (" + perc + "%)", perc, true, Toast.LENGTH_LONG);
            publishProgress(progress);
            previousBearing = bearing;
            bearing += recordingIncrement;
            if (bearing >= 360)
               bearing -= 360;
            offset = (long) (Math.floor(bearing / recordingIncrement));
            if (skippedBearings.contains(offset))
            {
               long previousOffset = (long) (Math.floor(previousBearing / recordingIncrement));
               channel.position(previousOffset * bufferSize);
               frame.rewind();
               channel.read(frame);
               if (! CV.KLUDGE(renderer.previewWidth, renderer.previewHeight, frame, (int) averageShift, true,
                              rgbaFrame))
                  msg.append("Error shifting image for skipped bearing ").append(bearing).append("\n");
               else
                  msg.append("kludged frame at: ").append(bearing).append("\n");
               if (! writeKludgeFile(bearing, rgbaFrame))
                  msg.append("Error writing Kludge file for ").append(bearing).append("\n");
               skippedBearings.remove(offset);
               completedBearings.set((int) offset, 1);
            }
         } while (offset != startOffset);
         if ( (logWriter != null) && (msg.length() > 0) )
         {
            logWriter.write(msg.toString());
            logWriter.newLine();
         }
      }
      catch (Exception e)
      {
         if (DESKTOP_UNIT_TEST)
            e.printStackTrace(System.err);
         else
            Log.e(TAG, "", e);
         return;
      }
      finally
      {
         if (framesRAF != null)
            try { framesRAF.close(); } catch (Exception _e) {}
      }
   }

   private MutablePair<Float, Integer> checkRot(FileChannel channel, ByteBuffer okFrame, float bearing,  double average,
                                                double deviation, long startOffset, int bufferSize) throws IOException
   //------------------------------------------------------------------------------------------------------------------
   {
      ByteBuffer nextFrame = ByteBuffer.allocateDirect(bufferSize);
      long offset;
      double badmaxshift = (int) Math.round(average + deviation * 4.5);
      double badminshift = (int) Math.max(Math.round(average - deviation * 4.5), 1);
      int[] result = new int[2];
      int shiftCount = 2, shiftInitial;
      float worstCaseBearing = -1;
      double bestDistance = Double.MAX_VALUE;
      MutablePair<Float, Integer> res = new MutablePair<>(-1.0f, 0);
      do
      {
         bearing += recordingIncrement;
         if (bearing >= 360)
            bearing -= 360;
         offset = (long) (Math.floor(bearing / recordingIncrement));
         if (! skippedBearings.contains(offset))
         {
            channel.position(offset * bufferSize);
            nextFrame.rewind();
            channel.read(nextFrame);
            CV.SHIFT(renderer.previewWidth, renderer.previewHeight, okFrame, nextFrame, result);
            shiftInitial = result[0];
            double initialMin = badminshift * shiftCount;
            double initialMax = badmaxshift * shiftCount;
            boolean isInitialBad = ((shiftInitial < initialMin) || (shiftInitial > initialMax));
            if (! isInitialBad)
            {
               res.one = bearing;
               res.two = shiftCount;
               return res;
            }
            if (shiftInitial > 3 * shiftCount)
            {
               final double distance;
               if (shiftInitial < initialMin)
                  distance = initialMin - shiftInitial;
               else if (shiftInitial > initialMax)
                  distance = shiftInitial - initialMax;
               else
                  distance = 0;
               if (distance < bestDistance)
               {
                  worstCaseBearing = bearing;
                  bestDistance = distance;
               }
            }
         }
      } while ( (offset != startOffset) && (shiftCount++ < 12) );
      res.one = worstCaseBearing;
      res.two = shiftCount - 1;
      return res;
   }

   private boolean writeKludgeFile(float bearing, ByteBuffer rgbaFrame)
   //---------------------------------------------------------------
   {
      File shiftFile = new File(renderer.recordDir, String.format("%.1f.rgba", bearing));
      FileOutputStream fos = null;
      try
      {
         fos = new FileOutputStream(shiftFile);
         rgbaFrame.rewind();
         fos.getChannel().write(rgbaFrame);
         return true;
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         return false;
      }
      finally
      {
         if (fos != null)
            try { fos.close(); } catch (Exception _e) {}
      }
   }

   private void captureSkipped(float startBearing, ProgressParam progress) throws IOException
   //-------------------------------------------------------------------------------------------
   {
      final long[] completed = super.completedArray();
      int noSkipped = 0;
      for (int i=0; i<no; i++)
      {
         if (completed[i] < 0)
            noSkipped++;
      }

      Log.i(TAG, "captureSkipped(" + startBearing + ") skipped = " + noSkipped);
      if (noSkipped == 0)
         return;

      long count = completedLength();
      long offset = nextIncompleteOffset(startBearing);
      if (offset < 0)
      {
         Log.w(TAG, "captureSkipped: No outstanding bearings (" + count + ")");
         return;
      }
      //recordingLastBearing = recordingNextBearing;
      recordingNextBearing = offset * recordingIncrement;
      renderer.recordColor = GLRecorderRenderer.GREEN;
      renderer.isPause = false;
      renderer.requestRender();
      boolean isExiting = false;
      int exitCount = 0;
      float targetBearing = recordingNextBearing;
      if (! DESKTOP_UNIT_TEST)
      {
         progress.setStatus("Pass 2: Capture skipped bearings", (frameWriterThread.getCount()*100)/no, false, 0);
         publishProgress(progress);
      }
      renderer.recordColor = GLRecorderRenderer.GREEN;
      renderer.isPause = false;
      renderer.requestRender();
      class FrameListener implements CameraPreviewThread.FrameListenable
      //-----------------------------------------------------------------
      {
         volatile public boolean isRecording = false;

         @Override
         public void onFrameAvailable(byte[] data, long timestamp)
         //------------------------------------------------------
         {
            if (isRecording)
            {
               int offset = (int) (Math.floor(renderer.currentBearing / recordingIncrement));
               if (completed[offset] < 0)
               {
                  FrameAndOffset item = new FrameAndOffset(offset, bearingInfo.timestamp, Arrays.copyOf(data, data.length));
                  if (addFrameToWriteBufferNoWait(item))
                  {
                     completed[offset] = 1;
                     lastBearing = bearing;
                     bearingUIQueue.offer(-bearing);
                  }
               }
            }
         }
      }
      FrameListener frameListener = new FrameListener();
      previewer.setFrameListener(frameListener);
      while ( (pass <= 3) && (renderer.isRecording) && (! isCancelled()) )
      {
         if (count >= no)
         {
            if (frameListener.isRecording)
               isExiting = true;
            else
               break;
         }
         try
         {
            bearing = renderer.currentBearing;
            final float dist = distance(bearing, recordingNextBearing);
            offset = (long) (Math.floor(bearing / recordingIncrement));
            if (Math.abs(dist) <= 2)
            {
               if (! frameListener.isRecording)
                  frameListener.isRecording = true;
               renderer.recordColor = GLRecorderRenderer.PURPLE;
            }
            else
            {
               if (frameListener.isRecording)
               {
                  frameListener.isRecording = false;
                  if (isExiting) break;
               }
               renderer.recordColor = GLRecorderRenderer.BLUE;
            }

            if (dist <= 0)
            {
               float nextBearing = (++offset) * recordingIncrement;
               if (nextBearing >= 360)
                  nextBearing -= 360;
               long nextoffset = (long) Math.floor(nextBearing / recordingIncrement);
               offset = nextIncompleteOffset(nextBearing);
               if (offset < 0)
               {
                  if (! frameListener.isRecording)
                     break;
                  else
                     isExiting = true;
                  offset = nextoffset;
               }
               targetBearing = offset * recordingIncrement;
               if (Math.abs(nextBearing - recordingNextBearing) > 2)
               {
                  if (isExiting) break;
                  //recordingLastBearing = recordingNextBearing;
                  recordingNextBearing = targetBearing;
                  bearingUIQueue.offer(recordingNextBearing);
               }
               if ( (! frameListener.isRecording) && (Math.abs(distance(bearing, targetBearing)) <= 2) )
               {
                  frameListener.isRecording = true;
                  renderer.recordColor = GLRecorderRenderer.PURPLE;
               }
//               recordingNextBearing = offset * recordingIncrement;
            }
            renderer.requestRender();
         }
         catch (Exception e)
         {
            logException(e.getMessage(), e);
            Log.e(TAG, "", e);
            continue;
         }
      }
   }

   private MutablePair<Double, Double> stats(final float startBearing, final FileChannel channel, final int bufferSize,
                                             final ProgressParam progress, final int minShift, final int maxShift,
                                             final Set<Float> badBearings, String progressMessage)
   //------------------------------------------------------------------------------------------------------------------
   {
      ByteBuffer frame = ByteBuffer.allocateDirect(bufferSize), frame2 = null;
      final long startOffset = (long) (Math.floor(startBearing / recordingIncrement));
      long off = startOffset;
      int sampleCount = 0, totalShift = 0;
      int[] result = new int[2];
      List<Integer> allShifts = new ArrayList<>();
      int c = 0;
      float bearing = startBearing;
      do
      {
         try
         {
            if (! DESKTOP_UNIT_TEST)
            {
               int perc = (c++ * 100) / no;
               String message = progressMessage + "(" + perc + "%)";
               progress.setStatus(message, perc, true, Toast.LENGTH_SHORT);
               publishProgress(progress);
            }
            if (completedBearings.get((int) off) >= 0)
            {
               channel.position(off * bufferSize);
               frame.rewind();
               channel.read(frame);
               if (frame2 == null)
                  frame2 = ByteBuffer.allocateDirect(bufferSize);
               else
               {
                  CV.SHIFT(renderer.previewWidth, renderer.previewHeight, frame2, frame, result);
                  final int shift = result[0];
                  if ( (shift <= minShift) || (shift >= maxShift) )
                  {
//                     completedBearings.set((int) off, -1);
                     if (badBearings != null)
                        badBearings.add(off * recordingIncrement);
                     frame2 = null;
                  }
                  else
                  {
                     totalShift += shift;
                     allShifts.add(shift);
                     sampleCount++;
                  }
               }
               frame.rewind();
               if (frame2 != null)
               {
                  frame2.rewind();
                  frame2.put(frame);
               }
            }
            else
               frame2 = null;
            bearing += recordingIncrement;
            if (bearing >= 360)
               bearing -= 360;
            off = (long) (Math.floor(bearing / recordingIncrement));
         }
         catch (Exception e)
         {
            final String errm = "Exception in " + this.getClass().getSimpleName() + ".stats " + e.getMessage();
            e.printStackTrace();
            progress.setStatus(errm, 100, true, Toast.LENGTH_LONG);
            publishProgress(progress);
            logException(errm, e);
            Log.e(TAG, "", e);
         }
      } while (off != startOffset);

      double averageShift = 0, deviation = 0;
      if (sampleCount > 5)
      {
         averageShift = (double) totalShift / (double) sampleCount;
         double sum = 0.0;
         for (int ashift : allShifts)
            sum += (ashift - averageShift) * (ashift - averageShift);
         deviation = Math.sqrt(sum / (double) (allShifts.size() - 1));
         return new MutablePair<>(averageShift, deviation);
      }
      return null;
   }

   private void saveCompleted()
   //--------------------------
   {
      BufferedWriter bw = null;
      PrintWriter headerWriter = null;
      try
      {
         bw = new BufferedWriter(new FileWriter(new File(renderer.recordDir, "completed.bearings")));
         for (int i=0; i<no; i++)
         {
            bw.write(i + "=" + completedBearings.get(i));
            bw.newLine();
         }
         bw.close();
         bw = new BufferedWriter(new FileWriter(new File(renderer.recordDir, "skipped.bearings")));
         for (long bb : skippedBearings)
         {
            bw.write(Float.toString(bb*recordingIncrement));
            bw.newLine();
         }
         String name = (renderer.recordFileName == null) ? "Unknown" : renderer.recordFileName;
         File recordHeaderFile = new File(renderer.recordDir, name + ".head");
         if (recordHeaderFile.exists())
            recordHeaderFile.delete();
         headerWriter = new PrintWriter(recordHeaderFile);
         headerWriter.println(String.format("Increment=%6.2f", recordingIncrement));
         headerWriter.println(String.format("BufferSize=%d", renderer.nv21BufferSize));
         headerWriter.println(String.format("PreviewWidth=%d", renderer.previewWidth));
         headerWriter.println(String.format("PreviewHeight=%d", renderer.previewHeight));
         headerWriter.println(String.format("StartBearing=%.1f", startBearing));
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
      }
      finally
      {
         if (bw != null)
            try { bw.close(); } catch (Exception _e) {}
         if (headerWriter != null)
            try { headerWriter.close(); } catch (Exception _e) {}
      }
   }

   //#ifdef(DESKTOP_UNIT_TEST)
   public void serialize() throws IOException, ClassNotFoundException
   //------------------------------------------------------------------
   {
      if (DESKTOP_SERIALIZE)
      {
         try
         {
            File dir = new File(renderer.recordDir, "debug");
            if (!dir.exists())
               dir.mkdirs();
            File completeSerial = new File(dir, "complete.ser");
            File skippedSerial = new File(dir, "skipped.ser");
            File bearingTimestampsFile = new File(dir, "skipped.bearings");
            if ((completeSerial.exists()) && (skippedSerial.exists()))
            {
               ObjectInputStream serializeStream = new ObjectInputStream(new FileInputStream(completeSerial));
               completedBearings = (AtomicLongArray) serializeStream.readObject();
               serializeStream.close();
               serializeStream = new ObjectInputStream(new FileInputStream(skippedSerial));
               skippedBearings = (Set<Long>) serializeStream.readObject();
               serializeStream.close();
            }
            else if (completedLength() > 0)
            {
               ObjectOutputStream serializeStream = new ObjectOutputStream(new FileOutputStream(completeSerial));
               serializeStream.writeObject(completedBearings);
               serializeStream.close();
               serializeStream = new ObjectOutputStream(new FileOutputStream(skippedSerial));
               serializeStream.writeObject(skippedBearings);
               serializeStream.close();
               //            FileChannel ch1 = new FileInputStream(recordTimestampsFile).getChannel();
               //            FileChannel ch2 = new FileOutputStream(bearingTimestampsFile).getChannel();
               //            ch2.transferFrom(ch1, 0, ch1.size());
               //            ch1.close(); ch2.close();
            }
         }
         catch (Exception e)
         {
            Log.e(TAG, "", e);
         }
      }
   }
   //#endif

   private ByteBuffer nextFrame = null;

/*
   private float checkBearingDiscrepencies(float newBearing, Set<Long> completedBearings, ProgressParam progress)
   //------------------------------------------------------------------------------------------------------------
   {
      final String message = "Large number of bearing discrepancies. Try to recalibrate by waving device "
            + "in a figure of 8 motion. ";
      int discrepancies = 0;
      renderer.recordColor = GLRecorderRenderer.RED;
      renderer.isPause = true;
      renderer.requestRender();
      do
      {
         Log.e(TAG, String.format("Large bearing discrepancy %.5f %.5f", newBearing, lastBearing));
         discrepancies++;
         if (discrepancies == 100)
         {
            frameWriterThread.count = 0;
            //matchFramesThread.clear();
            completedBearings.clear();
            skippedBearings.clear();
            bearingBuffer.clear();
            renderer.initOrientationSensor(null);
            ConditionVariable cond = new ConditionVariable(false);
            renderer.activity.userRecalibrate(cond, message +
                  "Restarting recording. Orient camera and press OK to continue");
            cond.block();
            progress.setStatus(String.format("%d bearing discrepancies. Restarting", discrepancies), 0, true,
                               Toast.LENGTH_LONG);
            publishProgress(progress);
            bearingCondVar.close();
            bearingCondVar.block(30);
            bearing = lastBearing = bearingBuffer.peekBearing();

            long offset = (long) (Math.floor(bearing / recordingIncrement));
            float nextBearing = ((++offset) * recordingIncrement) % 360;
            offset = nextIncompleteOffset(nextBearing, completedBearings);
            if (offset >= 0)
            {
               recordingLastBearing = recordingNextBearing;
               recordingNextBearing = offset * recordingIncrement;
               offset = (long) (Math.floor(recordingNextBearing / recordingIncrement));
               recordingNextBearing = offset * recordingIncrement;
            }
            progress.set(bearing, recordingNextBearing, renderer.recordColor, (frameWriterThread.count * 100) / no);
            discrepancies = 0;
         }
         else if (discrepancies == 20)
         {
            bearingBuffer.clear();
            ConditionVariable cond = new ConditionVariable(false);
            renderer.activity.userRecalibrate(cond, message +
                  "When done orient camera at last recording location and press OK");
            cond.block();
            bearingBuffer.clear();
            bearingCondVar.close();
            bearingCondVar.block(30);
            bearing = bearingBuffer.peekBearing();
            progress.set(bearing, recordingNextBearing, renderer.recordColor, (frameWriterThread.count * 100) / no);
         }
         else if (discrepancies > 3)
         {
            progress.setStatus(String.format("%d bearing discrepancies. Resyncing", discrepancies), 0, true,
                               Toast.LENGTH_LONG);
            bearingBuffer.clear();
            bearingCondVar.close();
            bearingCondVar.block(30);
         }
         bearingInfo = bearingBuffer.peekHead();
         while (bearingInfo == null)
         {
            bearingBuffer.clear();
            bearingCondVar.close();
            bearingCondVar.block(20);
            bearingInfo = bearingBuffer.peekHead();
         }
         bearing = bearingInfo.bearing;
         diff = bearing - lastBearing;
      } while (Math.abs(diff) >= 10);
      renderer.recordColor = GLRecorderRenderer.GREEN;
      renderer.isPause = false;
      renderer.requestRender();
      return bearing;
   }
*/
}
