package to.augmented.reality.android.common.sensor.orientation;

public interface RawSensorListenable
//==================================
{
   void onAccelSensorUpdate(float[] accelEvent, long timestamp);

   void onGravitySensorUpdate(float[] gravityEvent, long timestamp);

   void onGyroSensorUpdate(float[] gyroEvent, long timestamp);

   void onMagneticSensorUpdate(float[] magEvent, long timestamp);

   void onLinearAccelSensorUpdate(float[] linAccelEvent, long timestamp);

   void onRotationVecSensorUpdate(float[] rotationEvent, long timestamp);
}
