package to.augmented.reality.android.em;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEventListener;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.util.Pair;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * SensorManager emulation class used to play back raw sensor data saved by ARemRecorder.
 */
public class ARSensorManager implements Latcheable
//================================================
{
   final static private String TAG = ARSensorManager.class.getSimpleName();

   final SensorManager sensorManager;
   private File sensorFile;
   private int[] sensorTypes;
   private Map<Integer, Sensor> sensors;
   private DataInputStream dis;
   final private List<Pair<Integer, SensorEventListener>> observers = new ArrayList<>();
   private CountDownLatch startLatch = null;
   private ExecutorService playbackExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
      {
         @Override
         public Thread newThread(Runnable r)
         //---------------------------------
         {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("RawSensorPlayback");
            return t;
         }
      });
   private Future<?> playbackFuture = null;
   private SensorPlaybackThread playbackThread = null;

   /**
    * Constructs an ARSensorManager instance
    * @param sensorManager A 'real' SensorManager to delegate to.
    * @param sensorFile The raw sensor file (usually /sdcard/Documents/{record name}/sensordata.raw)
    * @throws IOException
    */
   public ARSensorManager(SensorManager sensorManager, File sensorFile) throws IOException
   //-------------------------------------------------------------------------------------
   {
      this.sensorManager = sensorManager;
      this.sensorFile = sensorFile;
      open();
   }

   /**
    * Set a latch used to synchronize starting the raw sensor data playback with other threads such as the camera
    * preview threads.
    * @param latch A CountDownLatch to use for synchronization.
    */
   @Override public void setLatch(CountDownLatch latch) { startLatch = latch; }

   /**
    * Restart playback
    * @throws IOException
    */
   public void restart() throws IOException { restart(null); }

   /**
    * Restart playback
    * @param latch Count down latch for resyncing
    * @throws IOException
    */
   public void restart(CountDownLatch latch) throws IOException
   //-------------------------------------
   {
      if (playbackThread != null)
         stop();
      if (dis != null)
         try { dis.close(); } catch (Exception e) {}
      open();
      start(latch);
   }

   public void start() { start(null); }

   /**
    * Start playback of raw data.
    * @param startLatch A latch used to synchronize starting the raw sensor data playback with other threads such
    *                   as the camera preview threads.
    */
   public void start(CountDownLatch startLatch)
   //------------------------------------------
   {
      if (playbackThread != null)
         stop();
      this.startLatch = startLatch;
      playbackThread = new SensorPlaybackThread();
      playbackFuture = playbackExecutor.submit(playbackThread);
   }

   /**
    * Stops the sensor playback.
    */
   public void stop()
   //----------------
   {
      if ( (playbackThread == null) || (playbackFuture == null) )
         return;
      if (playbackFuture.isDone())
      {
         playbackThread = null;
         playbackFuture = null;
         return;
      }
      playbackThread.isStop = true;
      Thread.yield();
      try
      {
         playbackFuture.get(500, TimeUnit.MILLISECONDS);
      }
      catch (Exception e)
      {
         playbackFuture.cancel(true);

      }
      playbackThread = null;
      playbackFuture = null;
      return;
   }

   public Sensor getDefaultSensor(int type) { return sensorManager.getDefaultSensor(type); }

   public Sensor getDefaultSensor(int type, boolean wakeUp) { return sensorManager.getDefaultSensor(type, wakeUp); }

   public boolean registerListener(SensorEventListener listener, Sensor sensor, int samplingPeriodUs, Handler handler)
   //-----------------------------------------------------------------------------------------------------------------
   {
      final int type = sensor.getType();
      if (! isEmulatingType(type))
         return sensorManager.registerListener(listener, sensor, samplingPeriodUs, handler);
      return registerARListener(listener, type);
   }

   public boolean registerListener(SensorEventListener listener, Sensor sensor, int samplingPeriodUs)
   //------------------------------------------------------------------------------------------------
   {
      final int type = sensor.getType();
      if (! isEmulatingType(type))
         return sensorManager.registerListener(listener, sensor, samplingPeriodUs);
      return registerARListener(listener, type);
   }

   public boolean registerListener(SensorEventListener listener, Sensor sensor, int samplingPeriodUs, int maxReportLatencyUs)
   //------------------------------------------------------------------------------------------------------------------------
   {
      final int type = sensor.getType();
      if (! isEmulatingType(type))
         return sensorManager.registerListener(listener, sensor, samplingPeriodUs, maxReportLatencyUs);
      return registerARListener(listener, type);
   }

   public boolean registerListener(SensorEventListener listener, Sensor sensor,
                                   int samplingPeriodUs, int maxReportLatencyUs, Handler handler)
   //--------------------------------------------------------------------------------------------
   {
      final int type = sensor.getType();
      if (! isEmulatingType(type))
         return sensorManager.registerListener(listener, sensor, samplingPeriodUs, maxReportLatencyUs, handler);
      return registerARListener(listener, type);
   }

   public void unregisterListener(SensorEventListener listener)
   //-----------------------------------------------------------
   {
      if (! unregisterARListener(listener, null))
         sensorManager.unregisterListener(listener);
   }

   public void unregisterListener(SensorEventListener listener, Sensor sensor)
   //-------------------------------------------------------------------------
   {
      final int type = sensor.getType();
      if (! isEmulatingType(type))
         sensorManager.unregisterListener(listener, sensor);
      else
         unregisterARListener(listener, sensor);
   }

   public boolean flush(SensorEventListener listener)
   //------------------------------------------------
   {
      if (! isARListener(listener))
         return sensorManager.flush(listener);
      else
         return true;
   }

   private boolean isARListener(SensorEventListener listener)
   //--------------------------------------------------------
   {
      synchronized (observers)
      {
         for (Pair<Integer, SensorEventListener> observer : observers)
         {
            if (observer == listener)
               return true;
         }
      }
      return false;
   }

   public boolean registerARListener(SensorEventListener listener, int type)
   //-----------------------------------------------------------------------
   {
      if (isARListener(listener))
         return true;
      synchronized (observers)
      {
         return observers.add(new Pair<Integer, SensorEventListener>(type, listener));
      }
   }

   public boolean unregisterARListener(SensorEventListener listener, Sensor sensor)
   //------------------------------------------------------------------------------
   {
      synchronized (observers)
      {
         for (int i=0; i<observers.size(); i++)
         {
            Pair<Integer, SensorEventListener> observer = observers.get(i);
            final int type = (sensor == null) ? observer.first : sensor.getType();
            if ( (observer.first == type) && (observer.second == listener) )
            {
               observers.remove(i);
               return true;
            }
         }
      }
      return false;
   }

   @Deprecated
   public int getSensors() { return sensorManager.getSensors(); }


   public static float[] getOrientation(float[] R, float[] values) { return SensorManager.getOrientation(R, values); }

   @Deprecated
   public boolean registerListener(SensorListener listener, int sensors, int rate)
   //-----------------------------------------------------------------------------
   {
      throw new UnsupportedOperationException("Deprecated registerListener not supported");
   }

   public boolean requestTriggerSensor(TriggerEventListener listener, Sensor sensor)
   //-------------------------------------------------------------------------------
   {
      final int type = sensor.getType();
      if (! isEmulatingType(type))
         return sensorManager.requestTriggerSensor(listener, sensor);
      throw new UnsupportedOperationException("Trigger sensor emulation not supported");
   }

   public boolean cancelTriggerSensor(TriggerEventListener listener, Sensor sensor)
   //------------------------------------------------------------------------------
   {
      final int type = sensor.getType();
      if (! isEmulatingType(type))
         return sensorManager.cancelTriggerSensor(listener, sensor);
      throw new UnsupportedOperationException("Trigger sensor emulation not supported");
   }

   public static boolean remapCoordinateSystem(float[] inR, int X, int Y, float[] outR)
   //-----------------------------------------------------------------------------------
   {
      return SensorManager.remapCoordinateSystem(inR, X, Y, outR);
   }

   public static float getAltitude(float p0, float p) { return SensorManager.getAltitude(p0, p); }

   public static float getInclination(float[] I) { return SensorManager.getInclination(I); }

   public static boolean getRotationMatrix(float[] R, float[] I, float[] gravity, float[] geomagnetic)
   //-------------------------------------------------------------------------------------------------
   {
      return SensorManager.getRotationMatrix(R, I, gravity, geomagnetic);
   }

   @Deprecated
   public void unregisterListener(SensorListener listener, int sensors)
   //-----------------------------------------------------------------
   {
      throw new UnsupportedOperationException("Deprecated unregisterListener not supported");
   }

   @Deprecated
   public boolean registerListener(SensorListener listener, int sensors)
   //-------------------------------------------------------------------
   {
      throw new UnsupportedOperationException("Deprecated registerListener not supported");
   }

   @Deprecated
   public void unregisterListener(SensorListener listener)
   {
      throw new UnsupportedOperationException("Deprecated unregisterListener not supported");
   }

   public static void getAngleChange(float[] angleChange, float[] R, float[] prevR)
   //------------------------------------------------------------------------------
   {
      SensorManager.getAngleChange(angleChange, R, prevR);
   }

   public static void getRotationMatrixFromVector(float[] R, float[] rotationVector)
   //-------------------------------------------------------------------------------
   {
      SensorManager.getRotationMatrixFromVector(R, rotationVector);
   }

   public static void getQuaternionFromVector(float[] Q, float[] rv) { SensorManager.getQuaternionFromVector(Q, rv); }

   public List<Sensor> getSensorList(int type) { return sensorManager.getSensorList(type); }

   final private void open() throws IOException
   //-------------------------------------------
   {
      dis = new DataInputStream(new BufferedInputStream(new FileInputStream(sensorFile), 32768));
      int cSensors = dis.readInt();
      sensorTypes = new int[cSensors];
      sensors = new HashMap<>(cSensors);
      for (int i=0; i<cSensors; i++)
      {
         final int type = dis.readInt();
         sensorTypes[i] = type;
         sensors.put(type, sensorManager.getDefaultSensor(type));
      }
   }

   private boolean isEmulatingType(int type)
   //-----------------------------------
   {
      if ( (sensorTypes == null) || (sensorTypes.length == 0) )
         return false;
      for (int typ : sensorTypes)
      {
         if (typ == type)
            return true;
      }
      return false;
   }

   class SensorPlaybackThread implements Runnable
   //============================================
   {
      public boolean isStop = false;

      @Override
      public void run()
      //---------------
      {
         if (startLatch != null)
         {
            startLatch.countDown();
            try { startLatch.await(); } catch (InterruptedException e) { return; }
         }
         try
         {
            long startTime, processStart, timestamp = 0, lastTimestamp = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
               processStart = startTime = SystemClock.elapsedRealtimeNanos();
            else
               processStart = startTime = System.nanoTime();
            int type =-1, len;
            Constructor<SensorEvent> constructor = SensorEvent.class.getDeclaredConstructor(Integer.TYPE);
            constructor.setAccessible(true);
            SensorEvent event = constructor.newInstance(5);
            final float[] values = event.values;
            try
            {
               type = dis.readInt();
               //len = dis.readInt();
               lastTimestamp = timestamp =  dis.readLong();
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
            while (! isStop)
            {
               long timediff;
               if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                  timediff = (timestamp - lastTimestamp - (SystemClock.elapsedRealtimeNanos() - processStart) - 1000L); // / 1000000L;
               else
                  timediff = (timestamp - lastTimestamp - (System.nanoTime() - processStart) - 1000L);// / 1000000L;
               long now, then;
               if ( (timediff > 0) && (! isStop) )
               {
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                     now = SystemClock.elapsedRealtimeNanos();
                  else
                     now = System.nanoTime();
                  then = now + timediff;
                  while ( (then > now) && (! isStop) )
                  {
   //                     try { Thread.sleep(0, (int) timediff/4); } catch (InterruptedException _e) { break; }
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
                  timestamp =  dis.readLong();
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
         catch (Exception e)
         {
            Log.e(TAG, "Error reading raw sensor file " + sensorFile, e);
            isStop = true;
         }
      }
   }


}
