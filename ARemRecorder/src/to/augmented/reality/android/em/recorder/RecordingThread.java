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
import android.os.ConditionVariable;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

abstract public class RecordingThread extends AsyncTask<Void, ProgressParam, Boolean>
//====================================================================================
{
   static final private String TAG = RecordingThread.class.getSimpleName();
   static final protected int FRAMEWRITE_QUEUE_SIZE = 5;
   static final protected int WRITE_BUFFER_ADD_RETRIES = 20;
   static final protected int WRITE_BUFFER_DRAIN_TIMEOUT = 5;
   static final public boolean IS_LOGCAT_GOT = false;

   public enum RecordingType { THREE60, FREE }

   protected final OrientationHandler orientationHandler;
   protected final LocationHandler locationHandler;

   protected RecorderActivity activity = null;

   protected GLRecorderRenderer renderer;
   protected int no;
   protected boolean isStartRecording;
   volatile protected boolean isPaused = false;
   protected Previewable previewer;
   final protected File recordingDir;
   protected ConditionVariable frameAvailCondVar, pauseCondVar = null;
   protected File logFile = null;
   protected PrintStream logWriter = null;
   protected String error = null;
   protected File recordingFile;
   protected long maxFrameFileSize = 10000000000L; // ~= 10Gb
   protected boolean isDebug = false;

   public String getError() { return error; }
//   protected File recordCompleteBearingsFile = null;


   protected RecordingThread(GLRecorderRenderer renderer, Previewable previewer, File recordDir, long maxSize,
                             ConditionVariable frameAvailCondVar, OrientationHandler orientationHandler,
                             LocationHandler locationHandler, boolean isDebug)
   //----------------------------------------------------------------------------------------------------------------
   {
      this.renderer = renderer;
      this.activity = renderer.activity;
      this.previewer = previewer;
      if (recordDir == null)
      {
         recordDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                              "ARRecorder");
         recordDir.mkdirs();
         if ( (! recordDir.isDirectory()) || (! recordDir.canWrite()) )
         {
            recordDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                                 "ARRecorder");
            recordDir.mkdirs();
         }
      }
      if ( (! recordDir.isDirectory()) || (! recordDir.canWrite()) )
         throw new RuntimeException("Cannot create recording file in directory " + recordDir.getAbsolutePath());
      recordingDir = recordDir;
      this.maxFrameFileSize = maxSize;
      this.orientationHandler = orientationHandler;
      this.locationHandler = locationHandler;
      this.frameAvailCondVar = frameAvailCondVar;
      this.isStartRecording = true;
      logFile = new File(renderer.recordDir, "log");
      Log.i(TAG, "Record debug mode: " + isDebug);
      this.isDebug = isDebug;
      try
      {
         logWriter = new PrintStream(new BufferedOutputStream(new FileOutputStream(logFile)));
      }
      catch (Exception e)
      {
         logWriter = System.out;
         Log.e(TAG, "", e);
      }
   }

   @Override abstract protected Boolean doInBackground(Void... params);

   public void pauseRecording() { pauseCondVar = new ConditionVariable(false); isPaused = true; }

   public void resumeRecording() { isPaused = false; pauseCondVar.open(); }

   protected boolean pauseHandler(Bufferable cameraBuffer, Bufferable orientationBuffer)
   //-------------------------------------------------------------------------------------
   {
      cameraBuffer.bufferOff();
      cameraBuffer.writeOff();
      orientationBuffer.bufferOff();
      orientationBuffer.writeOff();
      renderer.recordColor = GLRecorderRenderer.GREEN;
      renderer.isPause = true;
      renderer.requestRender();
      while (isPaused)
      {
         if (pauseCondVar != null)
            pauseCondVar.block(50);
         else
            break;
         if (! renderer.isRecording)
            break;
      }
      if (! renderer.isRecording)
         return false;
      cameraBuffer.bufferOn();
      cameraBuffer.writeOn();
      orientationBuffer.bufferOn();
      orientationBuffer.writeOn();
      renderer.recordColor = GLRecorderRenderer.GREEN;
      renderer.isPause = true;
      renderer.requestRender();
      return true;
   }

   protected NativeFrameBuffer convertFrames(File dir, Bufferable framesBuffer, Previewable previewer,
                                             ProgressParam progress, boolean isRemoveRepeats, boolean isDebug)
         throws IOException
   //-----------------------------------------------------------------------------------------------------------
   {
      File f = new File(dir, "frames.RGBA"), ff = null;
      PrintWriter pw = null;
      if (isDebug)
      {
         ff = new File(dir, "framestamps.txt");
         try { pw = new PrintWriter(new FileWriter(ff)); } catch (Exception e) { pw = null; Log.e(TAG, "Create frame timestamps file", e); }
      }
      int count = framesBuffer.writeCount();
      if (count == 0)
         count = 1;
      if (progress != null)
      {
         progress.setStatus("Frame Conversion", 0, false, 0);
         publishProgress(progress);
      }
      Bufferable.BufferData frameBufData = framesBuffer.read();
      if (frameBufData == null)
         return null;
      long ts = frameBufData.timestamp, ts2 = -1L;
      byte[] grey = null, nextGrey = null;
      NativeFrameBuffer newFrameBuffer = new NativeFrameBuffer(3, renderer.rgbaBufferSize, false);
      newFrameBuffer.startTimestamp(0);
      newFrameBuffer.bufferOff();
      newFrameBuffer.writeFile(f.getAbsolutePath());
      if (isRemoveRepeats)
      {
         grey = new byte[renderer.rgbaBufferSize / 4];
         nextGrey = new byte[renderer.rgbaBufferSize / 4];
      }

//      ByteBuffer bb = (ByteBuffer) frameBufData.data;
//      bb.rewind();
//      FileOutputStream fw = new FileOutputStream(new File(dir, "frame.raw"));
//      byte buf[] = new byte[previewer.getPreviewBufferSize()];
//      bb.get(buf);
//      fw.write(buf);
//      fw.close();
//      bb.rewind();

      byte[] frame = convertRGBA(previewer, (ByteBuffer) frameBufData.data, previewer.getPreviewBufferSize(), grey);
      final int w = renderer.previewWidth;
      final int h = renderer.previewHeight;
      newFrameBuffer.writeOn();
      newFrameBuffer.bufferOn();
      List<Long> duplicateTimestamps = new ArrayList<>();
      double psnr;
      int n = 0;
      while ( (frameBufData = framesBuffer.read()) != null)
      {
         n++;
         if ( (progress != null) && ((n % 5) == 0) )
         {
            progress.setStatus("Frame Conversion", (n*100)/count, false, 0);
            publishProgress(progress);
         }
         ts2 = frameBufData.timestamp;
         byte[] nextframe = convertRGBA(previewer, (ByteBuffer) frameBufData.data, previewer.getPreviewBufferSize(),
                                        nextGrey);
         try { psnr = CV.PSNR(w, h, frame, nextframe); } catch (Exception ee) { Log.e(TAG, "", ee); psnr = -1; }
         if (psnr < 0)
         {
            continue;
//            if (pw != null)
//               pw.close();
//            Mat m = new Mat(h, w, CvType.CV_8UC1);
//            m.put(0, 0, frame);
//            Imgcodecs.imwrite("/sdcard/bad1.png", m);
//            m.put(0, 0, nextframe);
//            Imgcodecs.imwrite("/sdcard/bad2.png", m);
//            throw new RuntimeException("PSNR exception: images in /sdcard/bad1.png, /sdcard/bad2.png");
         }
         if (isRemoveRepeats)
         {
//               if (renderer.activity.isOpenCVJava())
//               {
//                  Mat m = new Mat(h, w, CvType.CV_8UC1);
//                  m.put(0, 0, grey);
//                  Imgcodecs.imwrite("/sdcard/m1.png", m);
//                  m.put(0, 0, nextGrey);
//                  Imgcodecs.imwrite("/sdcard/m2.png", m);
//               }
            if ( (psnr == 0) || (psnr > 32) )
            {
               duplicateTimestamps.add(ts2);
               continue;
            }
            int[] shift = new int[2];
            try
            {
               CV.SHIFT(w, h, grey, nextGrey, shift);
               if (shift[0] < 0)
               {
//                  duplicateTimestamps.add(ts2);
                  if (pw != null)
                     pw.println("T   " + ts + " " + shift[0]);
                  continue;
               }
            }
            catch (NativeCVException ee)
            {
               Log.e(TAG, "", ee);
            }
         }
         else if ( (psnr == 0) || (psnr > 32) )
         {
            duplicateTimestamps.add(ts2);
            continue;
         }
         if (pw != null)
            pw.println(ts);
         newFrameBuffer.push(ts, frame);
         if (! duplicateTimestamps.isEmpty())
         {
            for (long timestamp : duplicateTimestamps)
            {
               newFrameBuffer.push(timestamp, null);
               if (pw != null)
                  pw.println("D   " + timestamp);
            }
            duplicateTimestamps.clear();
         }
         frame = nextframe;
         grey = nextGrey;
         ts = ts2;
      }
      if (progress != null)
      {
         progress.setStatus("Frame Conversion", 100, false, 0);
         publishProgress(progress);
      }
      if (pw != null)
         pw.close();
      newFrameBuffer.stop();
      newFrameBuffer.closeFile();
      return newFrameBuffer;
   }

   private byte[] convertRGBA(Previewable previewer, ByteBuffer frameData, int previewBufferSize, byte[] grey)
   //---------------------------------------------------------------------------------------------------------
   {
      if (frameData == null)
         throw new RuntimeException("frameData was null");
      byte[] frame = new byte[previewBufferSize];
      frameData.rewind();
      frameData.get(frame);
      return previewer.toRGBA(activity, frame, renderer.previewWidth, renderer.previewHeight, renderer.rgbaBufferSize, grey);
   }

   protected int orientationFilter(File dir, File orientationFile, float startBearing, ProgressParam progress,
                                    int count, boolean isMonotonic, float rangeCheck, boolean isDebug)
   //-----------------------------------------------------------------------------------------------------
   {
      return orientationFilter(dir, orientationFile, startBearing, progress, count, isMonotonic, rangeCheck, -1, isDebug);
   }

   protected int orientationFilter(File dir, File orientationFile, float startBearing, ProgressParam progress,
                                    int count, boolean isMonotonic, float rangeCheck, int kernelSize, boolean isDebug)
   //---------------------------------------------------------------------------------------------------------------
   {
      if (progress != null)
      {
         progress.setStatus("Filtering Orientation Readings (0)", 0, false, 0);
         publishProgress(progress);
      }
      long N = 0;
      boolean mustFilter = (kernelSize > 0);
      int writecount = 0;
      if ( (mustFilter) && ((kernelSize % 2) == 0) )
         kernelSize++;
      File f = new File(dir, orientationFile.getName() + ".smooth");
      RingBuffer<OrientationData> buff = null;
      List<OrientationData> L = null;
      final int center = kernelSize / 2;
      if (mustFilter)
      {
         OrientationData[] array = new OrientationData[kernelSize];
         buff = new RingBuffer<>(array);
         L = new ArrayList<>(kernelSize);
      }
      float previousBearing = -1;
      PrintWriter pw = null;
      try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(orientationFile), 65535));
           DataOutputStream dos = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 65535)))
      {
         long[] recordLen = new long[1];
         OrientationData data = OrientationData.read(dis, recordLen);
         int n = 0;
         if (startBearing >= 0)
         {
            while (data != null)
            {
               float bearing = data.bearing();
               if (bearing >= startBearing)
                  break;
               data = OrientationData.read(dis, recordLen);
               n++;
            }
         }
         if (isDebug)
            try { pw = new PrintWriter(new FileWriter(new File(dir, "bearingstamps.txt"))); } catch (Exception _e) { pw = null; Log.e(TAG, "", _e); }
         int outOfRange = 0;
         boolean isStarted = false;
         while (data != null)
         {
            n++;
            if ( (progress != null) && ((n % 20) == 0) )
            {
               progress.setStatus("Filtering Orientation Readings (" + n + "/" + count + ")", (n * 100)/count, false, 0);
               publishProgress(progress);
            }
            float bearing = data.bearing();
            if ( (! isStarted) &&  (previousBearing >= 0) && (Math.floor(bearing) != Math.floor(startBearing)) )
               isStarted = true;
            else if ( (isStarted) && (Math.floor(bearing) == Math.floor(startBearing)) )
               break;

            if ( (previousBearing >= 0) && ( (isMonotonic) || (rangeCheck > 0) ) )
            {
               float bearingDiff = bearing - previousBearing;
               boolean isWrapped = isWrap(previousBearing, bearing);
               if (isWrapped)
                  bearingDiff += 360;
               if ( (isMonotonic) && (! isWrapped) && (Math.floor(bearing) < Math.floor(previousBearing)) )
               {
                  if (pw != null)
                     pw.println("-- " + bearing + " " + data.timestamp);
                  data = OrientationData.read(dis, recordLen);
                  continue;
               }
               if ( (rangeCheck > 0) && (Math.abs(bearingDiff) > rangeCheck) )
               {
                  if (pw != null)
                     pw.println("++ " + bearing + " " + data.timestamp);
                  if (outOfRange++ == 0)
                     dis.mark(data.size()*8);
                  else if (outOfRange > 3)
                  {
                     outOfRange = 0;
                     dis.reset();
                     data = OrientationData.read(dis, recordLen);
                     if (data != null)
                        previousBearing = data.bearing();
                     else
                        previousBearing = bearing;
                  }
                  data = OrientationData.read(dis, recordLen);
                  continue;
               }
            }
            previousBearing = bearing;

            if (mustFilter)
            {
               N++;
               if (N > kernelSize)
               {
                  buff.peekList(L);
                  buff.push(data);
                  data = L.get(center);
                  float ave = 0;
                  for (int i = 0; i < kernelSize; i++)
                     ave += L.get(i).bearing();
                  ave /= kernelSize;
                  data.write(dos, ave);
                  writecount++;
               }
               else
               {
                  buff.push(data);
                  if (N < kernelSize / 2)
                  {
                     data.write(dos, data.bearing());
                     writecount++;
                  }
               }
            }
            else
            {
               if (pw != null)
                  pw.println(bearing + " " + data.timestamp);
               data.write(dos, bearing);
               writecount++;
            }
            data = OrientationData.read(dis, recordLen);
         }
         if (mustFilter)
         {
            int left = buff.peekList(L);
            for (int i=center+1; i<left; i++)
            {
               data = L.get(i);
               data.write(dos, data.bearing());
               writecount++;
            }
         }
      }
      catch (IOException e)
      {
         StringBuilder sb = new StringBuilder();
         sb.append(orientationFile);
         Log.e(TAG, sb.toString(), e);
         f.delete();
         progress.setStatus("Error Filtering Orientation Readings", 0, false, 0);
         publishProgress(progress);
         return -1;
      }
      finally
      {
         if (pw != null)
            pw.close();
      }
      if (progress != null)
      {
         progress.setStatus("Filtering Orientation Readings", 100, false, 0);
         publishProgress(progress);
      }
      return writecount;
   }

   protected boolean isWrap(float previousBearing, float bearing)
   //------------------------------------------------------------
   {
      return ( ((previousBearing >= 355) && (previousBearing <= 359.9999999)) &&
               ((bearing >=0) && (bearing <=10)) );
   }

   static public class RingBuffer<T>
   //===============================
   {
      T[] array;
      int head, tail, length;
      final int count;

      public RingBuffer(T[] arr)
      //---------------------------------
      {
         array = arr;
         this.count = arr.length;
         head = tail = length = 0;
      }

      public void clear() { head = tail = length = 0; }

      public boolean isEmpty() { return (length == 0); }

      public boolean isFull() { return (length >= count); }

      public int push(T item)
      //-------------------------------------------------------------
      {
         if (length >= count)
         {
            tail = indexIncrement(tail);
            length--;
         }
         array[head] = item;
         head = indexIncrement(head);
         length++;
         return count - length;
      }

      public T pop()
      //-----------------------------------------
      {
         T popped = null;
         if (length > 0)
         {
            popped = array[tail];
            tail = indexIncrement(tail);
            length--;
         }
         return popped;
      }

      public int peekList(List<T> contents)
      //----------------------------------------------
      {
         contents.clear();
         int c = 0;
         if (length > 0)
         {
            int len = length;
            int t = tail;
            while (len > 0)
            {
               contents.add(array[t]);
               t = indexIncrement(t);
               len--;
               c++;
            }
         }
         return c;
      }

      private int indexIncrement(int i) { return (++i >= count) ? 0 : i; }
      private int indexDecrement(int i) { return (0 == i) ? (length - 1) : (i - 1);  }
   }

   @Override
   protected void onProgressUpdate(ProgressParam... values) { activity.onStatusUpdate(values[0]); }

   @Override protected void onPostExecute(Boolean B)
   //----------------------------------------------
   {
      if (renderer.isRecording)
         renderer.stopRecording(false);
      activity.stoppedRecording(null, new File(recordingDir, recordingDir.getName() + ".head"),
                                new File(recordingDir, recordingDir.getName() + ".frames"));
   }


   static public float closeDistance(float bearing, float nextBearing)
   //--------------------------------------------------------------
   {
      float bb = nextBearing - bearing;
      if ( (bearing >= 350) && (nextBearing >= 0) && (nextBearing <= 10) )
         return 360f + bb;
      else if ( (nextBearing >= 350) && (bearing >= 0) && (bearing <= 10) )
         return -(360f - bb);
      return bb;
   }

   static public float difference(float startBearing, float nextBearing)
   //-------------------------------------------------------------------
   {
      float bearing = nextBearing - startBearing;
      return (bearing < 0f) ? (bearing + 360f) : bearing;
   }

   static public float addBearing(float bearing, float addend)
   //--------------------------------------------------------
   {
      bearing += addend;
      if (bearing >= 360.0f)
         bearing -= 360.0f;
      return bearing;
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

   protected void logException(String errm, Exception e)
   //---------------------------------------------------
   {
      try
      {
         if ( (errm != null) && (! errm.isEmpty()) )
         {
            logWriter.println(errm);
         }
         if (e != null)
         {
            StringBuilder sb = new StringBuilder();
            sb.append(e.getMessage()).append(System.getProperty("line.separator"));
            for (StackTraceElement el : e.getStackTrace())
               sb.append(el.toString()).append(System.getProperty("line.separator"));
            logWriter.println(sb.toString());
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
}
