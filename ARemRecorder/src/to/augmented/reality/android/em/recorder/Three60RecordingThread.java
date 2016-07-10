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
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;
import to.augmented.reality.android.common.sensor.orientation.OrientationProvider;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class Three60RecordingThread extends RecordingThread
//=========================================================
{
   static final private String TAG = Three60RecordingThread.class.getSimpleName();

   static final private int MAX_KLUDGES = 20;

   static final private boolean TEST_POST_PROCESS = false;

   private float increment = 1.0f;

   protected Three60RecordingThread(GLRecorderRenderer renderer, Previewable previewer, File recordDir, float increment,
                                    long maxSize, ConditionVariable frameAvailCondVar, OrientationHandler orientationHandler,
                                    LocationHandler locationHandler, boolean isDebug)
   //--------------------------------------------------------------------------------------------------------
   {
      super(renderer, previewer, recordDir, maxSize, frameAvailCondVar, orientationHandler, locationHandler,
            isDebug);
      this.increment = Math.max(increment, 1f);
   }

   @Override
   protected Boolean doInBackground(Void... params)
   //--------------------------------------------
   {
      if (orientationHandler == null)
      {
         Log.e(TAG, "Three60RecordingThread: Orientation handler null");
         return false;
      }
      if ( (recordingDir == null) || (! recordingDir.isDirectory()) || (! recordingDir.canWrite()) )
      {
         Log.e(TAG, "Three60RecordingThread: Recording directory " +
               ((recordingDir == null) ? "null" : recordingDir.getAbsolutePath()) + " invalid");
         return false;
      }
      File headerFile = new File(recordingDir, recordingDir.getName() + ".head");
      PrintWriter headerWriter = null;
      boolean isCreated = false;
      float startBearing = -1, currentBearing;
      final long timestamp = SystemClock.elapsedRealtimeNanos();
      if (TEST_POST_PROCESS)
         try { return testPostProcess(headerFile); } catch (Exception e) { Log.e(TAG, "", e); return false; }

      ProgressParam progress = new ProgressParam();
      PowerManager powerManager = (PowerManager) renderer.activity.getSystemService(Context.POWER_SERVICE);
      PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakelock");
      wakeLock.acquire();
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
      Bufferable previewBuffer = (Bufferable) previewer;
      try
      {
         renderer.recordColor = GLRecorderRenderer.GREEN;
         renderer.isPause = true;
         renderer.requestRender();

         orientationHandler.bufferClear();
         orientationHandler.startTimestamp(timestamp);
         orientationHandler.bufferOn();
         if (locationHandler != null)
         {
            locationHandler.bufferClear();
            locationHandler.startTimestamp(timestamp);
            locationHandler.bufferOn();
         }

         long then = System.currentTimeMillis();
         long now = System.currentTimeMillis();
         ConditionVariable orientationCond = orientationHandler.getConditionVariable();
         int matches = 0, nonmatches = 0;
         progress.setStatus("Synchronizing start bearing", 0, true, 0);
         publishProgress(progress);
         while ((now - then) < 3000)
         {
            orientationCond.block();
            if (startBearing < 0)
               startBearing = (float) Math.floor(orientationHandler.getLastBearing());
            else
            {
               currentBearing = (float) Math.floor(orientationHandler.getLastBearing());
               if ( (currentBearing == startBearing) && (matches++ > 2) )
               {
                  renderer.isPause = false;
                  renderer.requestRender();
                  Log.i(TAG, "Start Bearing: " + startBearing);
                  break;
               }
               else if ( (currentBearing != startBearing) && (nonmatches++ > 2) )
               {
                  matches = nonmatches = 0;
                  startBearing = currentBearing;
               }
            }
            now = System.currentTimeMillis();
            progress.setStatus("Synchronizing start bearing", (matches*100)/3, true, 0);
            publishProgress(progress);
         }

         recordingFile = new File(recordingDir, "frames.RAW");
         previewBuffer.bufferClear();
         previewBuffer.startTimestamp(timestamp);
         previewBuffer.writeFile(recordingFile);
         previewBuffer.bufferOn();
         previewBuffer.writeOn();

         long startTime = SystemClock.elapsedRealtimeNanos() - timestamp;
         currentBearing = startBearing + 2;
         if (currentBearing >= 360)
            currentBearing -= 360;
         startBearing = currentBearing;
         orientationHandler.writeOn();
         float previousBearing = startBearing, startBearingFloor = (float) Math.floor(startBearing),
               nextBearingFloor = (float) Math.floor(startBearing) + 1.0f;
         if (locationHandler != null)
            locationHandler.writeOn();

         int secs = 0;
         then = System.currentTimeMillis();
         progress.setStatus("Recording", 0, false, 0);
         publishProgress(progress);
         renderer.recordColor = GLRecorderRenderer.GREEN;
         renderer.isPause = false;
         renderer.requestRender();
         int n = 0;
         boolean isStarted = false;
         while (renderer.isRecording)
         {
            if (orientationCond.block(100))
            {
               float newBearing = orientationHandler.getLastBearing();
               if (closeDistance(previousBearing, newBearing) < 0)
                  renderer.recordColor = GLRecorderRenderer.RED;
               else
               {
                  renderer.recordColor = GLRecorderRenderer.GREEN;
                  previousBearing = currentBearing;
                  currentBearing = newBearing;
                  float currentBearingFloor = (float) Math.floor(currentBearing);
                  if ( (! isStarted) && (currentBearingFloor != startBearingFloor) )
                     isStarted = true;
                  if ( (isStarted) && (secs > 7) && (currentBearingFloor == startBearingFloor) )
                     break;
                  if ( (currentBearingFloor == nextBearingFloor) ||
                        ( (currentBearingFloor > nextBearingFloor) && (! isWrap(currentBearingFloor, nextBearingFloor)))
                     )
                  {
                     float inc = (currentBearingFloor - nextBearingFloor) + 1.0f;
                     n += (int) inc;
//                     Log.i(TAG, "Progress 1: " + currentBearingFloor + " " + nextBearingFloor + " n = " + n + " inc "  + inc + " " + ((n * 100) / 360));
                     nextBearingFloor = (float) Math.floor(addBearing(nextBearingFloor, inc));
                     progress.set(currentBearingFloor, nextBearingFloor, renderer.recordColor, (n * 100) / 360);
                     publishProgress(progress);
//                     Log.i(TAG, "Progress 2: " + currentBearingFloor + " " + nextBearingFloor + " n = " + n + " inc "  + inc + " " + ((n * 100) / 360));
                  }
//                  else
//                     Log.i(TAG, "Non-Progress: " + currentBearingFloor + " "  + nextBearingFloor);
               }
            }

            now = System.currentTimeMillis();
            if ((now - then) > 1000)
            {
               secs++;
               then = now;
            }
            if ( (secs % 5) == 0)
            {
//               previewBuffer.flushFile();
               if (previewBuffer.writeSize() > maxFrameFileSize)
               {
                  Log.w(TAG, "Exiting due to file size exceeding " + maxFrameFileSize + " bytes");
                  renderer.toast("Exiting due to file size exceeding " + maxFrameFileSize + " bytes");
                  break;
               }
            }
            if (isPaused)
            {
               if (! pauseHandler(previewBuffer, orientationHandler))
                  break;
               then = System.currentTimeMillis();
            }
         }
         long endTime = SystemClock.elapsedRealtimeNanos() - timestamp;

         renderer.recordColor = GLRecorderRenderer.RED;
         renderer.isPause = true;
         renderer.requestRender();
         activity.hideExit();
         now = then = System.currentTimeMillis();
         while ((then - now) < 1500) // Record a few more frames
         {
            Thread.sleep(100);
            then = System.currentTimeMillis();
         }
         renderer.recordColor = GLRecorderRenderer.GREEN;
         renderer.isPause = true;
         renderer.requestRender();

         Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
         previewBuffer.bufferOff();
         orientationHandler.bufferOff();

         previewBuffer.stop();
         previewBuffer.closeFile();

         orientationHandler.stop();
         orientationHandler.closeFile();
         if (locationHandler != null)
         {
            locationHandler.stop();
            locationHandler.closeFile();
         }
         previewer.suspendPreview();
         if (headerFile.exists())
            headerFile.delete();
         headerWriter = new PrintWriter(headerFile);
         headerWriter.println(String.format(Locale.US, "StartBearing=%.1f", startBearing));
         headerWriter.println(String.format(Locale.US, "StartTime=%d", startTime));
         headerWriter.println(String.format(Locale.US, "EndTime=%d", endTime));
         headerWriter.println(String.format(Locale.US, "No=%d", n));
         headerWriter.println(String.format(Locale.US, "BufferSize=%d", renderer.rgbaBufferSize));
         headerWriter.println(String.format(Locale.US, "PreviewWidth=%d", renderer.previewWidth));
         headerWriter.println(String.format(Locale.US, "PreviewHeight=%d", renderer.previewHeight));
         headerWriter.println(String.format(Locale.US, "FocalLength=%.6f", previewer.getFocalLen()));
         headerWriter.println("FileFormat=RGBA");
         headerWriter.println("Type=THREE60");
         OrientationProvider.ORIENTATION_PROVIDER orientationProviderType = orientationHandler.getType();
         headerWriter.println(String.format(Locale.US, "OrientationProvider=%s",
                                            (orientationProviderType == null) ? OrientationProvider.ORIENTATION_PROVIDER.DEFAULT.name()
                                                                              : orientationProviderType.name()));
         headerWriter.flush();

         File orientationFile = orientationHandler.writeFile();
         int orientationCount = orientationHandler.writeCount();
         orientationCount = orientationFilter(recordingDir, orientationFile, startBearing, progress, orientationCount,
                                              true, 8, isDebug);
         File smoothOrientationFile = new File(recordingDir, orientationFile.getName() + ".smooth");
         if ( (orientationCount >= 0) && (smoothOrientationFile.exists()) && (smoothOrientationFile.length() > 0) )
         {
            if (! isDebug)
               orientationFile.delete();
            orientationFile = smoothOrientationFile;
         }

         Bufferable orientationBuffer = (Bufferable) orientationHandler;
         previewBuffer.openForReading();
         Bufferable rgbaBuffer = convertFrames(recordingDir, previewBuffer, previewer, progress, true, isDebug);
         if (rgbaBuffer == null)
         {
            Log.e(TAG, "Error converting frames");
            return false;
         }

         if (! isDebug)
         {
            File f = new File(recordingDir, "frames.RAW");
            f.delete();
         }
         orientationBuffer.openForReading(orientationFile);
         rgbaBuffer.openForReading();
         float recordingIncrement = -1f;
         float[] recordingIncrements = { 1f, 1.5f, 2.0f, 2.5f, 3.0f };
         Pair<Integer, Integer>[] incrementResults = new Pair[recordingIncrements.length];
         for (int j=0; j<recordingIncrements.length; j++)
            incrementResults[j] = new Pair<>(Integer.MAX_VALUE, Integer.MAX_VALUE);
         int[] kludgeCount = new int[1];
         for (int i=0; i<recordingIncrements.length; i++)
         {
            try
            {
               float increment = recordingIncrements[i];
               if (increment < this.increment)
                  continue;;
//               int no = (int) (Math.floor(360.0f / increment));
               int no = (int) (Math.floor(((float) (n + 1)) / increment));
               logWriter.println(); logWriter.println("increment " + Float.toString(increment) + " n = " + n +
                                                      " no = " + no);
               File ff = createThree60(startBearing, rgbaBuffer, orientationBuffer, increment, no, orientationCount,
                                       progress, kludgeCount);
               if (ff != null)
               {
                  logWriter.println("increment " + Float.toString(increment) + " kludges " + kludgeCount[0]);
                  incrementResults[i] = new Pair<>(i, kludgeCount[0]);
                  if (kludgeCount[0] == 0)
                    break;
               }
               else
                  logWriter.println("increment " + Float.toString(increment) + " error");
               if (isCancelled())
                  break;
               rgbaBuffer.readPos(0L);
               orientationBuffer.closeFile();
               orientationBuffer.openForReading(orientationFile);
            }
            catch (Exception ee)
            {
               Log.e(TAG, "", ee);
            }
         }
         File framesFile = null;
         if (incrementResults[0].second == 0)
         {
            recordingIncrement = recordingIncrements[incrementResults[0].first];
            framesFile = new File(recordingDir, String.format(Locale.US, "%s.frames.%.1f", recordingDir.getName(),
                                                              recordingIncrement));
         }

         if (framesFile == null)
         {
            boolean hasResult = false;
            for (int i=0; i<incrementResults.length; i++)
            {
               if (incrementResults[0].first < Integer.MAX_VALUE)
               {
                  hasResult = true;
                  break;
               }
            }
            if (! hasResult)
            {
               renderer.toast("No valid result");
               return false;
            }
            Arrays.sort(incrementResults, new Comparator<Pair<Integer, Integer>>()
            {
               @Override public int compare(Pair<Integer, Integer> lhs, Pair<Integer, Integer> rhs)
               //------------------------------------------------------------------------------
               {
                  int kludgeResult = lhs.second.compareTo(rhs.second);
                  return (kludgeResult == 0) ? lhs.first.compareTo(rhs.first) : kludgeResult;
               }
            });
            try { headerWriter.close(); } catch (Exception _ee) {}
            activity.selectResult(recordingDir, recordingIncrements, incrementResults);
//            if (incrementResults[0].first < Integer.MAX_VALUE)
//            {
//               recordingIncrement = recordingIncrements[incrementResults[0].first];
//               framesFile = new File(recordingDir, String.format(Locale.US, "%s.frames.%.1f", recordingDir.getName(),
//                                                                 recordingIncrement));
//            }
         }
         else
         {
            File fn = new File(recordingDir, recordingDir.getName() + ".frames");
            framesFile.renameTo(fn);
            framesFile = fn;
            headerWriter.println(String.format(Locale.US, "FramesFile=%s", framesFile.getAbsolutePath()));
            headerWriter.println(String.format(Locale.US, "Increment=%6.1f", recordingIncrement));
            isCreated = true;
            progress.setStatus("Created Recording " + framesFile.getName(), 100, true, Toast.LENGTH_LONG);
            publishProgress(progress);
         }
         if (! isDebug)
         {
            File f = new File(recordingDir, "frames.RGBA");
            f.delete();
//            for (int i=0; i<recordingIncrements.length; i++)
//            {
//               float increment = recordingIncrements[i];
//               f = new File(recordingDir, String.format(Locale.US, "%s.frames.%.1f", recordingDir.getName(), increment));
//               f.delete();
//            }
         }
         previewer.setFlash(false);
         previewer.restartPreview();
         return (framesFile != null);
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         logException("", e);
         if (previewBuffer != null)
         {
            previewBuffer.bufferOff();
            previewBuffer.writeOff();
            previewBuffer.stop();
            try { previewBuffer.closeFile(); } catch (Exception ee) { Log.e(TAG, "", ee); }
         }

         if (orientationHandler != null)
         {
            orientationHandler.bufferOff();
            orientationHandler.writeOff();
            orientationHandler.stop();
            try { orientationHandler.closeFile(); } catch (Exception ee) { Log.e(TAG, "", ee); }
         }
         if (locationHandler != null)
         {
            locationHandler.stop();
            try { locationHandler.closeFile(); } catch (Exception ee) { Log.e(TAG, "", ee); }
         }
         return false;
      }
      finally
      {
         wakeLock.release();
         if (headerWriter != null)
            try { headerWriter.close(); } catch (Exception e) { Log.e(TAG, headerFile.getAbsolutePath(), e); }
         if (! isDebug)
         {
            File f = new File(recordingDir, "frames.RAW");
            f.delete();
            f = new File(recordingDir, "frames.RGBA");
            f.delete();
            f = new File(recordingDir, "framestamps.txt");
            f.delete();
            f = new File(recordingDir, "orientation");
            f.delete();
            f = new File(recordingDir, "orientation.smooth");
            f.delete();
            f = new File(recordingDir, "bearingstamps.txt");
            f.delete();
         }
         if (logWriter != System.out)
            try { logWriter.close(); } catch (Exception e) {}
      }
   }

   protected File createThree60(float startBearing, Bufferable framesBuffer, Bufferable orientationBuffer,
                                float recordingIncrement, int no, int orientationCount, ProgressParam progress,
                                int[] kludgeCount)
         throws IOException
   //------------------------------------------------------------------------------------------------------------
   {
      if (progress != null)
      {
         progress.setStatus("Creating output (" + recordingIncrement + ")", 0, false, 0);
         publishProgress(progress);
      }
      File framesFile = new File(recordingDir, String.format(Locale.US, "%s.frames.%.1f", recordingDir.getName(),
                                           recordingIncrement));
      kludgeCount[0] = 0;
      RandomAccessFile raf = new RandomAccessFile(framesFile, "rw");
      int n = 0, N = 0;
      Bufferable.BufferData orientationBufData = orientationBuffer.read();
      float currentBearing = startBearing;
      long currentOffset = (long) (Math.floor(startBearing / recordingIncrement));
      long startOffset = currentOffset;
      float stopBearing = startBearing - increment;
      if (stopBearing < 0)
         stopBearing += 360;
      long stopOffset = (long) (Math.floor(stopBearing / recordingIncrement));
      List<OrientationData> m = new ArrayList<>();
      byte[] frameContents = new byte[renderer.rgbaBufferSize], kludgedContents = new byte[renderer.rgbaBufferSize];
      while (orientationBufData != null)
      {
         OrientationData orientationData = (OrientationData) orientationBufData.data;
         float bearing = orientationData.bearing();
         if (bearing >= startBearing)
            break;
         orientationBufData = orientationBuffer.read();
      }
//      final long interval = 2000000000L;
      long nextTime, frameno = 0, matchFrameno = -1, lastFrameno =-1;
      ByteBuffer frame = null, matchFrame = null;
      long lastValidOffset = 0;
      int kludgeTranslate = 4;
      try
      {
         while (orientationBufData != null)
         {
            N++;
            if ( (progress != null) && ((N % 5) == 0) )
            {
               progress.setStatus("Creating output (" + recordingIncrement + ")", (N*100) / orientationCount, false, 0);
               publishProgress(progress);
            }
            OrientationData orientationData = (OrientationData) orientationBufData.data;
            float bearing = orientationData.bearing();
            long bearingOffset = (long) (Math.floor(bearing / recordingIncrement));
            m.clear();
            if (bearingOffset == 147)
            {
               int a = 1;
               Log.i(TAG, "offset 147");
            }
            while ( (orientationBufData != null) && (bearingOffset == currentOffset) )
            {
               m.add(orientationData);
               orientationBufData = orientationBuffer.read();
               if (orientationBufData != null)
               {
                  orientationData = (OrientationData) orientationBufData.data;
                  bearing = orientationData.bearing();
                  bearingOffset = (long) (Math.floor(bearing / recordingIncrement));
               }
            }

            nextTime = orientationData.timestamp + 300000000L;
            Bufferable.BufferData frameBufData = framesBuffer.read();
            OrientationData orientationMatch = null;
            Bufferable.BufferData frameMatch = null;
            long offset = -1;
            long minmatch = Long.MAX_VALUE;
            while ( (frameBufData != null) && (frameBufData.timestamp < nextTime) )
            {
               long frameTimestamp = frameBufData.timestamp;
               if (frameTimestamp < 0)
               {
                  frameBufData = framesBuffer.read();
                  continue;
               }

               if (frameBufData.data != null)
               {
                  frame = (ByteBuffer) frameBufData.data;
                  frameno++;
               }
//               else
//                  isDuplicateFrame = true;
               for (OrientationData od : m)
               {
                  long timediff = Math.abs(od.timestamp - frameTimestamp);
                  if ( (timediff < minmatch) && (frameno > lastFrameno) )
                  {
                     minmatch = timediff;
                     orientationMatch = od;
                     frameMatch = frameBufData;
                     matchFrame = frame;
                     matchFrameno = frameno;
                     offset = frameBufData.fileOffset;
                  }
               }
               frameBufData = framesBuffer.read();
            }

            if ( (bearing >= 111) && (bearing <= 115) )
            {
               int a = 1;
               int c = a + 2;
            }

            if ( (orientationMatch != null) && (minmatch < 300000000L) )
            {
               raf.seek(currentOffset * renderer.rgbaBufferSize);
               matchFrame.rewind();
               lastFrameno = matchFrameno;
               matchFrame.get(frameContents);
               raf.write(frameContents);
               Log.i(TAG, Integer.toString(n) + ": Wrote offset " + currentOffset + " bearing " + currentBearing +
                     " using bearing timestamp " + orientationMatch.timestamp +" frame timestamp " + frameMatch.timestamp);
               logWriter.println(Integer.toString(n) + ": Wrote offset " + currentOffset + " bearing " + currentBearing +
                                 " using bearing timestamp " + orientationMatch.timestamp +" frame timestamp " +
                                 frameMatch.timestamp);

//               boolean SAVE_FRAME = false;
//               if (SAVE_FRAME)
//               {
//                  Mat M = new Mat(renderer.previewHeight, renderer.previewWidth, CvType.CV_8UC4);
//                  M.put(0, 0, frameContents);
//                  Imgcodecs.imwrite("/storage/emulated/0/Documents/ARRecorder/2/frame.png", M);
//               }

               n++;
               framesBuffer.readPos(offset);
               lastValidOffset = offset;
               kludgeTranslate = 4;
            }
            else
            {
               kludgeCount[0]++;
               if (kludgeCount[0] > MAX_KLUDGES)
                  return null;
               CV.KLUDGE_RGBA(renderer.previewWidth, renderer.previewHeight, frameContents, kludgeTranslate, true,
                              kludgedContents);
               kludgeTranslate += 4;
               raf.seek(currentOffset * renderer.rgbaBufferSize);
               raf.write(kludgedContents);
               Log.i(TAG, "******** " + n + ": Kludged offset" + currentOffset + " bearing " + currentBearing);
               logWriter.println("******** " + n + ": Kludged offset " + currentOffset + " bearing " + currentBearing);
               n++;
               framesBuffer.readPos(lastValidOffset);
//               boolean SAVE_FRAME = false;
//               if (SAVE_FRAME)
//               {
//                  Mat M = new Mat(renderer.previewHeight, renderer.previewWidth, CvType.CV_8UC4);
//                  M.put(0, 0, kludgedContents);
//                  Imgcodecs.imwrite("/storage/emulated/0/Documents/ARRecorder/frame.png", M);
//               }
            }
            currentOffset = bearingOffset;
            currentBearing = bearing;
            m.clear();
         }
         if (progress != null)
         {
            progress.setStatus("Creating output (" + recordingIncrement + ")", 100, false, 0);
            publishProgress(progress);
         }
         if ( (n >= no) && (currentOffset == stopOffset) )
            return framesFile;
         //return (n >= no) ? framesFile : null;
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         return null;
      }
      return framesFile;
   }

   private Boolean testPostProcess(File headerFile) throws IOException
   //-----------------------------------------------------------------
   {
      if (TEST_POST_PROCESS) // JIT can remove if false (pseudo #ifdef)
      {
         try
         {
            renderer.toast("Debugging in testPostProcess");
            final boolean isCreateOrient = false, isCreateRGBA = false;
            Bufferable previewBuffer = (Bufferable) previewer;
            //      previewBuffer.writeFile(new File("/sdcard/Documents/ARRecorder/t/moreframes"));
            //      previewBuffer.bufferOn();
            //      previewBuffer.writeOn();

            boolean isCreated = false;
            float startBearing = -1;
            int n = 0;
            if (headerFile.exists())
            {
               try (BufferedReader br = new BufferedReader(new FileReader(headerFile)))
               {
                  String line = br.readLine();
                  while (line != null)
                  {
                     String[] as = line.split("=");
                     if ((as.length > 1) && (as[0].trim().equalsIgnoreCase("StartBearing")))
                        startBearing = Float.parseFloat(as[1].trim());
                     if ((as.length > 1) && (as[0].trim().equalsIgnoreCase("No")))
                        n = Integer.parseInt(as[1].trim());
                     line = br.readLine();
                  }
               }
            }
            Bufferable orientationBuffer = (Bufferable) orientationHandler;
            File orientationFile = new File(recordingDir, "orientation");

            if (startBearing < 0)
            {
               try (DataInputStream dis = new DataInputStream(
                     new BufferedInputStream(new FileInputStream(orientationFile))))
               {
                  long[] recordLen = new long[1];
                  OrientationData data = OrientationData.read(dis, recordLen);
                  if ((startBearing < 0) && (data != null))
                     startBearing = data.bearing();
               }
               catch (Exception E)
               {
                  logException("", E);
                  Log.e(TAG, "", E);
               }
            }
            if (isCreateOrient)
            {
               int ocount = orientationFilter(recordingDir, orientationFile, startBearing, null, 0, true, 8, true);
               if (ocount >= 0)
                  orientationFile = new File(recordingDir, orientationFile.getName() + ".smooth");
            }
            else
            {
               orientationFile = new File(recordingDir, orientationFile.getName() + ".smooth");
               if ( (! orientationFile.exists()) || (orientationFile.length() == 0) )
                  throw new RuntimeException("Invalid orientation file " + orientationFile.getAbsolutePath());
            }

            Bufferable rgbaBuffer;
            if (isCreateRGBA)
            {
               File rf = new File(recordingDir, "frames.RAW");
               if (!previewBuffer.openForReading(rf))
                  throw new RuntimeException("Could not open file " + rf);
               rgbaBuffer = convertFrames(recordingDir, previewBuffer, previewer, null, true, true);
               rgbaBuffer.openForReading();
            }
            else
            {
               rgbaBuffer = new NativeFrameBuffer(3, renderer.rgbaBufferSize, false);
               rgbaBuffer.startTimestamp(0);
               File rf = new File(recordingDir, "frames.RGBA");
               if (! rf.exists())
                  throw new RuntimeException(rf.getAbsolutePath());
               rgbaBuffer.openForReading(rf);
            }

            //      Bufferable.BufferData frameBufData;
            //      while ( (frameBufData = rgbaBuffer.read()) != null)
            //      {
            //         long ts2 = frameBufData.timestamp;
            //         Log.i(TAG, "ts == " + ts2);
            //      }

            orientationBuffer.openForReading(orientationFile);
            float recordingIncrement = -1f;
            float[] recordingIncrements = { 1f, 1.5f, 2.0f, 2.5f, 3.0f };
            Pair<Integer, Integer>[] incrementResults = new Pair[recordingIncrements.length];
            for (int j = 0; j < recordingIncrements.length; j++)
               incrementResults[j] = new Pair<>(Integer.MAX_VALUE, Integer.MAX_VALUE);
            int[] kludgeCount = new int[1];

            for (int i = 0; i < recordingIncrements.length; i++)
            {
               try
               {
                  float increment = recordingIncrements[i];
                  if (increment < this.increment)
                     continue;;
   //               int no = (int) (Math.floor(360.0 / increment));
                  int no = (int) (Math.floor(((float) (n + 1)) / increment));
                  logWriter.println(); logWriter.println("increment " + Float.toString(increment) + " n = " + n +
                                                         " no = " + no);
                  File ff = createThree60(startBearing, rgbaBuffer, orientationBuffer, increment, no, 0, null,
                                          kludgeCount);
                  if (ff != null)
                  {
                     logWriter.println("increment " + Float.toString(increment) + " kludges " + kludgeCount[0]);
                     incrementResults[i] = new Pair<>(i, kludgeCount[0]);
                     if (kludgeCount[0] == 0)
                        break;
                  }
                  else
                     logWriter.println("increment " + Float.toString(increment) + " error");
                  if (isCancelled())
                     break;
                  rgbaBuffer.readPos(0L);
                  orientationBuffer.closeFile();
                  orientationBuffer.openForReading(orientationFile);
               }
               catch (Exception ee)
               {
                  logException("increment " + Float.toString(increment), ee);
                  Log.e(TAG, "", ee);
               }
            }
            PrintWriter headerWriter = null;
            try
            {
               headerWriter = new PrintWriter(new FileWriter(headerFile, true));
            }
            catch (Exception e)
            {
               renderer.toast("Error opening header file " + headerFile.getAbsolutePath() + " for appending");
               headerWriter = null;
            }
            File framesFile = null;
            if (incrementResults[0].second == 0)
            {
               recordingIncrement = recordingIncrements[incrementResults[0].first];
               framesFile = new File(recordingDir, String.format(Locale.US, "%s.frames.%.1f", recordingDir.getName(),
                                                                 recordingIncrement));
            }

            if (framesFile == null)
            {
               boolean hasResult = false;
               for (int i=0; i<incrementResults.length; i++)
               {
                  if (incrementResults[0].first < Integer.MAX_VALUE)
                  {
                     hasResult = true;
                     break;
                  }
               }
               if (! hasResult)
               {
                  renderer.toast("No valid result");
                  return false;
               }
               Arrays.sort(incrementResults, new Comparator<Pair<Integer, Integer>>()
               {
                  @Override public int compare(Pair<Integer, Integer> lhs, Pair<Integer, Integer> rhs)
                  //------------------------------------------------------------------------------
                  {
                     int kludgeResult = lhs.second.compareTo(rhs.second);
                     return (kludgeResult == 0) ? lhs.first.compareTo(rhs.first) : kludgeResult;
                  }
               });
               try { headerWriter.close(); } catch (Exception _ee) {}
               activity.selectResult(recordingDir, recordingIncrements, incrementResults);
//            if (incrementResults[0].first < Integer.MAX_VALUE)
//            {
//               recordingIncrement = recordingIncrements[incrementResults[0].first];
//               framesFile = new File(recordingDir, String.format(Locale.US, "%s.frames.%.1f", recordingDir.getName(),
//                                                                 recordingIncrement));
//            }
            }
            else
            {
               File fn = new File(recordingDir, recordingDir.getName() + ".frames");
               framesFile.renameTo(fn);
               framesFile = fn;
               headerWriter.println(String.format(Locale.US, "FramesFile=%s", framesFile.getAbsolutePath()));
               headerWriter.println(String.format(Locale.US, "Increment=%6.1f", recordingIncrement));
               isCreated = true;
            }
            return isCreated;
         }
         catch (Exception e)
         {
            logException("", e);
            Log.e(TAG, "", e);
            return false;
         }
         finally
         {
            if (logWriter != System.out)
               try { logWriter.close(); } catch (Exception e) {}
         }
      }
      else
         return false;
   }
}
