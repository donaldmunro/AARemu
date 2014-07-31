/**
 * Released under the MIT license. http://opensource.org/licenses/MIT
 * Addition to sensor fusion code by Alexander Pacha (https://bitbucket.org/apacha/sensor-fusion-demo)
 * A single listener callback with timestamp and rotation arguments for optimising performance.
 */

package to.augmented.reality.android.common.sensor.orientation;

import to.augmented.reality.android.common.math.*;

public interface OrientationListenable
{
   /**
    * See OrientationProvider.setOrientationListener
    * @param M Rotation matrix
    * @param Q Rotation quaternion
    * @param timestamp time stamp
    */
   public void onOrientationListenerUpdate(float[] M, Quaternion Q, long timestamp);
}
