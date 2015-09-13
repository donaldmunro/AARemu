package to.augmented.reality.android.em.recorder;

import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ArrayBlockingQueue;

class FrameWriterThread implements Runnable, CameraPreviewThread.FrameListenable
//===============================================================================
{
   static final private String TAG = FrameWriterThread.class.getName();
   static final protected int FRAMEWRITE_QUEUE_SIZE = 6;

   int priority = Process.THREAD_PRIORITY_DEFAULT;

   File dir;
   public File getDir() { return dir; }
   public void setDir(File dir) { this.dir = dir; }

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
         this.buffer = ByteBuffer.allocateDirect(previewBuffer.length);
         this.buffer.put(previewBuffer);
      }
   }

   protected final ArrayBlockingQueue<FrameFile> frameQueue = new ArrayBlockingQueue<>(FRAMEWRITE_QUEUE_SIZE);

   public FrameWriterThread(GLRecorderRenderer renderer, CameraPreviewThread previewer)
   //----------------------------------------------------------------------------------
   {
      this(renderer, previewer, Process.THREAD_PRIORITY_DEFAULT, null);
   }

   public FrameWriterThread(GLRecorderRenderer renderer, CameraPreviewThread previewer, int priority, File dir)
   //----------------------------------------------------------------------------------------------------------
   {
      this.renderer = renderer;
      this.previewer = previewer;
      this.priority = priority;
      String filename = (renderer.recordFileName == null) ? "Unknown" : renderer.recordFileName;
      if (dir == null)
      {
         dir = new File(renderer.recordDir, filename + ".frames");
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
   }

   public void on() { previewer.setFrameListener(this); }

   public void off() { previewer.setFrameListener(null); }

   @Override
   public void run()
   //--------------
   {
      Process.setThreadPriority(priority);
      FrameFile frameBuffer;
      FileOutputStream fos;
      FileChannel channel;
      long size = 0, timestamp, lastTimestamp = 0;
      ByteBuffer buffer, previousBuffer = null;
      final int width = renderer.previewWidth, height = renderer.previewHeight;
      double psnr;
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
               if ((timestamp - lastTimestamp) < 5000000)
                  continue;
               String filename = Long.toString(timestamp);
               File f = new File(dir, filename);
               if (f.exists())
                  continue;
               buffer = frameBuffer.buffer;
               if (previousBuffer == null)
                  previousBuffer = ByteBuffer.allocateDirect(buffer.capacity());
               else
               {
                  psnr = FrameDiff.PSNR(width, height, buffer, previousBuffer);
                  if ( (psnr == 0) || (psnr > 30) )
                     continue;
               }
               fos = new FileOutputStream(f);
               channel = fos.getChannel();
               buffer.rewind();
               channel.write(buffer);
               size += buffer.capacity();
               previousBuffer.rewind();
               buffer.rewind();
               previousBuffer.put(buffer);
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
   {
      try { frameQueue.put(new FrameFile(timestamp, data)); } catch (Exception e) { Log.e(TAG, "", e); }
   }
}
