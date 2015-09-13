package to.augmented.reality.android.em.recorder;

import android.os.*;
import android.os.Process;
import android.util.Log;
import android.widget.Toast;
import to.augmented.reality.android.common.math.QuickFloat;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class MergeRecordingThread extends RecordingThread implements Freezeable
//=============================================================================
{
   static final private String TAG = MergeRecordingThread.class.getName();

   float lastBearing = Float.MIN_VALUE;
   private ExecutorService matchFramesExecutor = null;
   private Future<?> matchFrameFuture = null;
   private FrameWriterThread matchFramesThread = null;

   protected MergeRecordingThread(GLRecorderRenderer renderer, int nv21BufferSize, float increment,
                                  CameraPreviewThread previewer, ConditionVariable recordingCondVar,
                                  ConditionVariable frameCondVar,
                                  RecorderRingBuffer frameBuffer, BearingRingBuffer bearingBuffer)
   {
      super(renderer, nv21BufferSize, increment, previewer, recordingCondVar, frameCondVar, frameBuffer, bearingBuffer);
   }

   public MergeRecordingThread(GLRecorderRenderer renderer, RecorderRingBuffer frameBuffer)
   {
      super(renderer, frameBuffer);
   }

   BearingRingBuffer.RingBufferContent[] bearings;

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

   @Override
   protected Boolean doInBackground(Void... params)
   //----------------------------------------------
   {
      renderer.recordColor = GLRecorderRenderer.RED;
      renderer.isPause = true;
      renderer.requestRender();
      if (previewBuffer == null)
         previewBuffer = new byte[renderer.nv21BufferSize];
      if (previousBuffer == null)
         previousBuffer = new byte[renderer.nv21BufferSize];
      ProgressParam progress = new ProgressParam();
      BufferedWriter bw = null;
      File pass1BearingsFile, pass2BearingsFile = null, pass1FramesDir, pass2FramesDir = null;
      try
      {
         float lastUIBearing = 1000;
         int count = 0;
         String name = (renderer.recordFileName == null) ? "Unknown" : renderer.recordFileName;
         File dir = new File(renderer.recordDir, name);
         if (dir.exists())
            delDirContents(dir);
         dir.mkdirs();
         File pass1Dir = new File(dir, "pass1");
         pass1Dir.mkdirs();
         pass1BearingsFile = new File(pass1Dir, "bearings");
         pass1FramesDir = new File(pass1Dir, "frames");
         pass1FramesDir.mkdirs();
         matchFramesThread = new FrameWriterThread(renderer, previewer, Process.THREAD_PRIORITY_MORE_FAVORABLE,
                                                   pass1FramesDir);
         matchFramesExecutor = Executors.newSingleThreadExecutor();
         matchFrameFuture = matchFramesExecutor.submit(matchFramesThread);
         bearingBuffer.clear();
         previewer.clearBuffer();
         matchFramesThread.on();
         while (previewer.awaitFrame(400, previewBuffer) < 0);
         lastBearing = Float.MIN_VALUE;
         while (lastBearing == Float.MIN_VALUE)
         {
            bearingCondVar.close();
            bearingCondVar.block(20);
            lastBearing = bearingBuffer.peekBearing();
         }
         float startBearing = (float) Math.ceil(lastBearing), dist, recordingLastBearing = -1, rnb;
         bw = new BufferedWriter(new FileWriter(pass1BearingsFile), 32768);
         long lastOffset = -1;
         matchFramesThread.on();
         int pass = 1;
         final List<Float> skippedBearings = new ArrayList<>();
         boolean isPass2Recording = false;
         renderer.recordColor = GLRecorderRenderer.GREEN;
         boolean isSkipSet = false;
         Map<Long, SortedSet<Long>> pass1BearingTimes = new HashMap<>();
         TreeMap<Long, File> pass1FrameFileMap = new TreeMap<>();
         renderer.recordColor = GLRecorderRenderer.GREEN;
         renderer.isPause = false;
         renderer.requestRender();
         while ( (renderer.isRecording) && (! isCancelled()) )
         {
            bearings = bearingBuffer.popAll();
            if (bearings.length == 0)
            {
               bearingCondVar.close();
               bearingCondVar.block(40);
               continue;
            }
            for (BearingRingBuffer.RingBufferContent bearingInfo : bearings)
            {
               bw.write(String.format("%.5f=%d", bearingInfo.bearing, bearingInfo.timestamp));
               bw.newLine();
            }
            float bearing = bearings[bearings.length-1].bearing;
            long offset = (long) (Math.floor(bearing / recordingIncrement));
            if (lastOffset != offset)
            {
               lastOffset = offset;
               count++;
            }
            if (pass == 2)
            {
               dist = distance(bearing, recordingNextBearing);
               if (Math.abs(dist) <= 5)
               {
                  if (! isPass2Recording)
                  {
                     matchFramesThread.on();
                     isPass2Recording = true;
                  }
                  bearings = bearingBuffer.popAll();
                  if (bearings.length > 0)
                  {
                     for (BearingRingBuffer.RingBufferContent bearingInfo : bearings)
                     {
                        bw.write(String.format("%.5f=%d", bearingInfo.bearing, bearingInfo.timestamp));
                        bw.newLine();
                     }
                     bearing = bearings[bearings.length-1].bearing;
                     dist = distance(bearing, recordingNextBearing);
                  }
                  renderer.recordColor = GLRecorderRenderer.PURPLE;
                  if ( (Math.abs(dist) <= 0.8) && (bearing <= recordingNextBearing) )
                  {
                     previewer.awaitFrame(100, previewBuffer);
                     bearingCondVar.close();
                     bearingCondVar.block(40);
                  }
                  if ( (Math.abs(dist) <= 1) && (bearing >= recordingNextBearing) )
                  {
                     rnb = recordingLastBearing = recordingNextBearing;
                     recordingNextBearing = skippedBearings.remove(0);
                     isSkipSet = true;
                  }
                  else
                     rnb = recordingNextBearing;
               }
               else
               {
                  if ( (isSkipSet) && (Math.abs(distance(recordingLastBearing, bearing)) < 5) )
                     renderer.recordColor = GLRecorderRenderer.PURPLE;
                  else
                  {
                     isSkipSet = false;
                     if (isPass2Recording)
                     {
                        matchFramesThread.off();
                        isPass2Recording = false;
                     }
                     renderer.recordColor = GLRecorderRenderer.BLUE;
                  }
                  rnb = recordingNextBearing;
               }
            }
            else
               rnb = (offset + 1) * recordingIncrement;
            dist = distance(bearing, startBearing);
            if ( (pass == 2) && (skippedBearings.isEmpty()) )
               break;
            else if ((pass == 1) && (count > 20) && (Math.abs(dist) < 10) )
            {
               if ( (Math.abs(dist) <= 1) && (bearing >= startBearing) )
               {
                  renderer.recordColor = GLRecorderRenderer.RED;
                  renderer.isPause = true;
                  renderer.requestRender();
                  checkSkipped(startBearing, pass1BearingsFile, pass1FramesDir, pass1BearingTimes, pass1FrameFileMap,
                               skippedBearings);
                  if (skippedBearings.isEmpty())
                     break;
                  matchFramesThread.off();
                  pass = 2;
                  File pass2Dir = new File(dir, "pass2");
                  pass2Dir.mkdirs();
                  pass2BearingsFile = new File(pass2Dir, "bearings");
                  try { bw.close(); } catch (Exception _e) {}
                  bw = new BufferedWriter(new FileWriter(pass2BearingsFile), 32768);
                  pass2FramesDir = new File(pass2Dir, "frames");
                  pass2FramesDir.mkdirs();
                  matchFramesThread.setDir(pass2FramesDir);
                  recordingNextBearing = skippedBearings.remove(0);
                  renderer.recordColor = GLRecorderRenderer.BLUE;
                  renderer.isPause = false;
                  dist = distance(bearing, recordingNextBearing);
                  if (Math.abs(dist) <= 5)
                  {
                     isPass2Recording = true;
                     renderer.recordColor = GLRecorderRenderer.PURPLE;
                     matchFramesThread.on();
                  }
               }
            }
            if (Math.abs(bearing - lastUIBearing) >= recordingIncrement)
            {
               lastUIBearing = bearing;
               progress.set(bearing, rnb, renderer.recordColor, (count * 100) / no);
               publishProgress(progress);
               renderer.requestRender();
            }
         }
         renderer.recordColor = GLRecorderRenderer.RED;
         renderer.isPause = true;
         progress.setStatus("Merging bearing and frames", 100, false, 0);
         renderer.requestRender();
         try { Thread.sleep(200); } catch (Exception _e) {}
         matchFramesThread.off();
         try { bw.close(); bw = null; } catch (Exception _e) { Log.e(TAG, "", _e); }
         return merge(dir, startBearing, pass1BearingTimes, pass1FrameFileMap, pass2BearingsFile, pass2FramesDir);
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         progress.setStatus("Exception in Record thread: " + e.getMessage(), 100, true, Toast.LENGTH_LONG);
         publishProgress(progress);
         renderer.isRecording = false;
         return false;
      }
      finally
      {
         if (bw != null)
            try { bw.close(); } catch (Exception _e) { Log.e(TAG, "", _e); }
         matchFramesThread.enqueue(new FrameWriterThread.FrameFile());
         try {Thread.sleep(200); } catch (Exception _e) {}
         matchFramesExecutor.shutdown();
         try { matchFrameFuture.get(300, TimeUnit.MILLISECONDS); } catch (Exception e) { Log.e(TAG, "", e); }
         matchFramesExecutor.shutdownNow();
      }
   }

   public List<Float> checkSkipped(float startBearing, File bearingsFile, File framesDirectory,
                                   Map<Long, SortedSet<Long>> pass1BearingTimes, TreeMap<Long, File> pass1FrameFileMap,
                                   List<Float> skippedBearings)
   //------------------------------------------------------------------------------------------
   {
      if (skippedBearings == null)
         skippedBearings = new ArrayList<>();
      try
      {
         if (createBearingTimesList(bearingsFile, pass1BearingTimes) == null)
            return null;
         createFrameTimesList(framesDirectory, pass1FrameFileMap);

         float bearing = startBearing;
         do
         {
            long offset = (long) (Math.floor(bearing / recordingIncrement));
            SortedSet<Long> bearingList = pass1BearingTimes.get(offset);
            if ((bearingList == null) || (bearingList.isEmpty()))
            {
               skippedBearings.add(bearing);
               bearing = (bearing + recordingIncrement) % 360;
               continue;
            }
            long mintime = bearingList.first();
            long maxtime = bearingList.last();
            SortedMap<Long, File> frames = pass1FrameFileMap.tailMap(mintime, true).headMap(maxtime, true);
            if ((frames == null) || (frames.size() == 0))
            {
               skippedBearings.add(bearing);
               bearing = (bearing + recordingIncrement) % 360;
               continue;
            }
            bearing = (bearing + recordingIncrement) % 360;
         } while (QuickFloat.compare(bearing, startBearing, 0.0001f) != 0);
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         return null;
      }
      return skippedBearings;
   }

   public boolean merge(File dir, float startBearing, Map<Long, SortedSet<Long>> pass1BearingTimes,
                        TreeMap<Long, File> pass1FrameFileMap, File pass2BearingsFile, File pass2FramesDirectory)
   //-------------------------------------------------------------------------------------------------------------
   {
      long offset, nextoffset;
      List<Float> skippedBearings = new ArrayList<>();
      ByteBuffer tmpFrame = ByteBuffer.allocateDirect(renderer.nv21BufferSize),
                 frame = ByteBuffer.allocateDirect(renderer.nv21BufferSize);
      RandomAccessFile framesRAF = null;
      FileChannel channel;
      Map<Long, SortedSet<Long>> pass2BearingTimes = new HashMap<>();
      TreeMap<Long, File> pass2FrameFileMap = new TreeMap<>();
      try
      {
         if (pass2BearingsFile != null)
            createBearingTimesList(pass2BearingsFile, pass2BearingTimes);
         if (pass2FramesDirectory != null)
            createFrameTimesList(pass2FramesDirectory, pass2FrameFileMap);
         String filename = (renderer.recordFileName == null) ? "Unknown" : renderer.recordFileName;
         renderer.recordFramesFile = new File(dir, filename + ".frames.part");
         framesRAF = new RandomAccessFile(renderer.recordFramesFile, "rw");
         channel = framesRAF.getChannel();
         float bearing = startBearing;
         Set<Long> usedFrames = new HashSet<>();
         completedBearings = new HashSet<Long>((int) (360.0/recordingIncrement));
         do
         {
            offset = (long) (Math.floor(bearing / recordingIncrement));
            float nextBearing = (bearing + recordingIncrement) % 360;
            nextoffset = (long) (Math.floor(nextBearing / recordingIncrement));
            SortedSet<Long> pass1BearingList = pass1BearingTimes.get(offset);
            if ( (pass1BearingList == null) || (pass1BearingList.isEmpty()) )
            {
               skippedBearings.add(bearing);
               bearing = nextBearing;
               continue;
            }
            long mintime = pass1BearingList.first();
            long maxtime = pass1BearingList.last();
            SortedMap<Long, File> pass1Frames = pass1FrameFileMap.tailMap(mintime, true).headMap(maxtime, true);
            if ( (pass1Frames == null) || (pass1Frames.size() == 0) )
            {
               skippedBearings.add(bearing);
               bearing = nextBearing;
               continue;
            }

            MutablePair<Long, File> closest = closest(mintime, maxtime, pass1BearingList, pass1Frames, usedFrames);
            if ( (closest != null) && (closest.two != null) )
            {
               if (writeFrame(closest.two, offset, tmpFrame, channel))
               {
                  usedFrames.add(closest.one);
                  completedBearings.add(offset);
                  try { framesRAF.getFD().sync(); } catch (Exception _ee) { Log.e(TAG, "", _ee); }
               }
            }
            else
               skippedBearings.add(bearing);
            bearing = nextBearing;
         } while (QuickFloat.compare(bearing, startBearing, 0.0001f) != 0);

         if (! skippedBearings.isEmpty())
         {
            Set<File> completedFiles = new HashSet<>();
            Collections.sort(skippedBearings);
            SortedMap<Long, File> frames = new TreeMap<>();
            for (float skippedBearing : skippedBearings)
            {
               offset = (long) (Math.floor(skippedBearing / recordingIncrement));
               float nextBearing = (skippedBearing + recordingIncrement) % 360;
               nextoffset = (long) (Math.floor(nextBearing / recordingIncrement));
               long previousCompleteOffset = previousCompleteOffset(skippedBearing);
               if ((int) (offset - previousCompleteOffset) > 1)
                  continue;
//               long nextCompleteOffset = nextCompleteOffset(skippedBearing);
               SortedSet<Long> bearingList = pass2BearingTimes.get(offset);
               long mintime = bearingList.first();
               long maxtime = bearingList.last();
               frames.clear();
               SortedMap<Long, File> framesQuery = pass2FrameFileMap.tailMap(mintime - 1000000000, true).
                                                                     headMap(maxtime, true);
               frames.putAll(framesQuery);
               MutablePair<Long, File> closest = null;
               if ( (! bearingList.isEmpty()) && (! frames.isEmpty()) )
                  closest = closest(mintime, maxtime, bearingList, frames, usedFrames);
               bearingList.addAll(pass2BearingTimes.get(nextoffset));
               mintime = bearingList.first();
               maxtime = bearingList.last();
               framesQuery = pass2FrameFileMap.tailMap(mintime, true).headMap(maxtime + 1000000000, true);
               frames.putAll(framesQuery);
//               if (nextoffset == nextCompleteOffset)
//               {
//                  SortedSet<Long> pass1BearingList = pass1BearingTimes.get(nextoffset);
//                  mintime = pass1BearingList.first();
//                  maxtime = pass1BearingList.last();
//                  frames.putAll(pass1FrameFileMap.tailMap(mintime, true).headMap(maxtime, true));
//               }
               channel.position(previousCompleteOffset * renderer.nv21BufferSize);
               frame.rewind();
               channel.read(frame);
               double psnr, maxPsnr = -1;
               File maxFile = null;
               for (File f : frames.values())
               {
                  if (completedFiles.contains(f)) continue;
                  psnr = filePSNR(f, frame, tmpFrame);
                  if (psnr > maxPsnr)
                  {
                     maxPsnr = psnr;
                     maxFile = f;
                  }
               }
               File writeFile;
               if ( (maxFile != null) && (closest != null) )
               {
                  psnr = filePSNR(closest.two, frame, tmpFrame);
                  if (psnr > maxPsnr)
                     writeFile = closest.two;
                  else
                     writeFile = maxFile;
               }
               else if (closest != null)
                  writeFile = closest.two;
               else
                  writeFile = maxFile;
               if (writeFile != null)
               {
                  if (writeFrame(writeFile, offset, tmpFrame, channel))
                  {
                     completedBearings.add(offset);
                     completedFiles.add(writeFile);
                     try { framesRAF.getFD().sync(); } catch (Exception _ee) { Log.e(TAG, "", _ee); }
                  }
               }
            }
         }
      }
      catch (Exception e)
      {
         e.printStackTrace(System.err);
         Log.e(TAG, "", e);
         return false;
      }
      finally
      {
         if (framesRAF != null)
            try { framesRAF.close(); } catch (Exception _e) { Log.e(TAG, "", _e); }
      }
      return true;
   }

   private boolean writeFrame(File frameFile, long offset, ByteBuffer tmpFrame, FileChannel channel)
   //--------------------------------------------------------------------------------------------
   {
      FileInputStream fis = null;
      try
      {
         fis = new FileInputStream(frameFile);
         tmpFrame.rewind();
         fis.getChannel().read(tmpFrame);
         channel.position(offset * renderer.nv21BufferSize);
         channel.write(tmpFrame);
         return true;
      }
      catch (Exception _e)
      {
         Log.e(TAG, "Error writing " + frameFile + " to offset " + offset, _e);
         return false;
      }
      finally
      {
         if (fis != null)
            try { fis.close(); } catch (Exception _e) { Log.e(TAG, "", _e); }
      }
   }

   private MutablePair<Long, File> closest(long mintime, long maxtime, SortedSet<Long> bearingList, SortedMap<Long, File> frames,
                                    Set<Long> usedFrames)
   //------------------------------------------------------------------------------------------
   {
      SortedSet<Long> ml = bearingList.tailSet((maxtime - mintime) / 2);
      long mediantime;
      if (ml.size() > 0)
         mediantime = ml.first();
      else
         mediantime = bearingList.first();
      long closestTime = Long.MAX_VALUE;
      File closestFile = null;
      Set<Map.Entry<Long, File>> frameset = frames.entrySet();
      for (Map.Entry<Long, File> frame : frameset)
      {
         Long frametime = frame.getKey();
         if ( (usedFrames != null) && (usedFrames.contains(frametime)) )
            continue;
         long diff = Math.abs(frametime - mediantime);
         if (diff < closestTime)
         {
            closestTime = diff;
            closestFile = frame.getValue();
         }
      }
      if (closestFile == null)
         return null;
      return new MutablePair<>(closestTime, closestFile);
   }

   private Map<Long, SortedSet<Long>> createBearingTimesList(final File bearingsFile, Map<Long, SortedSet<Long>> list)
   //------------------------------------------------------------------------------------------------------------------
   {
      long offset, timestamp;
      if (list == null)
         list = new HashMap<>();
      BufferedReader br = null;
      try
      {
         br = new BufferedReader(new FileReader(bearingsFile));
         String s;
         while ((s = br.readLine()) != null)
         {
            String[] as = s.split("=");
            if (as.length < 2) continue;
            try
            {
               float bearing = Float.parseFloat(as[0].trim());
               timestamp = Long.parseLong(as[1].trim());
               offset = (long) (Math.floor(bearing / recordingIncrement));
               SortedSet<Long> bearingList = list.get(offset);
               if (bearingList == null)
               {
                  bearingList = new TreeSet<>();
                  list.put(offset, bearingList);
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
         return null;
      }
      finally
      {
         if (br != null)
            try { br.close(); } catch (Exception _e) {}
      }
      return list;
   }

   private TreeMap<Long, File> createFrameTimesList(File framesDirectory, TreeMap<Long, File> frameFileMap)
   //------------------------------------------------------------------------------------------------------
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
}
