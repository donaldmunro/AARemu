/**
 * Released under the MIT license. http://opensource.org/licenses/MIT
 * @author Alexander Pacha https://bitbucket.org/apacha/sensor-fusion-demo
 * Some adaptions for use in AARemu by Donald Munro
 */

package to.augmented.reality.android.common.sensor.orientation;

import android.hardware.*;
import android.os.*;

/**
 * The orientation provider that delivers the current orientation from the {@link Sensor#TYPE_ACCELEROMETER
 * Accelerometer} and {@link Sensor#TYPE_MAGNETIC_FIELD Compass}.
 *
 * @author Alexander Pacha
 */
public class AccelerometerCompassProvider extends OrientationProvider
//===================================================================
{

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
   public AccelerometerCompassProvider(SensorManager sensorManager)
   {
      super(sensorManager);

      //Add the compass and the accelerometer
      sensorList.add(sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));
      sensorList.add(sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD));
   }

   private float[] I = new float[16];

   @Override
   public void onSensorChanged(SensorEvent event)
   //---------------------------------------------
   {
      if (isSuspended) return;
      switch (event.sensor.getType())
      {
         case Sensor.TYPE_MAGNETIC_FIELD:
//            magnitudeValues = event.values.clone();
            System.arraycopy(event.values, 0, magnitudeValues, 0, magnitudeValues.length);
            System.arraycopy(event.values, 0, lastMagVec, 0, MAG_VEC_SIZE);
            break;
         case Sensor.TYPE_ACCELEROMETER:
//            accelerometerValues = event.values.clone();
            System.arraycopy(event.values, 0, accelerometerValues, 0, accelerometerValues.length);
            System.arraycopy(event.values, 0, lastAccelVec, 0, ACCEL_VEC_SIZE);
            break;
      }

      if (magnitudeValues != null && accelerometerValues != null)
      {
         // Fuse accelerometer with compass
         SensorManager.getRotationMatrix(currentOrientationRotationMatrix, I, accelerometerValues, magnitudeValues);
         // Transform rotation matrix to quaternion
         currentOrientationQuaternion.setFromMatrix(currentOrientationRotationMatrix);

         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
            super.timestampNS = SystemClock.elapsedRealtimeNanos(); //event.timestamp;
         else
            super.timestampNS = System.nanoTime();
         if (orientationListener != null)
            orientationListener.onOrientationListenerUpdate(currentOrientationRotationMatrix,
                                                            currentOrientationQuaternion, super.timestampNS);
         notifyObservers();
      }
   }
}
