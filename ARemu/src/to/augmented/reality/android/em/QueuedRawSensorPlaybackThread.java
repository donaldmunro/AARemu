package to.augmented.reality.android.em;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

public class QueuedRawSensorPlaybackThread implements Runnable, Latcheable
//=====================================================================
{
   final static private String TAG = QueuedRawSensorPlaybackThread.class.getSimpleName();

   public boolean isStop = false;

   private File sensorFile = null;

   private Map<Integer, Sensor> sensors;

   private List<Pair<Integer, SensorEventListener>> observers = new ArrayList<>();

   private CountDownLatch startLatch = null;
   @Override public void setLatch(CountDownLatch latch) { startLatch = latch; }

   private final ConcurrentLinkedQueue<Long> timestampQueue;

   public QueuedRawSensorPlaybackThread(File sensorFile, Map<Integer, Sensor> sensors,
                                        List<Pair<Integer, SensorEventListener>> observers,
                                        ConcurrentLinkedQueue<Long> timestampQueue)
   //------------------------------------------------------------------------------
   {
      this(sensorFile, sensors, observers, null, timestampQueue);
   }

   public QueuedRawSensorPlaybackThread(File sensorFile, Map<Integer, Sensor> sensors,
                                        List<Pair<Integer, SensorEventListener>> observers, CountDownLatch startLatch,
                                        ConcurrentLinkedQueue<Long> timestampQueue)
   //----------------------------------------------------------------------------------------------------
   {
      this.sensorFile = sensorFile;
      this.sensors = sensors;
      this.observers = observers;
      this.startLatch = startLatch;
      this.timestampQueue = timestampQueue;
   }

   public QueuedRawSensorPlaybackThread(ARSensorManager m, CountDownLatch startLatch, ConcurrentLinkedQueue<Long> timestampQueue)
   //-------------------------------------------------------------------------------------------------------------------------
   {
      sensorFile = m.sensorFile;
      sensors = m.sensors;
      observers = m.observers;
      this.startLatch = startLatch;
      this.timestampQueue = timestampQueue;
   }

   @Override
   public void run()
   //---------------
   {
      if (startLatch != null)
      {
         startLatch.countDown();
         try { startLatch.await(); } catch (InterruptedException e) { return; }
      }
      DataInputStream dis = null;
      Long nextTimestamp = -1L;
      try
      {
         dis = new DataInputStream(new BufferedInputStream(new FileInputStream(sensorFile), 32768));

         // skip header
         int cSensors = dis.readInt();
         for (int i = 0; i < cSensors; i++)
            dis.readInt();

         long startTime, processStart, timestamp = 0, lastTimestamp = 0;
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
            processStart = startTime = SystemClock.elapsedRealtimeNanos();
         else
            processStart = startTime = System.nanoTime();
         int type = -1, len;
         Constructor<SensorEvent> constructor = SensorEvent.class.getDeclaredConstructor(Integer.TYPE);
         constructor.setAccessible(true);
         SensorEvent event = constructor.newInstance(5);
         final float[] values = event.values;
         try
         {
            type = dis.readInt();
            //len = dis.readInt();
            lastTimestamp = timestamp = dis.readLong();
            values[0] = dis.readFloat();
            values[1] = dis.readFloat();
            values[2] = dis.readFloat();
            values[3] = dis.readFloat();
            values[4] = dis.readFloat();
         }
         catch (EOFException _e)
         {
            Log.w(TAG, "Empty raw sensor file " + sensorFile);
            isStop = true;
         }
         while (!isStop)
         {
            nextTimestamp = timestampQueue.poll();
            if (nextTimestamp == null)
            {
               Thread.sleep(10);
               continue;
            }
            while ((timestamp < nextTimestamp) && (!isStop))
            {
               long timediff;
               if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                  timediff = (timestamp - lastTimestamp - (SystemClock.elapsedRealtimeNanos() - processStart) - 1000L);
               else
                  timediff = (timestamp - lastTimestamp - (System.nanoTime() - processStart) - 1000L);
               long now, then;
               if ((timediff > 0) && (!isStop))
               {
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                     now = SystemClock.elapsedRealtimeNanos();
                  else
                     now = System.nanoTime();
                  then = now + timediff;
                  while ((then > now) && (!isStop))
                  {
                     Thread.yield();
                     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                        now = SystemClock.elapsedRealtimeNanos();
                     else
                        now = System.nanoTime();
                  }
               }

               if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                  processStart = SystemClock.elapsedRealtimeNanos();
               else
                  processStart = System.nanoTime();

               for (Pair<Integer, SensorEventListener> pp : observers)
               {
                  if (pp.first == type)
                  {
                     event.sensor = sensors.get(type);
                     pp.second.onSensorChanged(event);
                  }
               }

               try
               {
                  type = dis.readInt();
                  //len = dis.readInt();
                  lastTimestamp = timestamp;
                  timestamp = dis.readLong();
                  values[0] = dis.readFloat();
                  values[1] = dis.readFloat();
                  values[2] = dis.readFloat();
                  values[3] = dis.readFloat();
                  values[4] = dis.readFloat();
               }
               catch (EOFException _e)
               {
                  isStop = true;
                  break;
               }
            }
         }
      }
      catch (Exception e)
      {
         Log.e(TAG, "Error reading raw sensor file " + sensorFile, e);
         isStop = true;
      }
   }
}
