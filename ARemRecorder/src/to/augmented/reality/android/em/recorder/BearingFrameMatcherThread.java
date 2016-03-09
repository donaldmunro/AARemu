package to.augmented.reality.android.em.recorder;

import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ArrayBlockingQueue;

class BearingFrameMatcherThread implements Runnable, CameraPreviewThread.FrameListenable
//========================================================================================
{
   static final private String TAG = BearingFrameMatcherThread.class.getName();
   static final protected int FRAMEWRITE_QUEUE_SIZE = 6;

   int priority = Process.THREAD_PRIORITY_DEFAULT;

   File dir;
   public File getDir() { return dir; }
   public void setDir(File dir) { this.dir = dir; }

   long size = 0;

   int count = 0;

   GLRecorderRenderer renderer;
   CameraPreviewThread previewer;

   boolean mustStop = false;

   static public class FrameFile
   //============================
   {
      public ByteBuffer buffer;
      public long timestamp;

      public FrameFile() { timestamp = -1; buffer = null; }

      public FrameFile(long timestamp, byte[] previewBuffer)
      //---------------------------------------------------
      {
         this.timestamp = timestamp;
         if (previewBuffer != null)
         {
            this.buffer = ByteBuffer.allocateDirect(previewBuffer.length);
            this.buffer.put(previewBuffer);
         }
      }
   }

   protected final ArrayBlockingQueue<FrameFile> frameQueue = new ArrayBlockingQueue<>(FRAMEWRITE_QUEUE_SIZE);

   public BearingFrameMatcherThread() {}

   public BearingFrameMatcherThread(GLRecorderRenderer renderer, CameraPreviewThread previewer)
   //----------------------------------------------------------------------------------
   {
      this(renderer, previewer, Process.THREAD_PRIORITY_MORE_FAVORABLE, null);
   }

   public BearingFrameMatcherThread(GLRecorderRenderer renderer, CameraPreviewThread previewer, int priority, File dir)
   //----------------------------------------------------------------------------------------------------------
   {
      this.renderer = renderer;
      this.previewer = previewer;
      this.priority = priority;
      if (dir == null)
      {
         dir = new File(renderer.recordDir, "frames");
         if (dir.isDirectory())
            clear();
         else if (dir.isFile())
            dir.delete();
         dir.mkdirs();
      }
      this.dir = dir;
   }

   public void enqueue(FrameFile frameFile) { try { frameQueue.put(frameFile); } catch (Exception _e) { Log.e(TAG, "", _e); } }

   public void clear()
   //-----------------
   {
      File[] files = dir.listFiles();
      for (File f : files)
         f.delete();
      count = 0;
      size = 0;
   }

   public void on() { previewer.setFrameListener(this); }

   public void off() { previewer.setFrameListener(null); }

   public boolean isOn() { return (previewer.getFrameListener() == this); }

   @Override
   public void run()
   //--------------
   {
      Process.setThreadPriority(priority);
      FrameFile frameBuffer;
      FileOutputStream fos;
      FileChannel channel;
      long timestamp, lastTimestamp = 0;
      ByteBuffer buffer;
      try
      {
         while (true)
         {
            if ( (frameQueue.isEmpty()) && ( (mustStop) || (! renderer.isRecording) ) )
               break;
            try { frameBuffer = frameQueue.take(); } catch (InterruptedException e) { break; }
            if (frameBuffer == null) continue;
            if ( (frameBuffer.timestamp == -1) && (frameBuffer.buffer == null) )
               break;
            fos = null;
            channel = null;
            try
            {
               timestamp = frameBuffer.timestamp;
               if ((timestamp - lastTimestamp) < 3000000)
                  continue;
               String filename = Long.toString(timestamp);
               File f = new File(dir, filename);
               if (f.exists())
                  continue;
               buffer = frameBuffer.buffer;
               fos = new FileOutputStream(f);
               channel = fos.getChannel();
               buffer.rewind();
               channel.write(buffer);
               size += buffer.capacity();
               count++;
               lastTimestamp = timestamp;
            }
            catch (Exception e)
            {
               Log.e(TAG, "Error writing frame", e);
            }
            finally
            {
               if (channel != null)
                  try { channel.close(); } catch (Exception _e) {}
               if (fos != null)
                  try { fos.close(); } catch (Exception _e) {}
            }
         }
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
      }
      finally
      {
         previewer.setFrameListener(null);
      }
   }

   @Override
   public void onFrameAvailable(byte[] data, long timestamp)
   //-------------------------------------------------------
   {
      try
      {
         if (! frameQueue.offer(new FrameFile(timestamp, data)))
            Log.e(TAG, "FrameWriterThread frame buffer full - frame skipped: " + timestamp);
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
      }
   }
}
