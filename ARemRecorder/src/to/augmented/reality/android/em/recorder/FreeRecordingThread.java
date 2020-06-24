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
import to.augmented.reality.android.common.sensor.orientation.OrientationProvider;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

public class FreeRecordingThread extends RecordingThread
//=====================================================
{
   static final private String TAG = FreeRecordingThread.class.getSimpleName();

   protected FreeRecordingThread(GLRecorderRenderer renderer, Previewable previewer, File recordDir,
                                 long maxSize, ConditionVariable frameAvailCondVar,
                                 OrientationHandler orientationHandler, LocationHandler locationHandler, boolean isPostProcess)
   //--------------------------------------------------------------------------------------------------------------------------
   {
      super(renderer, previewer, recordDir, maxSize, frameAvailCondVar, orientationHandler, locationHandler, isPostProcess);
   }

   @Override
   protected Boolean doInBackground(Void... params)
   //----------------------------------------------
   {
      ProgressParam progress = new ProgressParam();
      PowerManager powerManager = (PowerManager) renderer.activity.getSystemService(Context.POWER_SERVICE);
      PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wakelock");
      wakeLock.acquire();
      final long timestamp = SystemClock.elapsedRealtimeNanos();
      boolean isCreated = false;
      PrintWriter headerWriter = null;
      Bufferable previewBuffer = (Bufferable) previewer;
      Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
      try
      {
         renderer.recordColor = GLRecorderRenderer.GREEN;
         renderer.isPause = true;
         renderer.requestRender();

         recordingFile = new File(recordingDir, "frames.RAW");
         previewBuffer.startTimestamp(timestamp);
         previewBuffer.bufferClear();
         previewBuffer.writeFile(recordingFile);
         previewBuffer.bufferOn();
         previewBuffer.writeOn();

         orientationHandler.bufferClear();
         orientationHandler.startTimestamp(timestamp);
         orientationHandler.bufferOn();
         orientationHandler.writeOn();
         if (locationHandler != null)
         {
            locationHandler.bufferClear();
            locationHandler.startTimestamp(timestamp);
            locationHandler.bufferOn();
         }

         long then = System.currentTimeMillis();
         long now = System.currentTimeMillis();
         ConditionVariable orientationCond = orientationHandler.getConditionVariable();
         int matches = 0, nonmatches = 0, secs = 0;
         progress.setStatus("Free Record Mode", 0, false, 0);
         publishProgress(progress);
         long startTime = SystemClock.elapsedRealtimeNanos() - timestamp;
         renderer.isShowRecording = true;
         renderer.requestRender();
         while (renderer.isRecording)
         {
            now = System.currentTimeMillis();
            if (isPaused)
            {
               renderer.isShowRecording = false;
               if (! pauseHandler(previewBuffer, orientationHandler))
                  break;
               then = System.currentTimeMillis();
               renderer.isShowRecording = true;
            }
            else if ((now - then) > 1000)
            {
               secs++;
               then = now;
               if ((secs % 2) == 0)
               {
                  long written = previewBuffer.writeSize();
                  progress.setStatus("Free Record Mode (" + written/1048576L + "Mb)", (int)((written * 100)/ maxFrameFileSize),
                                     false, 0);
                  publishProgress(progress);
                  if (written > maxFrameFileSize)
                  {
                     Log.w(TAG, "Exiting due to file size exceeding " + maxFrameFileSize + " bytes");
                     renderer.toast("Exiting due to file size exceeding " + maxFrameFileSize + " bytes");
                     break;
                  }
               }
            }
            Thread.sleep(50);
         }
         renderer.isShowRecording = false;
         renderer.requestRender();
         Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);

         orientationHandler.bufferOff();
         orientationHandler.stop();
         orientationHandler.closeFile();
         previewBuffer.bufferOff();
         previewBuffer.writeOff();
         previewBuffer.flushFile();
         previewBuffer.closeFile();

         if (locationHandler != null)
         {
            locationHandler.bufferOff();
            locationHandler.stop();
            locationHandler.closeFile();
         }
         previewer.setFlash(false);
         previewer.suspendPreview();

         Log.w(TAG, "=================================== FreeRecording ============================");

         File headerFile = new File(recordingDir, recordingDir.getName() + ".head");
         File orientationFile = orientationHandler.writeFile();
         int orientationCount = orientationHandler.writeCount();
         try
         {
            headerWriter = new PrintWriter(headerFile);
            if (locationHandler != null)
            {
               File locationFile = locationHandler.writeFile();
               if ( (locationFile != null) && (locationFile.exists()) && (locationFile.length() > 0) )
                  headerWriter.println(String.format(Locale.US, "LocationFile=%s", locationFile.getAbsolutePath()));
            }
            headerWriter.println(String.format(Locale.US, "Version=%d", renderer.getVersionCode()));
            headerWriter.println(String.format(Locale.US, "VersionName=%s", renderer.getVersionName()));
            headerWriter.println(String.format(Locale.US, "StartTime=%d", startTime));
            headerWriter.println(String.format(Locale.US, "BufferSize=%d", renderer.rgbaBufferSize));
            headerWriter.println(String.format(Locale.US, "RawBufferSize=%d", renderer.rawBufferSize));
            headerWriter.println(String.format(Locale.US, "RawBufferFormat=%s", previewer.getPreviewFormat()));
            headerWriter.println(String.format(Locale.US, "PreviewWidth=%d", renderer.previewWidth));
            headerWriter.println(String.format(Locale.US, "PreviewHeight=%d", renderer.previewHeight));
            headerWriter.println(String.format(Locale.US, "FocalLength=%.6f", previewer.getFocalLen()));
            headerWriter.println(String.format(Locale.US, "HorizontalViewAngle=%.6f", previewer.getFovx()));
            headerWriter.println(String.format(Locale.US, "VerticalViewAngle=%.6f", previewer.getFovy()));
            headerWriter.println("Type=FREE");
            OrientationProvider.ORIENTATION_PROVIDER orientationProviderType = orientationHandler.getType();
            headerWriter.println(String.format(Locale.US, "OrientationProvider=%s",
                                               (orientationProviderType == null) ? OrientationProvider.ORIENTATION_PROVIDER.DEFAULT.name()
                                                                                 : orientationProviderType.name()));
            headerWriter.println(String.format(Locale.US, "OrientationCount=%d", orientationCount));
            headerWriter.flush();
         }
         catch (IOException ee)
         {
            Log.e(TAG, "Error creating header file " + headerFile, ee);
         }

         if (isPostProcess)
         {
            renderer.toast("Raw frames and sensor data saved in " + recordingDir.getAbsolutePath());
            renderer.isPause = false;
            renderer.requestRender();
            previewer.setFlash(false);
            return true;
         }

         orientationCount = orientationFilter(recordingDir, orientationFile, -1, progress, orientationCount, false, 8,
                                              -1);
         File smoothOrientationFile = new File(recordingDir, orientationFile.getName() + ".smooth");
         if ( (orientationCount < 0) || (! smoothOrientationFile.exists()) || (smoothOrientationFile.length() == 0) )
         {
            Log.e(TAG, "Orientation filter failed");
            smoothOrientationFile = null;
         }
         else if (headerFile != null)
         {
            try
            {
               headerWriter.println(String.format(Locale.US, "OrientationFile=%s", orientationFile.getAbsolutePath()));
               if ((smoothOrientationFile != null) && (smoothOrientationFile.exists()) && (smoothOrientationFile.length() > 0))
                  headerWriter.println(String.format(Locale.US, "FilteredOrientationFile=%s",
                                                     smoothOrientationFile.getAbsolutePath()));
               headerWriter.println(String.format(Locale.US, "FilteredOrientationCount=%d", orientationCount));
               headerWriter.flush();
            }
            catch (Exception _e)
            {
               Log.e(TAG, "Error updating header file with orientation", _e);
            }
         }
         previewBuffer.openForReading();
         Bufferable rgbaBuffer = convertFrames(recordingDir, previewBuffer, previewer, progress, false, null, null);
         if (rgbaBuffer == null)
         {
            Log.e(TAG, "Error converting frames");
            renderer.toast("Error converting frames");
            return false;
         }
         if (!isPostProcess)
         {
            File f = new File(recordingDir, "frames.RAW");
            f.delete();
         }
         File ff = new File(recordingDir, "frames.RGBA");
         File f = new File(recordingDir, recordingDir.getName() + ".frames");
         if (ff.exists())
         {
            f.delete();
            ff.renameTo(f);
            if ( (! f.exists()) && (ff.exists()) )
               f = ff;
            isCreated = ( (f.exists()) && (f.length() > 0) );
         }
         if ( (isCreated) && (headerFile != null) )
         {
            try
            {
               headerWriter.println("FileFormat=RGBA");
               headerWriter.println(String.format(Locale.US, "FramesFile=%s", f.getAbsolutePath()));
            }
            catch (Exception _e)
            {
               Log.e(TAG, "Error updating header file with frames file name", _e);
            }
         }
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         logException("", e);
         if (previewBuffer != null)
         {
            previewBuffer.bufferOff();
            previewBuffer.writeOff();
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
         previewBuffer.stop();
         wakeLock.release();
         if (headerWriter != null)
            try { headerWriter.close(); } catch (Exception _e) { Log.e(TAG, "", _e); }
         if ( (! RecorderActivity.IS_DEBUG) && (! isPostProcess) )
         {
            File f = new File(recordingDir, "frames.RAW");
            f.delete();
         }
         renderer.stopRecording(false);
      }
      return isCreated;
   }
}
