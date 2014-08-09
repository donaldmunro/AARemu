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

   /**
    * Initialises a new RotationVectorProvider
    *
    * @param sensorManager The android sensor manager
    */
   public RotationVectorProvider(SensorManager sensorManager)
   //--------------------------------------------------------
   {
      super(sensorManager);

      //The rotation vector sensor that is being used for this provider to get device orientation
      sensorList.add(sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR));
   }

   @Override
   public void onSensorChanged(SensorEvent event)
   //---------------------------------------------
   {
      if (isSuspended) return;
      // we received a sensor event. it is a good practice to check
      // that we received the proper event
      if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR)
      {
         // convert the rotation-vector to a 4x4 matrix. the matrix
         // is interpreted by Open GL as the inverse of the
         // rotation-vector, which is what we want.
         SensorManager.getRotationMatrixFromVector(currentOrientationRotationMatrix, event.values);

         // Get Quaternion
         float[] q = new float[4];
         // Calculate angle. Starting with API_18, Android will provide this value as event.values[3], but if not, we have to calculate it manually.
         SensorManager.getQuaternionFromVector(q, event.values);
         currentOrientationQuaternion.setXYZW(q[1], q[2], q[3], -q[0]);

         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
            super.timestampNS = SystemClock.elapsedRealtimeNanos(); //event.timestamp;
         else
            super.timestampNS = System.nanoTime(); //event.timestamp;
         if (orientationListener != null)
            orientationListener.onOrientationListenerUpdate(currentOrientationRotationMatrix, currentOrientationQuaternion,
                                                            super.timestampNS);
         notifyObservers();
      }
   }
}
