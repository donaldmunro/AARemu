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

import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

@SuppressWarnings("JniMissingFunction")
public class NativeFrameBuffer implements Bufferable
//==================================================
{
   static public boolean isNativeLoaded = false;

   static
   {
      try
      {
         System.loadLibrary("framebuffer");
         isNativeLoaded = true;
      }
      catch (Exception e)
      {
         Log.e("NativeFrameBuffer", "Error loading native library libframebuffer.so for " + System.getProperty("os.arch"), e);
      }
   }

   private ByteBuffer ref = null;
   private File recordingFile = null;
   int frameSize = -1;
   public int getFrameSize() { return frameSize; }

   public NativeFrameBuffer(int count, int size, boolean mustCompress)
   //-----------------------------------------------------------------
   {
      if (! isNativeLoaded)
         throw new RuntimeException("Error loading libframebuffer.so");
      ref = construct(count, size, "ref", mustCompress);
      if (ref == null)
         throw new RuntimeException("Error initializing a libframebuffer.so instance");
      frameSize = size;
   }

   @Override protected void finalize() throws Throwable { destroy(ref); }

   public static native ByteBuffer construct(int count, int size, String refName, boolean mustCompress);

   public static native void destroy(ByteBuffer ref);

   @Override public native void bufferOn();

   @Override public native void bufferOff();

   @Override public native void bufferClear();

   @Override public native boolean bufferEmpty();

   @Override public native void startTimestamp(long timestamp);

   @Override public native void push(long timestamp, byte[] data, int retries);

   public native void pushYUV(long timestamp, ByteBuffer Y, int ysize, ByteBuffer U, int usize, int ustride,
                              ByteBuffer V, int vsize, int vstride, int retries);

   @Override public native void writeOn();

   @Override public native void writeOff();

   @Override public native int writeCount();

   @Override public long writeSize()
   //-------------------------------
   {
      if (recordingFile != null)
         return recordingFile.length();
      else
         return 0;
   }

   public native void writeFile(String filename);

   @Override native public boolean closeFile() throws IOException;

   @Override native public void flushFile();

   @Override public native void stop();

   @Override public boolean openForReading() { return openRead(null); }

   @Override public boolean openForReading(File f) { return openRead(f.getAbsolutePath()); }

   public native boolean openRead(String filename);

   @Override
   public BufferData read() throws IOException
   //-----------------------------------------
   {
      //LongBuffer timestampBuf = ByteBuffer.allocateDirect(Long.SIZE/8).asLongBuffer();
      //IntBuffer repeats = ByteBuffer.allocateDirect(Integer.SIZE/8).asIntBuffer();
      long[] timestampArg = new long[1];
      int[] sizeArg = new int[1];
      ByteBuffer frameBuf = ByteBuffer.allocateDirect(frameSize);
      long filepos = read(timestampArg, sizeArg, frameBuf);
      if (filepos < 0)
         return null;
      BufferData data = new BufferData();
      data.timestamp = timestampArg[0];
      if (sizeArg[0] == 0)
         data.data = null;
      else
      {
         frameBuf.rewind();
         data.data = frameBuf;
      }
      data.fileOffset = filepos;
      return data;
   }

   public native long read(long[] timestamp, int[] size, ByteBuffer frame);

   @Override public native long readPos(long offset);

   @Override
   public void writeFile(File f) throws IOException
   //----------------------------------------------
   {
      File dir;
      if (f.getParentFile() != null)
         dir = f.getParentFile();
      else if ( (recordingFile != null) && (recordingFile.getParentFile() != null) )
         dir = recordingFile.getParentFile();
      else
      {
         dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                        "ARRecorder");
         dir.mkdirs();
         if ( (! dir.isDirectory()) || (! dir.canWrite()) )
            dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                           "ARRecorder");
         dir.mkdirs();
      }
      if ( (! dir.isDirectory()) || (! dir.canWrite()) )
         throw new RuntimeException("Cannot create recording file in directory " + dir.getAbsolutePath());
      recordingFile = new File(dir, f.getName());
      writeFile(recordingFile.getAbsolutePath());
   }

   @Override public File writeFile() { return recordingFile; }
}
