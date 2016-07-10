/**
 * Released under the MIT license. http://opensource.org/licenses/MIT
 * @author Alexander Pacha https://bitbucket.org/apacha/sensor-fusion-demo
 * Some adaptions for use in AARemu by Donald Munro
 */

package to.augmented.reality.android.common.sensor.orientation;


import android.hardware.*;
import android.os.*;

/**
 * The orientation provider that delivers the current orientation from the {@link Sensor#TYPE_ROTATION_VECTOR Android
 * Rotation Vector sensor}.
 *
 * @author Alexander Pacha
 */
public class RotationVectorProvider extends OrientationProvider
//=============================================================
{
   static final protected int[] SENSORS = { Sensor.TYPE_ROTATION_VECTOR };

   @Override protected int[] fusedSensors() { return SENSORS; }

   public RotationVectorProvider(SensorManager sensorManager) { this(sensorManager, null, null); }

   /**
    * Initialises a new RotationVectorProvider
    *
    * @param sensorManager The android sensor manager
    */
   public RotationVectorProvider(SensorManager sensorManager, int[] extraSensors, int[] extraSensorSpeeds)
   //-----------------------------------------------------------------------------------------------------
   {
      super(sensorManager);

      //The rotation vector sensor that is being used for this provider to get device orientation
      sensorList.add(sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR));
      if ( (extraSensors != null) && (extraSensors.length > 0) )
         super.rawSensors(extraSensors, extraSensorSpeeds);
   }

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
      if (eventType == Sensor.TYPE_ROTATION_VECTOR)
      {
         // convert the rotation-vector to a 4x4 matrix. the matrix
         // is interpreted by Open GL as the inverse of the
         // rotation-vector, which is what we want.
         SensorManager.getRotationMatrixFromVector(currentOrientationRotationMatrix, event.values);

         // Get Quaternion
         float[] q = new float[4];
         SensorManager.getQuaternionFromVector(q, event.values);
         currentOrientationQuaternion.setXYZW(q[1], q[2], q[3], -q[0]);

         if (orientationListener != null)
            orientationListener.onOrientationListenerUpdate(currentOrientationRotationMatrix, currentOrientationQuaternion,
                                                            super.timestampNS);
         notifyObservers();
      }
      onHandleEvent(eventType, event);
      return;
   }
}
