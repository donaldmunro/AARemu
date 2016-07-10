/**
 * Released under the MIT license. http://opensource.org/licenses/MIT
 * @author Alexander Pacha https://bitbucket.org/apacha/sensor-fusion-demo
 * Some adaptions for use in AARemu by Donald Munro
 */

package to.augmented.reality.android.common.sensor.orientation;

import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.SystemClock;

/**
 * The orientation provider that delivers the current orientation from the {@link Sensor#TYPE_ACCELEROMETER
 * Accelerometer} and {@link Sensor#TYPE_MAGNETIC_FIELD Compass}.
 *
 * @author Alexander Pacha
 */
public class AccelerometerCompassProvider extends OrientationProvider
//===================================================================
{
   static final protected int[] SENSORS = { Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_MAGNETIC_FIELD };

   @Override protected int[] fusedSensors() { return SENSORS; }

   /**
    * Compass values
    */
   private float[] magnitudeValues = new float[3];

   /**
    * Accelerometer values
    */
   private float[] accelerometerValues = new float[3];

   /**
    * Initialises a new AccelerometerCompassProvider
    *
    * @param sensorManager The android sensor manager
    */
   public AccelerometerCompassProvider(SensorManager sensorManager) { this(sensorManager, null, null); }

   /**
    * Initialises a new AccelerometerCompassProvider
    *
    * @param sensorManager The android sensor manager
    * @param extraSensors Extra sensors for raw event callback
    * @param extraSensorSpeeds the corresponding sensor update speeds which can be SensorManager.SENSOR_DELAY_FASTEST,
    *                     SENSOR_DELAY_GAME, SENSOR_DELAY_NORMAL or SENSOR_DELAY_UI. If null or empty then
    *                     SENSOR_DELAY_FASTEST is assumed.
    */
   public AccelerometerCompassProvider(SensorManager sensorManager, int[] extraSensors, int[] extraSensorSpeeds)
   //------------------------------------------------------------------------------------------------------------
   {
      super(sensorManager);

      //Add the compass and the accelerometer
      sensorList.add(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));
      sensorList.add(sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD));

      if ( (extraSensors != null) && (extraSensors.length > 0) )
         super.rawSensors(extraSensors, extraSensorSpeeds);
   }

   private float[] I = new float[16];

   @Override
   public void onSensorChanged(SensorEvent event)
   //---------------------------------------------
   {
      if (isSuspended) return;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
         super.timestampNS = SystemClock.elapsedRealtimeNanos(); //event.timestamp;
      else
         super.timestampNS = System.nanoTime();
      final int eventType = event.sensor.getType();
      switch (eventType)
      {
         case Sensor.TYPE_MAGNETIC_FIELD:
//            magnitudeValues = event.values.clone();
            System.arraycopy(event.values, 0, magnitudeValues, 0, magnitudeValues.length);
            break;
         case Sensor.TYPE_ACCELEROMETER:
//            accelerometerValues = event.values.clone();
            System.arraycopy(event.values, 0, accelerometerValues, 0, accelerometerValues.length);
            break;
      }

      if (magnitudeValues != null && accelerometerValues != null)
      {
         // Fuse accelerometer with compass
         SensorManager.getRotationMatrix(currentOrientationRotationMatrix, I, accelerometerValues, magnitudeValues);
         // Transform rotation matrix to quaternion
         currentOrientationQuaternion.setFromMatrix(currentOrientationRotationMatrix);

         if (orientationListener != null)
            orientationListener.onOrientationListenerUpdate(currentOrientationRotationMatrix,
                                                            currentOrientationQuaternion, super.timestampNS);
         notifyObservers();
      }
      onHandleEvent(eventType, event);
   }
}
