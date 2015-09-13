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

import android.os.ConditionVariable;
import android.util.Log;
import android.widget.Toast;
import to.augmented.reality.android.common.math.QuickFloat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
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
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class TraverseRecordingThread extends RecordingThread implements Freezeable
//================================================================================
{
   static final private String TAG = TraverseRecordingThread.class.getSimpleName();
   static final long epsilon = 60000000L;
   private static final boolean DEBUG_MATCH = true;

   private float recordingLastBearing = -1.0f, startBearing = 1001, bearing = 0, lastBearing = Float.MIN_VALUE, diff = 0,
                 minBearingCorrectionDelta = 1.8f * recordingIncrement;
   private Set<Float> skippedBearings  = new HashSet<>();
   private int discrepancies = 0;
   private BearingRingBuffer.RingBufferContent bearingInfo;
   private ExecutorService matchFramesExecutor = null;
   private Future<?> matchFrameFuture = null;
   private FrameWriterThread matchFramesThread = null;
   private File recordTimestampsFile = null;

   protected TraverseRecordingThread(GLRecorderRenderer renderer, RecorderRingBuffer frameBuffer) { super(renderer,
                                                                                                           frameBuffer); }

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

   private float bearingCorrection = 0;
   private long  bearingCorrectionTime = Long.MAX_VALUE;

   @Override
   protected Boolean doInBackground(Void... params)
   //--------------------------------------------
   {
//      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
      renderer.recordColor = GLRecorderRenderer.GREEN;
      if (previewBuffer == null)
         previewBuffer = new byte[renderer.nv21BufferSize];
      if (previousBuffer == null)
         previousBuffer = new byte[renderer.nv21BufferSize];
      ProgressParam progress = new ProgressParam();
      renderer.recordColor = GLRecorderRenderer.RED;
      renderer.isPause = true;
      renderer.requestRender();
      try
      {
         float lastUIBearing = 1000;
         recordingCurrentBearing = -1;
//         previewer.clearBuffer();
         startFrameWriter();
         float nextBearing, startBearingDist = -1;
         long frameTimestamp;
         previewer.clearBuffer();
         previewer.awaitFrame(1000, previewBuffer);
         bearingBuffer.clear();
         lastBearing = bearingBuffer.peekBearing();
         while (lastBearing == Float.MIN_VALUE)
         {
            bearingCondVar.close();
            bearingCondVar.block(20);
            lastBearing = bearingBuffer.peekBearing();
         }
         minBearingCorrectionDelta = 1.8f * recordingIncrement;
         boolean mustMatch = true;
         File matchFramesDir = null;
         renderer.recordColor = GLRecorderRenderer.GREEN;
         renderer.isPause = false;
         renderer.requestRender();
         int discrepancyCount = 0;
         while ( (frameWriterThread.count < no) && (renderer.isRecording) && (! isCancelled()) )
         {
            frameTimestamp = frameBuffer.peek(previewBuffer);
            if (frameTimestamp < 0)
               frameTimestamp = previewer.awaitFrame(200, previewBuffer);
            long offset = -1;
            if (frameTimestamp >= 0)
            {
               bearingInfo = null;
               for (int retry=0; retry<3; retry++)
               {
                  bearingInfo = bearingBuffer.find(frameTimestamp, epsilon);
                  if (bearingInfo == null)
                  {
                     bearingCondVar.close();
                     bearingCondVar.block(20);
                  }
                  else
                     break;
               }
               if (bearingInfo != null)
               {
                  bearing = bearingInfo.bearing;
                  if (bearingInfo.timestamp >= bearingCorrectionTime)
                  {
                     bearing -= bearingCorrection;
                     if (bearing < 0)
                        bearing += 360;
                     if ( (Math.floor(bearing) > 360) || (bearing < 0) )
                        Log.e(TAG, "Invalid bearing " + bearing);
                  }
                  if (Math.abs(bearing - recordingNextBearing) < recordingIncrement)
                  {
                     offset = (long) (Math.floor(bearing / recordingIncrement));
                     if (! completedBearings.contains(offset))
                     {
                        if (addFrameToWriteBuffer(offset, bearingInfo.timestamp))
                        {
                           lastFrameTimestamp = frameTimestamp;
                           if (startBearing > 1000)
                           {
                              startBearing = offset * recordingIncrement;
                              if (startBearing >= 350)
                                 startBearingDist = 360 - startBearing;
                              else
                                 startBearingDist = startBearing;
                              lastUIBearing = startBearing;
                           }
                           renderer.recordColor = GLRecorderRenderer.GREEN;
                        } else
                        {
                           renderer.recordColor = GLRecorderRenderer.RED;
                           if (IS_LOGCAT_GOT)
                              Log.i(TAG, "TraverseRecordingThread2: Error writing " + bearing);
                        }
                     }
                  }
               }
               else
                  bearing = -1;
            }

            bearingInfo = bearingBuffer.peekHead(); // Get latest bearing
            if (bearingInfo != null)                // if possible
            {
               float newBearing = bearingInfo.bearing - bearingCorrection;
               if (newBearing < 0)
                  newBearing += 360;
               if (lastBearing < 0)
                  lastBearing = newBearing;
               diff = newBearing - lastBearing;
               while ( (diff >= 8) && (discrepancyCount < 3) )
               {
                  bearingInfo = bearingBuffer.peekHead();
                  if (bearingInfo == null) continue;
                  newBearing = bearingInfo.bearing - bearingCorrection;
                  if (newBearing < 0)
                     newBearing += 360;
                  diff = newBearing - lastBearing;
                  if (diff >= 8)
                     discrepancyCount++;

               }
               if (discrepancyCount >= 3)
               {
                  checkBearingDiscrepencies(newBearing, completedBearings, progress);
                  discrepancyCount = 0;
                  continue;
               }
               else
                  discrepancyCount = 0;
               if (newBearing == Float.MIN_VALUE)
                  break;
               discrepancies = 0;
               lastBearing = bearing;
               bearing = newBearing;
               if ( (lastBearing > 350) && (bearing < 10) )
                  diff = bearing + (360 - lastBearing);
               else if ( (bearing > 350) && (lastBearing < 10) )
                  diff = -(lastBearing + (360 - bearing));
               else
                  diff = bearing - lastBearing;

               if ( (lastBearing >= 0) && (Math.abs(diff) > minBearingCorrectionDelta) )
               {
                  bearingCorrection = diff;
                  if (bearingCorrectionTime == Long.MAX_VALUE)
                     bearingCorrectionTime = bearingInfo.timestamp;
//                  Log.i(TAG, "Set bearing correction " + bearingCorrection + " " + bearing + " - " + lastBearing + " = " + diff);
                  bearing = lastBearing;

               }
            }
            else
               continue;

            offset = (long) (Math.floor(bearing / recordingIncrement));
            nextBearing = ((++offset) * recordingIncrement) % 360;
            if (QuickFloat.compare(nextBearing, recordingNextBearing, 0.00001f) != 0)
            {
               offset = nextOffset(nextBearing, completedBearings);
               if (offset < 0)
                  break;

               recordingLastBearing = recordingNextBearing;
               recordingNextBearing = offset * recordingIncrement - bearingCorrection;
               if (recordingNextBearing < 0)
                  recordingNextBearing += 360;
               offset = (long) (Math.floor(recordingNextBearing / recordingIncrement));
               recordingNextBearing = offset * recordingIncrement;

               if ( (frameWriterThread.count > 20) && (Math.abs(distance(startBearing, recordingNextBearing)) <= 10) )
               {
                  matchFramesDir = writeMatchFrames(progress);
                  mustMatch = true;
                  break;
               }
            }
            else
               renderer.recordColor = GLRecorderRenderer.BLUE;
            if (Math.abs(bearing - lastUIBearing) >= recordingIncrement)
            {
               lastUIBearing = bearing;
               progress.set(bearing, recordingNextBearing, renderer.recordColor, (frameWriterThread.count * 100) / no);
               publishProgress(progress);
            }
            renderer.requestRender();
         }
         stopFrameWriter();
         if (matchFrameFuture != null)
         {
            matchFramesThread.enqueue(new FrameWriterThread.FrameFile());
            try {Thread.sleep(200); } catch (Exception _e) {}
            matchFramesExecutor.shutdown();
            matchFrameFuture.get();
            matchFramesExecutor.shutdownNow();
         }

         renderer.lastSaveBearing = 0;
//         if (error != null)
//         {
//            progress.setStatus(error, 100, true, Toast.LENGTH_LONG);
//            publishProgress(progress);
//            renderer.isRecording = false;
//            return false;
//         }
         if (mustMatch)
            matchFrames(matchFramesDir);
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

   //#ifdef(DEBUG_MATCH)
   private void serialize() throws IOException, ClassNotFoundException
   //------------------------------------------------------------------
   {
      if (DEBUG_MATCH)
      {
         File dir = new File("/sdcard/Documents/ARRecorder/debug");
         if (! dir.exists())
            dir.mkdirs();
         File completeSerial = new File(dir, "complete.ser");
         File skippedSerial= new File(dir, "skipped.ser");
         File bearingTimestampsFile = new File(dir, "skipped.bearings");
         if ( (completeSerial.exists()) && (skippedSerial.exists()) )
         {
            ObjectInputStream serializeStream = new ObjectInputStream(new FileInputStream(completeSerial));
            completedBearings = (Set<Long>) serializeStream.readObject();
            serializeStream.close();
            serializeStream = new ObjectInputStream(new FileInputStream(skippedSerial));
            skippedBearings = (Set<Float>) serializeStream.readObject();
            serializeStream.close();
         }
         else if (completedBearings.size() > 0)
         {
            ObjectOutputStream serializeStream = new ObjectOutputStream(new FileOutputStream(completeSerial));
            serializeStream.writeObject(completedBearings);
            serializeStream.close();
            serializeStream = new ObjectOutputStream(new FileOutputStream(skippedSerial));
            serializeStream.writeObject(skippedBearings);
            serializeStream.close();
            FileChannel ch1 = new FileInputStream(recordTimestampsFile).getChannel();
            FileChannel ch2 = new FileOutputStream(bearingTimestampsFile).getChannel();
            ch2.transferFrom(ch1, 0, ch1.size());
            ch1.close(); ch2.close();
         }
      }
   }
   //#endif

   public class MutableTriple<T1, T2, T3>
   {
      public T1 one;
      public T2 two;
      public T3 three;

      public MutableTriple(T1 one, T2 two, T3 three) { this.one = one; this.two = two; this.three = three; }
   }

   @SuppressWarnings("unchecked")
   //Public for tests
   public boolean matchFrames(File matchFramesDir)
   //-----------------------------------------------
   {
      File framesFile = renderer.recordFramesFile;
      if ( (! framesFile.exists()) || (framesFile.length() == 0) )
         return false;
      File bearingTimestampsFile;
      if (DEBUG_MATCH)
      {
         try { serialize(); } catch (Exception e) { Log.e(TAG, "serialize", e); throw new RuntimeException(e); }
         bearingTimestampsFile = new File("/sdcard/Documents/ARRecorder/debug/skipped.bearings");
      }
      else
         bearingTimestampsFile = recordTimestampsFile;
      Map<Long, SortedSet<Long>> skippedBearingTimes = new HashMap<>();
      BufferedReader br = null;
      try
      {
         br = new BufferedReader(new FileReader(bearingTimestampsFile));
         String s;
         long offset, timestamp;
         while ( (s = br.readLine()) != null)
         {
            String[] as = s.split("=");
            if (as.length < 2) continue;
            try
            {
               offset = Long.parseLong(as[0].trim());
               timestamp = Long.parseLong(as[1].trim());
               SortedSet<Long> bearingList = skippedBearingTimes.get(offset);
               if (bearingList == null)
               {
                  bearingList = new TreeSet<>();
                  skippedBearingTimes.put(offset, bearingList);
               }
               bearingList.add(timestamp);
            }
            catch (Exception e)
            {
               Log.e(TAG, "", e);
            }
         }
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         return false;
      }
      finally
      {
         if (br != null)
            try { br.close(); } catch (Exception e) {}
      }

      TreeMap<Long, File> frameFileMap = new TreeMap<>();
      File[] frameFiles = matchFramesDir.listFiles();
      for (File f : frameFiles)
      {
         long timestamp;
         try { timestamp = Long.parseLong(f.getName().trim()); } catch (Exception _e) { continue; }
         frameFileMap.put(timestamp, f);
      }
      if ( (! bearingTimestampsFile.exists()) || (bearingTimestampsFile.length() == 0) )
         return false;
      Float[] skippedBearingArray = skippedBearings.toArray(new Float[skippedBearings.size()]);
      Arrays.sort(skippedBearingArray);
      RandomAccessFile framesRAF = null;
      final int bufferSize = renderer.nv21BufferSize;
      try
      {
         framesRAF = new RandomAccessFile(renderer.recordFramesFile, "rw");
         FileChannel channel = framesRAF.getChannel();
         ByteBuffer frame = ByteBuffer.allocateDirect(bufferSize), frame2 = null,
                    tmpFrame = ByteBuffer.allocateDirect(bufferSize);
         SortedSet<Long> bearingList = null;
         double psnr, psnrRight;
         NavigableMap<Long, File> candidateFrames;
         Set<File> checkedFiles = new HashSet<>(), completedFiles = new HashSet<>();
         File writeFile = null;
         for (int i=0; i < skippedBearingArray.length; i++)
         {
            float skippedBearing = skippedBearingArray[i];
            final long offset = (long) (Math.floor(skippedBearing / recordingIncrement));
            long previousOffset = previousCompleteOffset(skippedBearing);
            long nextOffset = nextCompleteOffset(skippedBearing);
            int previousDist = (int) (offset - previousOffset);
            int nextdist = (int) (nextOffset - offset);
            bearingList = skippedBearingTimes.get(offset);
            if ( (previousDist == 1) && (nextdist == 1) )
            {
               channel.position(previousOffset * bufferSize);
               frame.rewind();
               channel.read(frame);
               channel.position(nextOffset * bufferSize);
               if (frame2 == null)
                  frame2 = ByteBuffer.allocateDirect(bufferSize);
               frame2.rewind();
               channel.read(frame2);
               List<MutableTriple<Double, Double, File>> combinedList = new ArrayList<>();
               MutablePair<Double, File> maxLeft = new MutablePair<>(-1.0, null), maxRight = new MutablePair<>(-1.0, null);
               if ( (bearingList != null) && (! bearingList.isEmpty()) )
               {
                  long mintime = bearingList.first();
                  long maxtime = bearingList.last();
                  candidateFrames = frameFileMap.tailMap(mintime, true).headMap(maxtime, true);
                  Collection<File> files = candidateFrames.values();
                  checkedFiles.clear();
                  for (File f : files)
                  {
                     if (completedFiles.contains(f)) continue;
                     checkedFiles.add(f);
                     psnr = filePSNR(f, frame, tmpFrame);
                     if (psnr > maxLeft.one)
                     {
                        maxLeft.one = psnr;
                        maxLeft.two = f;
                     }
                     psnrRight = filePSNR(f, frame2, tmpFrame);
                     if (psnrRight > maxRight.one)
                     {
                        maxRight.one = psnrRight;
                        maxRight.two = f;
                     }
                     if ( (psnr > 14) && (psnrRight > 14) )
                        combinedList.add(new MutableTriple<>(psnr, psnrRight, f));
                  }
               }
               Collections.sort(combinedList, new Comparator<MutableTriple<Double, Double, File>>() {
                  @Override
                  public int compare(MutableTriple<Double, Double, File> lhs, MutableTriple<Double, Double, File> rhs)
                  {
                     return Double.compare(lhs.one, rhs.one);
                  }
               });
               if ( ((maxLeft.one <= 15) && (maxRight.one <= 15)) || (combinedList.size() < 3) )
               {
                  candidateFrames = frameFileMap;
                  Collection<File> files = candidateFrames.values();
                  for (File f : files)
                  {
                     if (completedFiles.contains(f)) continue;
                     if (checkedFiles.contains(f)) continue;
                     psnr = filePSNR(f, frame, tmpFrame);
                     if (psnr > maxLeft.one)
                     {
                        maxLeft.one = psnr;
                        maxLeft.two = f;
                     }
                     psnrRight = filePSNR(f, frame2, tmpFrame);
                     if (psnrRight > maxRight.one)
                     {
                        maxRight.one = psnrRight;
                        maxRight.two = f;
                     }
                     if ( (psnr > 14) && (psnrRight > 14) )
                        combinedList.add(new MutableTriple<>(psnr, psnrRight, f));
                  }
               }
               psnr = psnrRight = -1.0;
               if (! combinedList.isEmpty())
               {
                  Collections.sort(combinedList, new Comparator<MutableTriple<Double, Double, File>>() {
                     @Override
                     public int compare(MutableTriple<Double, Double, File> lhs, MutableTriple<Double, Double, File> rhs)
                     {
                        return Double.compare(Math.abs(lhs.one - lhs.two), Math.abs(rhs.one - rhs.two));
                     }
                  });
                  psnr = combinedList.get(0).one;
                  psnrRight = combinedList.get(0).two;
                  if ( ((maxLeft.one - psnr) >= 1.0) || ((maxRight.one - psnrRight) >= 1.0) )
                     psnr = psnrRight = -1;
               }
               if (psnr > 0)
                  writeFile = combinedList.get(0).three;
               else
               {
                  if (maxLeft.one >= maxRight.one)
                     writeFile = maxLeft.two;
                  else
                     writeFile = maxRight.two;
               }
               completedBearings.add(offset);
               frameWriterThread.count++;
            }
            else if ( (previousDist == 1) || (nextdist == 1) )
            {
               if (previousDist == 1)
               {
                  channel.position(previousOffset * bufferSize);
                  int j = i + 1;
                  if (j < skippedBearingArray.length)
                  {
                     float nextSkippedBearing = skippedBearingArray[j];
                     long nextOff = (long) (Math.floor(nextSkippedBearing / recordingIncrement));
                     if ((offset + 1) == nextOff)
                        bearingList.addAll(skippedBearingTimes.get(nextOff));
                  }
               }
               else
               {
                  channel.position(nextOffset * bufferSize);
                  int j = i - 1;
                  if (j >= 0)
                  {
                     float prevSkippedBearing = skippedBearingArray[j];
                     long prevOff = (long) (Math.floor(prevSkippedBearing / recordingIncrement));
                     if ((offset - 1) == prevOff)
                        bearingList.addAll(skippedBearingTimes.get(prevOff));
                  }
               }
               frame.rewind();
               channel.read(frame);

//               FileOutputStream fos = null;
//               try
//               {
//                  fos = new FileOutputStream("/tmp/x/frame");
//                  frame.rewind();
//                  fos.getChannel().write(frame);
//               }
//               catch (Exception _e) { }
//               finally
//               {
//                  if (fos != null) try { fos.close(); } catch (Exception _e) {}
//               }

               double maxPsnr = -1;
               File maxFile = null;
               if ( (bearingList != null) && (! bearingList.isEmpty()) )
               {
                  long mintime = bearingList.first();
                  long maxtime = bearingList.last();
                  if (previousDist == 1)
                     candidateFrames = frameFileMap.tailMap(mintime, true).headMap(maxtime, true);
                  else
                     candidateFrames = frameFileMap.headMap(maxtime, true).tailMap(mintime, true);
                  Collection<File> files = candidateFrames.values();
                  checkedFiles.clear();
                  for (File f : files)
                  {
                     if (completedFiles.contains(f)) continue;
                     checkedFiles.add(f);
                     psnr = filePSNR(f, frame, tmpFrame);
                     //System.out.println(f + " " + psnr);
                     if (psnr > maxPsnr)
                     {
                        maxPsnr = psnr;
                        maxFile = f;
                     }
                  }
               }
               if (maxPsnr < 14)
               {
                  double maxPsnr2 = maxPsnr;
                  File ff = null;
                  candidateFrames = frameFileMap;
                  Collection<File> files = candidateFrames.values();
                  for (File f : files)
                  {
                     if (completedFiles.contains(f)) continue;
                     if (checkedFiles.contains(f)) continue;
                     psnr = filePSNR(f, frame, tmpFrame);
                     if (psnr > maxPsnr2)
                     {
                        maxPsnr2 = psnr;
                        ff = f;
                     }
                  }
                  if (maxPsnr2 > maxPsnr)
                  {
                     if (fileMSSIM(ff, frame, tmpFrame) > fileMSSIM(maxFile, frame, tmpFrame))
                     {
                        maxPsnr = maxPsnr2;
                        maxFile = ff;
                     }
                  }
               }
               writeFile = maxFile;
               completedBearings.add(offset);
               frameWriterThread.count++;
            }

            if (writeFile != null)
            {
               FileInputStream fis = null;
               FileChannel readChannel = null;
               try
               {
                  fis = new FileInputStream(writeFile);
                  readChannel = fis.getChannel();
                  tmpFrame.rewind();
                  readChannel.read(tmpFrame);
                  channel.position(offset * bufferSize);
                  channel.write(tmpFrame);
                  completedFiles.add(writeFile);
                  try { framesRAF.getFD().sync(); } catch (Exception _ee) { Log.e(TAG, "", _ee); }
                  System.out.println("Wrote " + writeFile + " at " + offset);
               }
               catch (Exception _e)
               {
                  Log.e(TAG, "Error writing " + writeFile + " to offset " + offset, _e);
               }
               finally
               {
                  if (readChannel != null)
                     try { readChannel.close(); } catch (Exception _e) { Log.e(TAG, "", _e); }
                  if (fis != null)
                     try { fis.close(); } catch (Exception _e) { Log.e(TAG, "", _e); }
               }
            }
         }
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
      }
      finally
      {
         if (framesRAF != null)
            try { framesRAF.close(); } catch (Exception _e) { Log.e(TAG, "", _e); }
      }
     return true;
   }

   private float checkBearingDiscrepencies(float newBearing, Set<Long> completedBearings, ProgressParam progress)
   //------------------------------------------------------------------------------------------------------------
   {
      final String message = "Large number of bearing discrepancies. Try to recalibrate by waving device "
            + "in a figure of 8 motion. ";
      discrepancies = 0;
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
            bearingCorrection = 0;
            bearingCorrectionTime = Long.MAX_VALUE;
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
            offset = nextOffset(nextBearing, completedBearings);
            if (offset >= 0)
            {
               recordingLastBearing = recordingNextBearing;
               recordingNextBearing = offset * recordingIncrement - bearingCorrection;
               if (recordingNextBearing < 0)
                  recordingNextBearing += 360;
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
            bearingCorrection = 0;
            bearingCorrectionTime = Long.MAX_VALUE;
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

   private File writeMatchFrames(ProgressParam progress) throws IOException
   //-----------------------------------------------------
   {
      float lastUIBearing = -1;
      matchFramesThread = new FrameWriterThread(renderer, previewer);
      matchFramesExecutor = Executors.newSingleThreadExecutor();
      matchFrameFuture = matchFramesExecutor.submit(matchFramesThread);
      boolean isRecording = false;
      Set<Long> writtenBearings = new HashSet<>();
      String name = (renderer.recordFileName == null) ? "Unknown" : renderer.recordFileName;
      recordTimestampsFile = new File(renderer.recordDir, name + ".bearings");
      Set<Long> completedBearings = new HashSet<>(this.completedBearings);
      PrintWriter timestampWriter = new PrintWriter(new BufferedWriter(new FileWriter(recordTimestampsFile), 32768));
      long count = frameWriterThread.count;
      int discrepancyCount = 0;
      while ( (count < no) && (renderer.isRecording) && (! isCancelled()) )
      {
         try
         {
            bearingInfo = bearingBuffer.peekHead(); // Get latest bearing
            if (bearingInfo != null)                // if possible
            {
               float newBearing = bearingInfo.bearing - bearingCorrection;
               if (newBearing < 0)
                  newBearing += 360;
               if (lastBearing < 0)
                  lastBearing = newBearing;
               diff = newBearing - lastBearing;
               if (diff >= 8)
               {
                  if (discrepancyCount++ > 3)
                  {
                     newBearing = checkBearingDiscrepencies(newBearing, completedBearings, progress);
                     discrepancyCount = 0;
                  }
                  else
                     continue;
               }
               else
                  discrepancyCount = 0;
               if (newBearing == Float.MIN_VALUE)
                  break;
               discrepancies = 0;
               lastBearing = bearing;
               bearing = newBearing;
               if ( (lastBearing > 350) && (bearing < 10) )
                  diff = bearing + (360 - lastBearing);
               else if ( (bearing > 350) && (lastBearing < 10) )
                  diff = -(lastBearing + (360 - bearing));
               else
                  diff = bearing - lastBearing;

               if ( (lastBearing >= 0) && (Math.abs(diff) > minBearingCorrectionDelta) )
               {
                  bearingCorrection = diff;
                  if (bearingCorrectionTime == Long.MAX_VALUE)
                     bearingCorrectionTime = bearingInfo.timestamp;
   //                  Log.i(TAG, "Set bearing correction " + bearingCorrection + " " + bearing + " - " + lastBearing + " = " + diff);
                  //TODO: Currently just reverts to the previous bearing; perhaps subtract moving average of differences
                  bearing = lastBearing; // -= bearingCorrection;
               }
            }
            else
            {
               bearingCondVar.close();
               bearingCondVar.block(20);
               continue;
            }

            final float dist = distance(bearing, recordingNextBearing);
            long offset = (long) (Math.floor(bearing / recordingIncrement));
            long off = (long) (Math.floor(recordingNextBearing / recordingIncrement));
            if ( (! completedBearings.contains(off)) && (Math.abs(dist) <= recordingIncrement) )
            {
               skippedBearings.add(recordingNextBearing);
               Log.i(TAG, "Skipped " + bearing + " " + recordingNextBearing + " " + dist + " " + recordingIncrement);
               completedBearings.add(off); //local only
               if (! writtenBearings.contains(off))
               {
                  timestampWriter.printf("%d=%d\n", off, bearingInfo.timestamp);
                  writtenBearings.add(off);
               }
               if (++count >= no)
                  break;
            }
            if (dist <= 0)
            {
               float nextBearing = ((++offset) * recordingIncrement) % 360;
               offset = nextOffset(nextBearing, completedBearings);
               if (offset < 0)
                  break;
               recordingLastBearing = recordingNextBearing;
               recordingNextBearing = offset * recordingIncrement;
               if (recordingNextBearing < 0)
                  recordingNextBearing += 360;
            }
            if (Math.abs(dist) <= 10)
            {
               if (! isRecording)
               {
                  matchFramesThread.on();
                  isRecording = true;
               }
               if (! writtenBearings.contains(offset))
               {
                  timestampWriter.printf("%d=%d\n", off, bearingInfo.timestamp);
                  writtenBearings.add(off);
               }
               renderer.recordColor = GLRecorderRenderer.PURPLE;
            }
            else
            {
               if (isRecording)
               {
                  matchFramesThread.off();
                  isRecording = false;
               }
               renderer.recordColor = GLRecorderRenderer.BLUE;
            }

            if (Math.abs(bearing - lastUIBearing) >= recordingIncrement)
            {
               lastUIBearing = bearing;
               progress.set(bearing, recordingNextBearing, renderer.recordColor,
                            (int) ((count * 100) / no));
               publishProgress(progress);
               renderer.requestRender();
            }
         }
         catch (Exception e)
         {
            Log.e(TAG, "", e);
            continue;
         }
      }
      if (timestampWriter != null)
         try { timestampWriter.close(); } catch (Exception _e) {}
      matchFramesThread.off();
      matchFramesThread.mustStop = true;
      matchFramesThread.onFrameAvailable(null, -1);
      try { Thread.sleep(100); } catch (Exception e) {}
      boolean isTerminated = false;
      matchFramesExecutor.shutdown();
      try { isTerminated = matchFramesExecutor.awaitTermination(500, TimeUnit.MILLISECONDS); } catch (Exception e) { isTerminated = false; }
      if (! isTerminated)
         matchFramesExecutor.shutdownNow();
      return matchFramesThread.getDir();
   }
}
