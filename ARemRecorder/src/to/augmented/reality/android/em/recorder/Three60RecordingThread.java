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
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
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

   private float startIncrement = 1.0f, endIncrement = 3.0f;

   private boolean mustStitch = false;

   //Testing the post-processing phase. See also RecorderActivity.NO_OVERWRITE_CHECK to
   // prevent the debug files being overwritten.
   static final public boolean TEST_POST_PROCESS = false;
   static final boolean IS_CREATE_ORIENT = false; // When in TEST_POST_PROCESS == true means create smoothed orientation file, false = assume it already exists
   static final boolean IS_CREATE_RGBA = false; // When in TEST_POST_PROCESS == true means create RGBA file from RAW, false = assume it already exists


   protected Three60RecordingThread(GLRecorderRenderer renderer, Previewable previewer, File recordDir, float startIncrement,
                                    long maxSize, ConditionVariable frameAvailCondVar, OrientationHandler orientationHandler,
                                    LocationHandler locationHandler, boolean isStitch, boolean isPostProcess)
   //--------------------------------------------------------------------------------------------------------
   {
      super(renderer, previewer, recordDir, maxSize, frameAvailCondVar, orientationHandler, locationHandler,
            isPostProcess);
      mustStitch = isStitch;
      //this.startIncrement = Math.max(startIncrement, 1f);
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
      final long timestamp = SystemClock.elapsedRealtimeNanos();  // Subtracted from the reading timestamps
      if (TEST_POST_PROCESS)
         try { return testPostProcess(headerFile); } catch (Exception e) { Log.e(TAG, "", e); return false; }

      ProgressParam progress = new ProgressParam();
      PowerManager powerManager = (PowerManager) renderer.activity.getSystemService(Context.POWER_SERVICE);
      PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                                                                "ARemRecorder:wakelock");
      wakeLock.acquire();
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
      Bufferable rawBuffer = (Bufferable) previewer;
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
         rawBuffer.bufferClear();
         recordingFile = new File(recordingDir, "frames.RAW");
         rawBuffer.startTimestamp(timestamp);
         rawBuffer.writeFile(recordingFile);
         rawBuffer.bufferOn();
         rawBuffer.writeOn();

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

         final long startTimestamp = SystemClock.elapsedRealtimeNanos();
         progress.setStatus("Synchronizing start bearing", 100, true, 0);
         publishProgress(progress);
         long startTime = startTimestamp - timestamp;
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
         progress.setStatus("Recording", 0, true, 0);
         publishProgress(progress);
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
               if (rawBuffer.writeSize() > maxFrameFileSize)
               {
                  Log.w(TAG, "Exiting due to file size exceeding " + maxFrameFileSize + " bytes");
                  renderer.toast("Exiting due to file size exceeding " + maxFrameFileSize + " bytes");
                  break;
               }
            }
            if (isPaused)
            {
               if (! pauseHandler(rawBuffer, orientationHandler))
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
         rawBuffer.bufferOff();
         rawBuffer.writeOff();
         orientationHandler.bufferOff();

         rawBuffer.flushFile();
         rawBuffer.closeFile();

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
         File orientationFile = orientationHandler.writeFile();
         int orientationCount = orientationHandler.writeCount();
         headerWriter = new PrintWriter(headerFile);
         headerWriter.println(String.format(Locale.US, "Version=%d", renderer.getVersionCode()));
         headerWriter.println(String.format(Locale.US, "VersionName=%s", renderer.getVersionName()));
         headerWriter.println(String.format(Locale.US, "StartBearing=%.1f", startBearing));
         headerWriter.println(String.format(Locale.US, "StartTime=%d", startTime));
         headerWriter.println(String.format(Locale.US, "EndTime=%d", endTime));
         headerWriter.println(String.format(Locale.US, "No=%d", n));
         headerWriter.println(String.format(Locale.US, "BufferSize=%d", renderer.rgbaBufferSize));
         headerWriter.println(String.format(Locale.US, "RawBufferSize=%d", renderer.rawBufferSize));
         headerWriter.println(String.format(Locale.US, "RawBufferFormat=%s", previewer.getPreviewFormat()));
         headerWriter.println(String.format(Locale.US, "PreviewWidth=%d", renderer.previewWidth));
         headerWriter.println(String.format(Locale.US, "PreviewHeight=%d", renderer.previewHeight));
         headerWriter.println(String.format(Locale.US, "FocalLength=%.6f", previewer.getFocalLen()));
         headerWriter.println(String.format(Locale.US, "HorizontalViewAngle=%.6f", previewer.getFovx()));
         headerWriter.println(String.format(Locale.US, "VerticalViewAngle=%.6f", previewer.getFovy()));
         headerWriter.println("Type=THREE60");
         OrientationProvider.ORIENTATION_PROVIDER orientationProviderType = orientationHandler.getType();
         headerWriter.println(String.format(Locale.US, "OrientationProvider=%s",
                                            (orientationProviderType == null) ? OrientationProvider.ORIENTATION_PROVIDER.DEFAULT.name()
                                                                              : orientationProviderType.name()));
         headerWriter.println(String.format(Locale.US, "OrientationCount=%d", orientationCount));
         headerWriter.flush();

         if (isPostProcess)
         {
            try { headerWriter.close(); } catch (Exception _e) {}
            headerWriter = null;
            renderer.toast("Raw frames and sensor data saved in " + recordingDir.getAbsolutePath());
            renderer.isPause = false;
            renderer.requestRender();
            previewer.setFlash(false);
            previewer.restartPreview();
            activity.showExit();
            return true;
         }

         orientationCount = orientationFilter(recordingDir, orientationFile, startBearing, progress, orientationCount,
                                              true, 8);
         File smoothOrientationFile = new File(recordingDir, orientationFile.getName() + ".smooth");
         if ( (orientationCount >= 0) && (smoothOrientationFile.exists()) && (smoothOrientationFile.length() > 0) )
         {
            orientationFile = smoothOrientationFile;
            headerWriter.println(String.format(Locale.US, "FilteredOrientationCount=%d", orientationCount));
         }

         Bufferable orientationBuffer = (Bufferable) orientationHandler;
         rawBuffer.openForReading();
         int[] shift_totals = new int[2], framecount = new int[1];
         Bufferable rgbaBuffer = convertFrames(recordingDir, rawBuffer, previewer, progress, true, shift_totals,
                                               framecount);
         if (rgbaBuffer == null)
         {
            Log.e(TAG, "Error converting frames");
            renderer.toast("Error converting frames");
            renderer.isPause = false;
            renderer.requestRender();
            previewer.setFlash(false);
            previewer.restartPreview();
            return false;
         }
         else
         {
            headerWriter.println(String.format(Locale.US, "ShiftX=%d", shift_totals[0]));
            headerWriter.println(String.format(Locale.US, "ShiftY=%d", shift_totals[1]));
            headerWriter.println(String.format(Locale.US, "FrameCount=%d", framecount[0]));
            headerWriter.flush();
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
               if ( (increment < this.startIncrement) || (increment > this.endIncrement) )
                  continue;;
//               int no = (int) (Math.floor(360.0f / startIncrement));
               int no = (int) (Math.floor(((float) (n + 1)) / increment));
               rgbaBuffer.readPos(0L);
               rawBuffer.readPos(0L);
               orientationBuffer.closeFile();
               orientationBuffer.openForReading(orientationFile);
               logWriter.println(); logWriter.println("startIncrement " + Float.toString(increment) + " n = " + n +
                                                      " no = " + no);
               kludgeCount[0] = 0;
               File ff = createThree60(startBearing, rawBuffer, rgbaBuffer, orientationBuffer, increment, no, orientationCount,
                                       mustStitch, progress, shift_totals, kludgeCount);
               if (ff != null)
               {
                  logWriter.println("startIncrement " + Float.toString(increment) + " kludges " + kludgeCount[0]);
                  incrementResults[i] = new Pair<>(i, kludgeCount[0]);
                  if (kludgeCount[0] == 0)
                    break;
               }
               else
                  logWriter.println("startIncrement " + Float.toString(increment) + " error");
               if (isCancelled())
                  break;
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
            headerWriter.println("FileFormat=RGBA");
            headerWriter.println(String.format(Locale.US, "FramesFile=%s", framesFile.getAbsolutePath()));
            headerWriter.println(String.format(Locale.US, "Increment=%6.1f", recordingIncrement));
            isCreated = true;
            progress.setStatus("Created Recording " + framesFile.getName(), 100, true, Toast.LENGTH_LONG);
            publishProgress(progress);
         }
         if (! RecorderActivity.IS_DEBUG)
         {
            File f = new File(recordingDir, "frames.RGBA");
            f.delete();
//            for (int i=0; i<recordingIncrements.length; i++)
//            {
//               float startIncrement = recordingIncrements[i];
//               f = new File(recordingDir, String.format(Locale.US, "%s.frames.%.1f", recordingDir.getName(), startIncrement));
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
         if (rawBuffer != null)
         {
            rawBuffer.bufferOff();
            rawBuffer.writeOff();
            try { rawBuffer.closeFile(); } catch (Exception ee) { Log.e(TAG, "", ee); }
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
         rawBuffer.stop();
         if (headerWriter != null)
            try { headerWriter.close(); } catch (Exception e) { Log.e(TAG, headerFile.getAbsolutePath(), e); }
         if ( (! RecorderActivity.IS_DEBUG) && (! isPostProcess) )
         {
            File f = new File(recordingDir, "frames.RAW");
            f.delete();
            f = new File(recordingDir, "frames.RGBA");
            f.delete();
            f = new File(recordingDir, "framestamps.txt");
            f.delete();
//            f = new File(recordingDir, "orientation");
//            f.delete();
//            f = new File(recordingDir, "orientation.smooth");
//            f.delete();
            f = new File(recordingDir, "bearingstamps.txt");
            f.delete();
         }
         if (logWriter != System.out)
            try { logWriter.close(); } catch (Exception e) {}
      }
   }

   protected File createThree60(float startBearing, Bufferable rawBuffer, Bufferable rgbaBuffer,
                                Bufferable orientationBuffer, float recordingIncrement, int no, int orientationCount,
                                boolean mustStitch, ProgressParam progress, int[] shift_totals, int[] kludgeCount)
         throws IOException
   //------------------------------------------------------------------------------------------------------------
   {
      if (progress != null)
      {
         progress.setStatus("Creating output (" + recordingIncrement + ")", 0, false, 0);
         publishProgress(progress);
      }
      int increments = (int) (Math.floor(360.0f / recordingIncrement));
      int shift_mean = shift_totals[0] / increments;
      File framesFile = new File(recordingDir, String.format(Locale.US, "%s.frames.%.1f", recordingDir.getName(),
                                           recordingIncrement));
      kludgeCount[0] = 0;
      RandomAccessFile raf = new RandomAccessFile(framesFile, "rw");
      int n = 0, N = 0;
      Bufferable.BufferData orientationBufData = orientationBuffer.read();
      if (orientationBufData == null)
         return null;
      long videoStartTimestamp = -1;
      if (startBearing < 0)
      {
         OrientationData orientationData = (OrientationData) orientationBufData.data;
         startBearing = orientationData.bearing();
         videoStartTimestamp = orientationData.timestamp;
      }
      float currentBearing = startBearing;
      long currentOffset = (long) (Math.floor(startBearing / recordingIncrement));
      long startOffset = currentOffset;
      float stopBearing = startBearing - recordingIncrement;
      if (stopBearing < 0)
         stopBearing += 360;
      long stopOffset = (long) (Math.floor(stopBearing / recordingIncrement));
      List<OrientationData> orientationsPerBearing = new ArrayList<>();
      byte[] frameContents = new byte[renderer.rgbaBufferSize]; //, kludgedContents = new byte[renderer.rgbaBufferSize];

      if (videoStartTimestamp < 0)
      {
         while (orientationBufData != null)
         {
            OrientationData orientationData = (OrientationData) orientationBufData.data;
            float bearing = orientationData.bearing();
            if (bearing >= startBearing)
            {
               videoStartTimestamp = orientationData.timestamp;
               break;
            }
            orientationBufData = orientationBuffer.read();
         }
      }
//      final long interval = 2000000000L;
      long nextTime, frameno = 0, lastFrameno =-1;
      ByteBuffer frame = null, lastFrame  = null;
      ByteBuffer[] lastFrames = new ByteBuffer[2];
      long[] lastFrameOffsets = new long[2];
      final int LAST_FRAME = 0, FRAME_BEFORE_LAST = 1;
      lastFrames[LAST_FRAME] = null; lastFrames[FRAME_BEFORE_LAST] = null;
      lastFrameOffsets[LAST_FRAME] = -1; lastFrameOffsets[FRAME_BEFORE_LAST] = -1;
      long lastValidOffset = 0;
      int kludgeTranslate = shift_mean;
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
            float bearing = orientationData.bearing(), lastBearing = orientationData.bearing();
            long bearingOffset = (long) (Math.floor(bearing / recordingIncrement));
            orientationsPerBearing.clear();
            while ( (orientationBufData != null) && (bearingOffset == currentOffset) )
            {
               orientationsPerBearing.add(orientationData);
               orientationBufData = orientationBuffer.read();
               if (orientationBufData != null)
               {
                  orientationData = (OrientationData) orientationBufData.data;
                  bearing = orientationData.bearing();
                  bearingOffset = (long) (Math.floor(bearing / recordingIncrement));
               }
            }

            nextTime = orientationData.timestamp + 300000000L;
            lastFrame = lastFrames[LAST_FRAME];
            Bufferable.BufferData frameBufData = rgbaBuffer.read();
            long frameOffset = -1;
            long minOrientationMatch = Long.MAX_VALUE;
            int shift_min = Integer.MAX_VALUE, matchShift1 = -1;
            ByteBuffer matchFrame = null, matchFrame2 = null;
            long matchTs = -1, matchTs2 = -1, matchFrameno = -1, matchFrameno2 = -1, matchOffset =-1, matchOffset2 =-1;
            while ( (frameBufData != null) && (frameBufData.timestamp < nextTime) )
            {
               long frameTimestamp = frameBufData.timestamp;
               if (frameTimestamp < 0)
               {
                  frameBufData = rgbaBuffer.read();
                  continue;
               }

               if (frameBufData.data != null)
               {
                  frame = (ByteBuffer) frameBufData.data;
                  if ( (lastFrame != null) && (frame != null) )
                  {
                     int[] shift = new int[2];
                     CV.TOGREY_SHIFT(renderer.previewWidth, renderer.previewHeight, lastFrame,
                                     frame, shift);
                     if (shift[0] > 0)
                     {
                        int sh = shift[0] - shift_mean; sh *= sh;
                        if (sh < shift_min)
                        {
                           shift_min = sh;
                           matchFrame = frame;
                           matchShift1 = shift[0];
                           matchFrameno = frameno;
                           matchTs = frameBufData.timestamp;
                           matchOffset = frameBufData.fileOffset;
                        }
                     }
                  }
                  if (frame != null)
                  {
                     for (OrientationData od : orientationsPerBearing)
                     {
                        long timediff = Math.abs(od.timestamp - frameTimestamp);
                        if ( (timediff < minOrientationMatch) && (frameno > lastFrameno) )
                        {
                           minOrientationMatch = timediff;
                           matchFrame2 = frame;
                           matchFrameno2 = frameno;
                           matchTs2 = frameBufData.timestamp;
                           matchOffset2 = frameBufData.fileOffset;
                        }
                     }
                  }
                  frameno++;
               }

               frameBufData = rgbaBuffer.read();
            }
            if (matchFrame == null)
            {
               matchFrame = matchFrame2;
               matchFrameno = matchFrameno2;
               matchTs = matchTs2;
               matchOffset = matchOffset2;
            }
            int matchShift2 = -1;
            if ( (matchFrame != matchFrame2) && (shift_min > 0) )
            {
               int[] shift = new int[2];
               CV.TOGREY_SHIFT(renderer.previewWidth, renderer.previewHeight, lastFrame, matchFrame2, shift);
               matchShift2 = shift[0];
               int sh = matchShift2 - shift_mean; sh *= sh;
               if (sh < shift_min)
               {
                  shift_min = sh;
                  matchFrame = matchFrame2;
                  matchFrameno = matchFrameno2;
                  matchTs = matchTs2;
                  matchOffset = matchOffset2;
               }
            }

            if (matchFrame != null)
            {
               frameOffset = matchOffset;
               ByteBuffer beforeLastFrame = lastFrames[FRAME_BEFORE_LAST];
               if ( (mustStitch) && (lastFrame != null) && (beforeLastFrame != null))
               {
                  ByteBuffer stitchedFrame = ByteBuffer.allocateDirect(renderer.rgbaBufferSize);
                  if (CV.STITCH3(renderer.previewWidth, renderer.previewHeight, beforeLastFrame, lastFrame,
                                 matchFrame, stitchedFrame))
                  {
//                     Mat M = new Mat(renderer.previewHeight, renderer.previewWidth, CvType.CV_8UC4);
//                     matchFrame.rewind(); matchFrame.get(frameContents); M.put(0, 0, frameContents);
//                     Mat MM = new Mat(renderer.previewHeight, renderer.previewWidth, CvType.CV_8UC4);
//                     Imgproc.cvtColor(M, MM, Imgproc.COLOR_RGBA2BGR); Imgcodecs.imwrite("/sdcard/matchframe.png", MM);
//                     lastFrame.rewind(); lastFrame.get(frameContents); M.put(0, 0, frameContents);
//                     Imgproc.cvtColor(M, MM, Imgproc.COLOR_RGBA2BGR); Imgcodecs.imwrite("/sdcard/lastframe.png", MM);
//                     beforeLastFrame.rewind(); beforeLastFrame.get(frameContents); M.put(0, 0, frameContents);
//                     Imgproc.cvtColor(M, MM, Imgproc.COLOR_RGBA2BGR); Imgcodecs.imwrite("/sdcard/beforelastframe.png", MM);
//                     stitchedFrame.rewind(); stitchedFrame.get(frameContents); M.put(0, 0, frameContents);
//                     Imgproc.cvtColor(M, MM, Imgproc.COLOR_RGBA2BGR); Imgcodecs.imwrite("/sdcard/stitchframe.png", MM);

//                     CV.TOGREY_SHIFT(renderer.previewWidth, renderer.previewHeight, beforeLastFrame,  stitchedFrame, shift);
//                     int sh = shift[0] - shift_mean; sh *= sh;
//                     if ( (sh <= Math.max(shift_min, 9)) && (lastFrameOffsets[LAST_FRAME] >= 0) )
//                     {
                        raf.seek(lastFrameOffsets[LAST_FRAME] * renderer.rgbaBufferSize);
                        stitchedFrame.rewind();
                        stitchedFrame.get(frameContents);
                        raf.write(frameContents);
//                     }
                  }
               }
               raf.seek(currentOffset * renderer.rgbaBufferSize);
               matchFrame.rewind();
               lastFrameno = matchFrameno;
               matchFrame.get(frameContents);
               raf.write(frameContents);
               lastFrames[FRAME_BEFORE_LAST] = lastFrames[LAST_FRAME];
               lastFrames[LAST_FRAME] = matchFrame;
               lastFrameOffsets[FRAME_BEFORE_LAST] = lastFrameOffsets[LAST_FRAME];
               lastFrameOffsets[LAST_FRAME] = currentOffset;
               Log.i(TAG, Integer.toString(n) + ": Wrote offset " + currentOffset + " bearing " + currentBearing +
                     " frame timestamp " + matchTs);
               logWriter.println(Integer.toString(n) + ": Wrote offset " + currentOffset + " bearing " + currentBearing +
                                 " frame timestamp " + matchTs);
               n++;
               rgbaBuffer.readPos(frameOffset);
               lastValidOffset = frameOffset;
               kludgeTranslate = shift_mean;
            }
            else
            {
               kludgeCount[0]++;
               if (kludgeCount[0] > MAX_KLUDGES)
                  return null;
               ByteBuffer kludgedFrame = ByteBuffer.allocateDirect(renderer.rgbaBufferSize);
               if (lastFrame == null)
                  lastFrame = (ByteBuffer) frameBufData.data;
               if (lastFrames != null)
               {
                  CV.KLUDGE_RGBA(renderer.previewWidth, renderer.previewHeight, lastFrame,
                                 kludgeTranslate, true, kludgedFrame);
                  kludgeTranslate += shift_mean;
                  raf.seek(currentOffset * renderer.rgbaBufferSize);
                  kludgedFrame.get(frameContents);
                  raf.write(frameContents);
                  lastFrames[FRAME_BEFORE_LAST] = lastFrames[LAST_FRAME];
                  lastFrames[LAST_FRAME] = kludgedFrame;
                  lastFrameOffsets[FRAME_BEFORE_LAST] = lastFrameOffsets[LAST_FRAME];
                  lastFrameOffsets[LAST_FRAME] = currentOffset;
                  Log.i(TAG, "******** " + n + ": Kludged offset" + currentOffset + " bearing " + currentBearing);
                        logWriter.println("******** " + n + ": Kludged offset " + currentOffset + " bearing " + currentBearing);
                  n++;
                  rgbaBuffer.readPos(lastValidOffset);
               }
            }
            lastBearing = currentBearing;
            currentOffset = bearingOffset;
            currentBearing = bearing;
            orientationsPerBearing.clear();
         }
         raf.close();

         if (currentOffset == stopOffset)
            syncLastFrame(framesFile, startOffset, stopOffset, shift_mean, videoStartTimestamp, mustStitch, progress);
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

   private void syncLastFrame(File framesFile, long startOffset, long stopOffset, int shift_mean,
                              long startTimestamp, boolean mustStitch, ProgressParam progress)
   //----------------------------------------------------------------------------------------------------
   {
      if (progress != null)
      {
         progress.setStatus("Synchronizing last frame", 100, false, 0);
         publishProgress(progress);
      }
      NativeFrameBuffer rawBuffer = null;
      RandomAccessFile raf = null;
      try
      {
         File rawFile = new File(recordingDir, "frames.RAW");
         if (rawFile.exists())
         {
            rawBuffer = new NativeFrameBuffer(3, renderer.rawBufferSize, false);
            if (! rawBuffer.openForReading(rawFile))
            {
               Log.e(TAG, "Could not reopen RAW frames buffer in syncLastFrame");
               return;
            }
            byte[] startFrameBuf = new byte[renderer.rgbaBufferSize], nextFrameBuf = new byte[renderer.rgbaBufferSize],
                   lastFrameBuf = new byte[renderer.rgbaBufferSize];
            raf = new RandomAccessFile(framesFile, "rw");
            raf.seek(startOffset * renderer.rgbaBufferSize);
            raf.readFully(startFrameBuf);
            raf.seek((startOffset + 1) * renderer.rgbaBufferSize);
            raf.readFully(nextFrameBuf);
            raf.seek(stopOffset * renderer.rgbaBufferSize);
            raf.readFully(lastFrameBuf);
            byte[] startFrameBufGrey = new byte[renderer.rgbaBufferSize / 4],
                   nextFrameBufGrey = new byte[renderer.rgbaBufferSize / 4],
                   lastFrameBufGrey = new byte[renderer.rgbaBufferSize / 4];
            Mat CM = new Mat(renderer.previewHeight, renderer.previewWidth, CvType.CV_8UC4);
            Mat BM = new Mat(renderer.previewHeight, renderer.previewWidth, CvType.CV_8UC1);
            CM.put(0, 0, startFrameBuf);
            Imgproc.cvtColor(CM, BM, Imgproc.COLOR_RGBA2GRAY);
            BM.get(0, 0, startFrameBufGrey);
            CM.put(0, 0, nextFrameBuf);
            Imgproc.cvtColor(CM, BM, Imgproc.COLOR_RGBA2GRAY);
            BM.get(0, 0, nextFrameBufGrey);
            CM.put(0, 0, lastFrameBuf);
            Imgproc.cvtColor(CM, BM, Imgproc.COLOR_RGBA2GRAY);
            BM.get(0, 0, lastFrameBufGrey);

            int[] shift = new int[2];
            int sh, shift_min = Integer.MAX_VALUE;
            // First compare last and first frames from written frame file
//            CV.TOGREY_SHIFT(renderer.previewWidth, renderer.previewHeight, lastFrameBuf, startFrameBuf, shift);
            CV.SHIFT(renderer.previewWidth, renderer.previewHeight, lastFrameBufGrey, startFrameBufGrey, shift);
            if (shift[0] > 0)
            {
               sh = shift[0] - shift_mean; sh *= sh;
               shift_min = sh;
            }
            byte[] frameMatchBuf = null;
            Bufferable.BufferData frameBufData = rawBuffer.read();
            while ( (frameBufData != null) && (frameBufData.timestamp < startTimestamp) )
            {
               if ( (frameBufData.timestamp < 0) ||  (frameBufData.data == null) )
               {
                  frameBufData = rawBuffer.read();
                  continue;
               }
               ByteBuffer bb = (ByteBuffer) frameBufData.data;
               bb.rewind();
               lastFrameBuf = convertRGBA(previewer, bb, previewer.getPreviewBufferSize(), lastFrameBufGrey);
//               CV.TOGREY_SHIFT(renderer.previewWidth, renderer.previewHeight, lastFrameBuf, startFrameBuf, shift);
               CV.SHIFT(renderer.previewWidth, renderer.previewHeight, lastFrameBufGrey, startFrameBufGrey, shift);
               if (shift[0] > 0)
               {
                  sh = shift[0] - shift_mean; sh *= sh;
                  if (sh < shift_min)
                  {
                     shift_min = sh;
                     frameMatchBuf = lastFrameBuf;
                     if (sh == 0)
                        break;
                  }
               }
               frameBufData = rawBuffer.read();
            }
            if (frameMatchBuf != null)
            {
               raf.seek(stopOffset * renderer.rgbaBufferSize);
               if (mustStitch)
               {
                  byte[] stitchedFrame = new byte[renderer.rgbaBufferSize];
                  if (CV.STITCH3(renderer.previewWidth, renderer.previewHeight, frameMatchBuf, startFrameBuf,
                                 nextFrameBuf, stitchedFrame))
                  {
                     raf.write(stitchedFrame);
                     Mat M = new Mat(renderer.previewHeight, renderer.previewWidth, CvType.CV_8UC4);
//                     M.put(0, 0, startFrameBuf); Imgcodecs.imwrite("/sdcard/startframe.png", M);
//                     M.put(0, 0, frameMatchBuf); Imgcodecs.imwrite("/sdcard/syncframe.png", M);
//                     M.put(0, 0, stitchedFrame); Imgcodecs.imwrite("/sdcard/stitchframe.png", M);
                  }
               }
               else
                  raf.write(frameMatchBuf);

            }
         }
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         return;
      }
      finally
      {
         if (rawBuffer != null)
            try { rawBuffer.closeFile(); } catch (Exception _e) {}
         if (raf != null)
            try { raf.close(); } catch (Exception _e) {}
      }

   }

   private Boolean testPostProcess(File headerFile) throws IOException
   //-----------------------------------------------------------------
   {
      if (TEST_POST_PROCESS) // JIT can remove if false (pseudo #ifdef)
      {
         try
         {
            renderer.toast("Debugging in testPostProcess");
            Bufferable rawBuffer = (Bufferable) previewer;

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
            if (IS_CREATE_ORIENT)
            {
               int ocount = orientationFilter(recordingDir, orientationFile, startBearing, null, 0, true, 8);
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
            int[] shift_totals = new int[2], framecount = new int[1];
            if (IS_CREATE_RGBA)
            {
               File rf = new File(recordingDir, "frames.RAW");
               if (!rawBuffer.openForReading(rf))
                  throw new RuntimeException("Could not open file " + rf);
               rgbaBuffer = convertFrames(recordingDir, rawBuffer, previewer, null, true,
                                          shift_totals, framecount);
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


               shift_totals[0] = 3238; shift_totals[1] = 0;
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
                  if (increment < this.startIncrement)
                     continue;;
   //               int no = (int) (Math.floor(360.0 / startIncrement));
                  int no = (int) (Math.floor(((float) (n + 1)) / increment));
                  rgbaBuffer.readPos(0L);
                  rawBuffer.readPos(0L);
                  orientationBuffer.closeFile();
                  orientationBuffer.openForReading(orientationFile);
                  logWriter.println(); logWriter.println("startIncrement " + Float.toString(increment) + " n = " + n +
                                                         " no = " + no);
                  File ff = createThree60(startBearing, rawBuffer, rgbaBuffer, orientationBuffer, increment, no, 0,
                                          false, null, shift_totals, kludgeCount);
                  if (ff != null)
                  {
                     logWriter.println("startIncrement " + Float.toString(increment) + " kludges " + kludgeCount[0]);
                     incrementResults[i] = new Pair<>(i, kludgeCount[0]);

                     //TODO: DELETE ME
//                     rgbaBuffer.readPos(0L);
//                     rawBuffer.readPos(0L);
//                     orientationBuffer.closeFile();
//                     orientationBuffer.openForReading(orientationFile);
//                     createThree60(startBearing, rawBuffer, rgbaBuffer, orientationBuffer, increment, no, 0,
//                                   false, null, shift_totals, kludgeCount);

                     if (kludgeCount[0] == 0)
                        break;
                  }
                  else
                  {
                     logWriter.println("startIncrement " + Float.toString(increment) + " error");

                     //TODO: DELETE ME
//                     rgbaBuffer.readPos(0L);
//                     rawBuffer.readPos(0L);
//                     orientationBuffer.closeFile();
//                     orientationBuffer.openForReading(orientationFile);
//                     createThree60(startBearing, rawBuffer, rgbaBuffer, orientationBuffer, increment, no, 0,
//                                   false, null, shift_totals, kludgeCount);
                  }
                  if (isCancelled())
                     break;
               }
               catch (Exception ee)
               {
                  logException("startIncrement " + Float.toString(startIncrement), ee);
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
