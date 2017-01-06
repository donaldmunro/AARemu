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

import android.hardware.Sensor;
import android.os.ConditionVariable;
import android.os.Environment;
import android.util.Log;
import to.augmented.reality.android.common.math.Quaternion;
import to.augmented.reality.android.common.sensor.orientation.OrientationListenable;
import to.augmented.reality.android.common.sensor.orientation.OrientationProvider;
import to.augmented.reality.android.common.sensor.orientation.RawSensorListenable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class OrientationHandler implements OrientationListenable, RawSensorListenable, Bufferable
//===============================================================================================
{
   final static String TAG = "OrientationListener";
   final static int XTRA_EVENT_BUFFER_SIZE = 200;
   final static ExecutorService processOrientationExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
   {
      @Override
      public Thread newThread(Runnable r)
      //-------------------------------------------
      {
         Thread t = new Thread(r);
         t.setDaemon(true);
         t.setName("ProcessOrientation");
         return t;
      }
   });
   final static ExecutorService eventWriterExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
   {
      @Override
      public Thread newThread(Runnable r)
      //-------------------------------------------
      {
         Thread t = new Thread(r);
         t.setDaemon(true);
         t.setName("EventWriter");
         return t;
      }
   });

   private final float[] RM = new float[16];
   private final OrientationProvider.ORIENTATION_PROVIDER type;

   public OrientationProvider.ORIENTATION_PROVIDER getType() { return type; }

   private ProgressParam param = new ProgressParam();
   private float lastUIBearing = -1000, lastUpdateBearing = -1000;
   private File recordingFile, xtraRecordingFile = null;
   private List<Integer> xtraSensorList = null;
   private DataOutputStream orientationWriter, xtraOrientationWriter = null;
   private DataInputStream orientationReader = null;
   private final OrientationRingBuffer buffer;
   private EventRingBuffer eventBuffer;
   private volatile boolean isRunning = false, mustBuffer = false, mustWrite = false, isStopping = false;
   private OrientationWriterThread writerThread;
   private EventWriterThread eventWriterThread = null;
   Future<?> writerFuture, eventWriterFuture = null;
   ConditionVariable cond = new ConditionVariable(false), eventCond = new ConditionVariable(false);
   public ConditionVariable getConditionVariable() { return cond; }
   private int writeCount = 0;
   private long startTimestamp = 0;
   volatile OrientationData lastReading = null;

   public float getLastBearing()
   //---------------------------
   {
      OrientationData reading = lastReading;
      if (reading == null)
         return -1;

      return reading.bearing();
   }

   public OrientationHandler(File dir, int bufferSize,
                             OrientationProvider.ORIENTATION_PROVIDER orientationProviderType) throws FileNotFoundException
   //--------------------------------------------------------------------------------
   {
      recordingFile = new File(dir, "orientation");
      orientationWriter = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(recordingFile), 32768));
      this.type = orientationProviderType;
      buffer = new OrientationRingBuffer(bufferSize);
      writerThread = new OrientationWriterThread();
      writerFuture = processOrientationExecutor.submit(writerThread);
   }

   @Override
   public void stop()
   //----------------
   {
      mustBuffer = false;
      isStopping = true;
      cond.open();
      try
      {
         if (writerFuture != null)
            writerFuture.get(200, TimeUnit.MILLISECONDS);
      }
      catch (Exception e)
      {
         if (isRunning)
            isRunning = false;
         if (writerFuture != null)
            try { writerFuture.get(500, TimeUnit.MILLISECONDS); } catch (Exception ee) { if (writerFuture != null) writerFuture.cancel(true); }
      }
      writerThread = null;
      writerFuture = null;
      if (eventWriterThread != null)
      {
         eventCond.open();
         try
         {
            eventWriterFuture.get(200, TimeUnit.MILLISECONDS);
         }
         catch (Exception e)
         {
            if (eventWriterThread.isRunning)
               eventWriterThread.isRunning = false;
            if (eventWriterFuture != null)
               try { eventWriterFuture.get(500, TimeUnit.MILLISECONDS); } catch (Exception ee) { eventWriterFuture.cancel(true); }
         }
         eventWriterThread = null;
         eventWriterFuture = null;
      }

      if (orientationWriter != null)
         try { orientationWriter.close(); orientationWriter = null; } catch (Exception e) {  }
      if (xtraOrientationWriter != null)
         try { xtraOrientationWriter.close(); xtraOrientationWriter = null; } catch (Exception e) { }
      xtraRecordingFile = null;
      xtraSensorList = null;
   }

   @Override public void bufferOn() { mustBuffer = true; }

   @Override public void bufferOff() { mustBuffer = false; }

   @Override public void bufferClear() { buffer.clear();  }

   @Override public boolean bufferEmpty() { return buffer.isEmpty(); }

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
      recordingFile = new File(dir, f.getName());
      if (orientationWriter != null)
      {
         try { orientationWriter.close(); } catch (Exception e) { Log.e(TAG, "", e); }
         orientationWriter = null;
      }
      orientationWriter = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(recordingFile), 32768));
      writeCount = 0;
   }

   @Override public File writeFile() { return recordingFile; }

   @Override
   public boolean openForReading()
   //-----------------------------
   {
      if ( (recordingFile == null) || (! recordingFile.exists()) )
         return false;
      try
      {
         orientationReader = new DataInputStream(new BufferedInputStream(new FileInputStream(recordingFile), 32768));
         return true;
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         return false;
      }
   }

   @Override
   public boolean openForReading(File f)
   //-----------------------------------
   {
      if ( (f == null) || (! f.isFile()) )
         return false;
      recordingFile = f;
      return openForReading();
   }

   private long filePos = 0;
   private long[] recordLen = new long[1];

   @Override
   public BufferData read() throws IOException
   //----------------------------------------
   {
      OrientationData orientationData = OrientationData.read(orientationReader, recordLen);
      if (orientationData == null)
         return null;
      filePos += recordLen[0];
      BufferData data = new BufferData();
      data.timestamp = orientationData.timestamp;
      data.data = orientationData;
      data.fileOffset = filePos;
      return data;
   }

   @Override public long readPos(long offset) { throw new RuntimeException("readPos not supported"); };

   @Override public boolean closeFile() throws IOException
   //-----------------------------------------------------
   {
      if (orientationWriter != null)
      {
         orientationWriter.close();
         orientationWriter = null;
         return true;
      }
      else
         return false;
   }

   @Override public void flushFile()
   //-----------------------------------------------------
   {
      if (orientationWriter != null)
         try { orientationWriter.flush(); } catch (IOException e) { Log.e(TAG, "", e); }
      if (xtraOrientationWriter != null)
         try { xtraOrientationWriter.flush(); } catch (IOException e) { Log.e(TAG, "", e); }
   }

   @Override public void writeOn() { mustWrite = true; }

   @Override public void writeOff() { mustWrite = false; }

   @Override public void startTimestamp(long timestamp) { this.startTimestamp = timestamp; }

   @Override public void push(long timestamp, byte[] data, int retries) { }

   @Override public int writeCount() { return writeCount; }

   @Override public long writeSize()
   //-------------------------------
   {
      if (recordingFile != null)
      {
         flushFile();
         return recordingFile.length();
      }
      else
         return 0L;
   }

   @Override
   public void onOrientationListenerUpdate(float[] R, Quaternion Q, long timestamp)
   //-----------------------------------------------------------------------------
   {
      if (mustBuffer)
      {
         buffer.push(timestamp, Q, R);
         cond.open();
      }
   }

   @Override
   public void onAccelSensorUpdate(float[] accelEvent, long timestamp)
   //-----------------------------------------------------------------
   {
      if (mustBuffer)
      {
         eventBuffer.push(timestamp, Sensor.TYPE_ACCELEROMETER, accelEvent);
         eventCond.open();
      }
   }

   @Override
   public void onGravitySensorUpdate(float[] gravityEvent, long timestamp)
   //---------------------------------------------------------------------
   {
      if (mustBuffer)
      {
         eventBuffer.push(timestamp, Sensor.TYPE_GRAVITY, gravityEvent);
         eventCond.open();
      }
   }

   @Override
   public void onGyroSensorUpdate(float[] gyroEvent, long timestamp)
   //---------------------------------------------------------------------
   {
      if (mustBuffer)
      {
         eventBuffer.push(timestamp, Sensor.TYPE_GYROSCOPE, gyroEvent);
         eventCond.open();
      }
   }

   @Override
   public void onMagneticSensorUpdate(float[] magEvent, long timestamp)
   //---------------------------------------------------------------------
   {
      if (mustBuffer)
      {
         eventBuffer.push(timestamp, Sensor.TYPE_MAGNETIC_FIELD, magEvent);
         eventCond.open();
      }
   }

   @Override
   public void onLinearAccelSensorUpdate(float[] linAccelEvent, long timestamp)
   //---------------------------------------------------------------------
   {
      if (mustBuffer)
      {
         eventBuffer.push(timestamp, Sensor.TYPE_LINEAR_ACCELERATION, linAccelEvent);
         eventCond.open();
      }
   }

   @Override
   public void onRotationVecSensorUpdate(float[] rotationEvent, long timestamp)
   //---------------------------------------------------------------------
   {
      if (mustBuffer)
      {
         eventBuffer.push(timestamp, Sensor.TYPE_ROTATION_VECTOR, rotationEvent);
         eventCond.open();
      }

   }

   public boolean initExtra(List<Integer> xtraSensorList) { return initExtra(xtraSensorList, null); }

   public boolean initExtra(List<Integer> xtraSensorList, String filename)
   //----------------------------------------------------------------------
   {
      if (( xtraSensorList == null) || (xtraSensorList.size() == 0) )
         return true;
      File dir;
      if ( (recordingFile != null) && (recordingFile.getParentFile() != null) )
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
         return false;
      if (filename == null)
         filename = "sensordata.raw";
      xtraRecordingFile = new File(dir, filename);
      try
      {
         xtraOrientationWriter = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(xtraRecordingFile), 32768));
         this.xtraSensorList = xtraSensorList;
         xtraOrientationWriter.writeInt(xtraSensorList.size());
         for (Integer sensorType : xtraSensorList)
            xtraOrientationWriter.writeInt(sensorType);
         eventBuffer = new EventRingBuffer(XTRA_EVENT_BUFFER_SIZE);
         eventWriterThread = new EventWriterThread();
         eventWriterFuture = eventWriterExecutor.submit(eventWriterThread);
         return true;
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         xtraOrientationWriter = null;
         xtraRecordingFile = null;
         eventBuffer = null;
      }
      return false;
   }

   class OrientationWriterThread implements Runnable
   //------------------------------------------------
   {
      @Override
      public void run()
      //---------------
      {
         isRunning = true;
         while (isRunning)
         {
            if ( (cond.block(50)) || (! buffer.isEmpty()) )
            {
               cond.close();
               OrientationData[] contents = buffer.popAll();
               for (OrientationData content : contents)
               {
                  if ( (mustWrite) && (orientationWriter != null) )
                  {
                     try
                     {
                        content.timestamp -= startTimestamp;
                        content.write(orientationWriter, -1);
                        writeCount++;
//                        Log.i(TAG, "Write bearing " + contents.length + " " + content.bearing() + " " + content.timestamp);
                     }
                     catch (Exception e)
                     {
                        Log.e(TAG, "Error writing orientation update to " + recordingFile.getAbsolutePath(), e);
                     }
                  }
                  lastReading = content;
                  //Log.i(TAG, "Last bearing " + lastReading.bearing() + " " + lastReading.timestamp + " " + mustWrite + " " + orientationWriter);
               }
            }
            else
               if ( (isStopping) && (buffer.isEmpty()) )
                  break;
         }
         isRunning = false;
      }
   }

   class EventWriterThread implements Runnable
   //-----------------------------------------
   {
      volatile public boolean isRunning = false;

      @Override
      public void run()
      //---------------
      {
         isRunning = true;
         while (isRunning)
         {
            if ( (eventCond.block(100)) || (! eventBuffer.isEmpty()) )
            {
               eventCond.close();
               EventRingBuffer.SensorEvent[] events = eventBuffer.popAll();
               for (EventRingBuffer.SensorEvent event : events)
               {
                  if ( (mustWrite) && (xtraOrientationWriter != null) )
                  {
                     try
                     {
                        final long ts = event.timestamp - startTimestamp;
                        final int len = event.data.length;
                        xtraOrientationWriter.writeInt(event.sensorType);
                        //xtraOrientationWriter.writeInt(len); // Always write 5 to make read faster
                        xtraOrientationWriter.writeLong(ts);
                        int i;
                        for (i=0; i<len; i++)
                           xtraOrientationWriter.writeFloat(event.data[i]);
                        for (;i<5; i++)
                           xtraOrientationWriter.writeFloat(Float.NaN);
                     }
                     catch (Exception e)
                     {
                        Log.e(TAG, "Error writing extra orientation update to " + recordingFile.getAbsolutePath(), e);
                     }
                  }
               }
            }
            else
            if ( (isStopping) && (eventBuffer.isEmpty()) )
               break;
         }
         isRunning = false;
      }
   }
}
