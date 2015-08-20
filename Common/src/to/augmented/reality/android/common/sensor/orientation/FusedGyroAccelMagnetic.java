package to.augmented.reality.android.common.sensor.orientation;

/*
 * Converted for OrientationProvider usage in AARemu.
 * Further floating point optimizations and reduced usage of heap allocations in frequently called methods.
 * Use built in native matrix multiply
 *    Donald Munro (2014)
 *
 * Copyright 2013, Kaleb Kircher - Boki Software, Kircher Electronics
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Copyright (c) 2012 Paul Lawitzki
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */

import android.hardware.*;
import android.os.*;
import to.augmented.reality.android.common.math.*;

import java.util.*;

/**
 * OrientationComplementaryFilter attempts to fuse magnetometer, gravity and
 * gyroscope sensors together to produce an accurate measurement of the rotation
 * of the device. The magnetometer and acceleration sensors are used to
 * determine the orientation of the device, but these readings are noisy and are
 * subject to the constraint that the device must not be accelerating. The
 * gyroscope is much more accurate and has a shorter response time, however it
 * experiences drift and has to be compensated periodically to remain accurate.
 * <p/>
 * The gyroscope provides the angular rotation speeds for all three axes. To
 * find the orientation of the device, the rotation speeds must be integrated
 * over time. This can be accomplished by multiplying the angular speeds by the
 * time intervals between sensor updates. The calculation produces the rotation
 * increment. Integrating these values again produces the absolute orientation
 * of the device. Small errors are produced at each iteration causing the gyro
 * to drift away from the true orientation.
 * <p/>
 * To eliminate both the drift and noise from the orientation, the gyro
 * measurements are applied only for orientation changes in short time
 * intervals. The magnetometer/acceleration fusion is used for long time
 * intervals. This is equivalent to low-pass filtering of the accelerometer and
 * magnetic field sensor signals and high-pass filtering of the gyroscope
 * signals.
 * <p/>
 * Note: The fusion algorithm itself was written by Paul @
 * http://www.thousand-thoughts.com/2012/03/android-sensor-fusion-tutorial/ and
 * taken from his SensorFusion1.zip project. J.W. Alexandar Qiu has credit for
 * the transitions between 179� <�> -179� fix. I have optimized some of the code
 * and made it slightly easier to follow and read. I have also changed the
 * SensorManager.getRotationMatrix() to use the gravity sensor instead of the
 * acceleration sensor.
 *
 * @author Kaleb
 * @version %I%, %G%
 * @see http://web.mit.edu/scolton/www/filter.pdf
 * @see http
 * ://developer.android.com/reference/android/hardware/SensorEvent.html#
 * values
 * @see http://www.thousand-thoughts.com/2012/03/android-sensor-fusion-tutorial/
 */
@SuppressWarnings("JavadocReference")
public class FusedGyroAccelMagnetic extends OrientationProvider
//===========================================================
{
   public static final float FILTER_COEFFICIENT = 0.5f;
   public static final float EPSILON = 0.000000001f;
   private static final String tag = FusedGyroAccelMagnetic.class.getSimpleName();
   // private static final float NS2S = 1.0f / 10000.0f;
   // Nano-second to second conversion
   private static final float NS2S = 1.0f / 1000000000.0f;

   private boolean hasOrientation = false;

   private float dT = 0;

   private float omegaMagnitude = 0;

   private float thetaOverTwo = 0;
   private float sinThetaOverTwo = 0;
   private float cosThetaOverTwo = 0;

   private float[] gravity = new float[]
         {0, 0, 0};

   // angular speeds from gyro
   private float[] gyroscope = new float[3];

   // rotation matrix from gyro data
   private float[] gyroMatrix = new float[9];

   // orientation angles from gyro matrix
   private float[] gyroOrientation = new float[3];

   // magnetic field vector
   private float[] magnetic = new float[3];

   // orientation angles from accel and magnet
   private float[] orientation = new float[3];

   // final orientation angles from sensor fusion
   private float[] fusedOrientation = new float[3];

   // accelerometer and magnetometer based rotation matrix
   private float[] rotationMatrix = new float[9];

   private float[] absoluteFrameOrientation = new float[3];

   // copy the new gyro values into the gyro array
   // convert the raw gyro data into a rotation vector
   private float[] deltaVector = new float[4];

   // convert rotation vector into rotation matrix
   private float[] deltaMatrix = new float[9];

   private long timeStamp = 0;

   private boolean initState = false;

   private MeanFilter meanFilterAcceleration;
   private MeanFilter meanFilterMagnetic;

   public FusedGyroAccelMagnetic(SensorManager sensorManager)
   //--------------------------------------------------------
   {
      super(sensorManager);
      sensorList.add(sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE));
      sensorList.add(sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY));
      sensorList.add(sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD));

      meanFilterAcceleration = new MeanFilter();
      meanFilterAcceleration.setWindowSize(10);

      meanFilterMagnetic = new MeanFilter();
      meanFilterMagnetic.setWindowSize(10);

      gyroOrientation[0] = 0.0f;
      gyroOrientation[1] = 0.0f;
      gyroOrientation[2] = 0.0f;

      // Initialize gyroMatrix with identity matrix
      gyroMatrix[0] = 1.0f;
      gyroMatrix[1] = 0.0f;
      gyroMatrix[2] = 0.0f;
      gyroMatrix[3] = 0.0f;
      gyroMatrix[4] = 1.0f;
      gyroMatrix[5] = 0.0f;
      gyroMatrix[6] = 0.0f;
      gyroMatrix[7] = 0.0f;
      gyroMatrix[8] = 1.0f;
   }

   @Override
   public void onSensorChanged(SensorEvent event)
   //--------------------------------------------
   {
      if (isSuspended) return;
      switch (event.sensor.getType())
      {
         case Sensor.TYPE_MAGNETIC_FIELD:
            System.arraycopy(event.values, 0, magnetic, 0, event.values.length);
            System.arraycopy(event.values, 0, lastMagVec, 0, MAG_VEC_SIZE);
            magnetic = meanFilterMagnetic.filterFloat(magnetic);
            break;
         case Sensor.TYPE_GRAVITY:
            System.arraycopy(event.values, 0, gravity, 0, event.values.length);
            System.arraycopy(event.values, 0, lastGravityVec, 0, GRAVITY_VEC_SIZE);
            gravity = meanFilterAcceleration.filterFloat(gravity);
            calculateOrientation();
            break;
         case Sensor.TYPE_GYROSCOPE:
            System.arraycopy(event.values, 0, gyroscope, 0, event.values.length);
            System.arraycopy(event.values, 0, lastGyroVec, 0, GYRO_VEC_SIZE);
            onGyroscopeSensorChanged(event.timestamp);
            break;
      }
   }

   public void onGyroscopeSensorChanged(long timeStamp)
   //--------------------------------------------------
   {
      if (! hasOrientation) return;

      // Initialization of the gyroscope based rotation matrix
      if (! initState)
      {
         gyroMatrix = matrixMultiplication(gyroMatrix, rotationMatrix);
//         Matrix.multiplyMV(gyroMatrix, 0, gyroMatrix, 0, rotationMatrix, 0);
         initState = true;
      }

      if (this.timeStamp != 0)
      {
         dT = (timeStamp - this.timeStamp) * NS2S;
         getRotationVectorFromGyro(dT / 2.0f);
      }

      // measurement done, save current time for next interval
      this.timeStamp = timeStamp;

      // Get the rotation matrix from the gyroscope
      SensorManager.getRotationMatrixFromVector(deltaMatrix, deltaVector);

      // Apply the new rotation interval on the gyroscope based rotation
      // matrix to form a composite rotation matrix. The product of two
      // rotation matricies is a rotation matrix...
      // Multiplication of rotation matrices corresponds to composition of
      // rotations... Which in this case are the rotation matrix from the
      // fused orientation and the rotation matrix from the current gyroscope
      // outputs.
      gyroMatrix = matrixMultiplication(gyroMatrix, deltaMatrix);
//      Matrix.multiplyMV(gyroMatrix, 0, gyroMatrix, 0, deltaMatrix, 0);

      // Get the gyroscope based orientation from the composite rotation
      // matrix. This orientation will be fused via complementary filter with
      // the orientation from the acceleration sensor and magnetic sensor.
      SensorManager.getOrientation(gyroMatrix, gyroOrientation);

      calculateFusedOrientation();
   }

   /**
    * Calculates orientation angles from accelerometer and magnetometer output.
    */
   private void calculateOrientation()
   //---------------------------------
   {
      if (SensorManager.getRotationMatrix(rotationMatrix, null, gravity, magnetic))
      {
         SensorManager.getOrientation(rotationMatrix, orientation);
         hasOrientation = true;
      }
   }

   /**
    * Calculates a rotation vector from the gyroscope angular speed values.
    *
    * @param gyroValues
    * @param deltaRotationVector
    * @param timeFactor
    * @see http://developer.android
    * .com/reference/android/hardware/SensorEvent.html#values
    */
   private void getRotationVectorFromGyro(float timeFactor)
   //------------------------------------------------------
   {
      // Calculate the angular speed of the sample
      omegaMagnitude = QuickFloat.sqrt(gyroscope[0] * gyroscope[0] + gyroscope[1] * gyroscope[1] + gyroscope[2] * gyroscope[2]);

      // Normalize the rotation vector if it's big enough to get the axis
      if (omegaMagnitude > EPSILON)
      {
         gyroscope[0] /= omegaMagnitude;
         gyroscope[1] /= omegaMagnitude;
         gyroscope[2] /= omegaMagnitude;
      }

      // Integrate around this axis with the angular speed by the timestep
      // in order to get a delta rotation from this sample over the timestep
      // We will convert this axis-angle representation of the delta rotation
      // into a quaternion before turning it into the rotation matrix.
      thetaOverTwo = omegaMagnitude * timeFactor;
      sinThetaOverTwo = (float) QuickFloat.sin(thetaOverTwo);
      cosThetaOverTwo = (float) QuickFloat.cos(thetaOverTwo);

      deltaVector[0] = sinThetaOverTwo * gyroscope[0];
      deltaVector[1] = sinThetaOverTwo * gyroscope[1];
      deltaVector[2] = sinThetaOverTwo * gyroscope[2];
      deltaVector[3] = cosThetaOverTwo;
   }

   /**
    * Calculate the fused orientation.
    */
   private void calculateFusedOrientation()
   //--------------------------------------
   {
      final float oneMinusCoeff = (1.0f - FILTER_COEFFICIENT);

		/*
		 * Fix for 179� <--> -179� transition problem: Check whether one of the
		 * two orientation angles (gyro or accMag) is negative while the other
		 * one is positive. If so, add 360 (2 * math.PI) to the negative value,
		 * perform the sensor fusion, and remove the 360 from the result if it
		 * is greater than 180. This stabilizes the output in
		 * positive-to-negative-transition cases.
		 */

      // azimuth
      if (gyroOrientation[0] < -QuickFloat.HALF_PI && orientation[0] > 0.0)
      {
         fusedOrientation[0] = (FILTER_COEFFICIENT * (gyroOrientation[0] + QuickFloat.TWO_PI) + oneMinusCoeff * orientation[0]);
         fusedOrientation[0] -= (fusedOrientation[0] > Math.PI) ? QuickFloat.TWO_PI : 0;
      } else if (orientation[0] < -QuickFloat.HALF_PI && gyroOrientation[0] > 0.0)
      {
         fusedOrientation[0] = (FILTER_COEFFICIENT * gyroOrientation[0] + oneMinusCoeff * (orientation[0] + QuickFloat.TWO_PI));
         fusedOrientation[0] -= (fusedOrientation[0] > Math.PI) ? QuickFloat.TWO_PI : 0;
      } else
         fusedOrientation[0] = FILTER_COEFFICIENT * gyroOrientation[0] + oneMinusCoeff * orientation[0];

      // pitch
      if (gyroOrientation[1] < -QuickFloat.HALF_PI && orientation[1] > 0.0)
      {
         fusedOrientation[1] = (FILTER_COEFFICIENT * (gyroOrientation[1] + QuickFloat.TWO_PI) + oneMinusCoeff * orientation[1]);
         fusedOrientation[1] -= (fusedOrientation[1] > Math.PI) ? QuickFloat.TWO_PI : 0;
      } else if (orientation[1] < -QuickFloat.HALF_PI && gyroOrientation[1] > 0.0)
      {
         fusedOrientation[1] = (FILTER_COEFFICIENT * gyroOrientation[1] + oneMinusCoeff * (orientation[1] + QuickFloat.TWO_PI));
         fusedOrientation[1] -= (fusedOrientation[1] > Math.PI) ? QuickFloat.TWO_PI : 0;
      } else
         fusedOrientation[1] = FILTER_COEFFICIENT * gyroOrientation[1] + oneMinusCoeff * orientation[1];

      // roll
      if (gyroOrientation[2] < -QuickFloat.HALF_PI && orientation[2] > 0.0)
      {
         fusedOrientation[2] = (FILTER_COEFFICIENT * (gyroOrientation[2] + QuickFloat.TWO_PI) + oneMinusCoeff * orientation[2]);
         fusedOrientation[2] -= (fusedOrientation[2] > Math.PI) ? QuickFloat.TWO_PI : 0;
      } else if (orientation[2] < -QuickFloat.HALF_PI && gyroOrientation[2] > 0.0)
      {
         fusedOrientation[2] = (FILTER_COEFFICIENT * gyroOrientation[2] + oneMinusCoeff * (orientation[2] + QuickFloat.TWO_PI));
         fusedOrientation[2] -= (fusedOrientation[2] > Math.PI) ? QuickFloat.TWO_PI : 0;
      } else
         fusedOrientation[2] = FILTER_COEFFICIENT * gyroOrientation[2] + oneMinusCoeff * orientation[2];

      // overwrite gyro matrix and orientation with fused orientation
      // to comensate gyro drift
      gyroMatrix = getRotationMatrixFromOrientation(fusedOrientation);

      System.arraycopy(fusedOrientation, 0, gyroOrientation, 0, 3);
      synchronized (syncToken)
      {
         Arrays.fill(currentOrientationRotationMatrix, 0);
         currentOrientationRotationMatrix[0] = gyroMatrix[0];
         currentOrientationRotationMatrix[1] = gyroMatrix[1];
         currentOrientationRotationMatrix[2] = gyroMatrix[2];
         currentOrientationRotationMatrix[4] = gyroMatrix[3];
         currentOrientationRotationMatrix[5] = gyroMatrix[4];
         currentOrientationRotationMatrix[6] = gyroMatrix[5];
         currentOrientationRotationMatrix[8] = gyroMatrix[6];
         currentOrientationRotationMatrix[9] = gyroMatrix[7];
         currentOrientationRotationMatrix[10] = gyroMatrix[8];
         currentOrientationQuaternion.setFromMatrix(gyroMatrix);
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
         super.timestampNS = SystemClock.elapsedRealtimeNanos(); //event.timestamp;
      else
         super.timestampNS = System.nanoTime();
      if (orientationListener != null)
         orientationListener.onOrientationListenerUpdate(currentOrientationRotationMatrix, currentOrientationQuaternion,
                                                         super.timestampNS);
      notifyObservers();
   }

   float[] rotationFromOrientMatrix = new float[9];
   final float[] xM = new float[9];
   final float[] yM = new float[9];
   final float[] zM = new float[9];

   /**
    * Get the rotation matrix from the current orientation. Android Sensor
    * Manager does not provide a method to transform the orientation into a
    * rotation matrix, only the orientation from a rotation matrix. The basic
    * rotations can be found in Wikipedia with the caveat that the rotations
    * are *transposed* relative to what is required for this method.
    *
    * @param The device orientation.
    * @return The rotation matrix from the orientation.
    * @see http://en.wikipedia.org/wiki/Rotation_matrix
    */
   private float[] getRotationMatrixFromOrientation(float[] orientation)
   //-------------------------------------------------------------------
   {


      float sinX = QuickFloat.sin(orientation[1]);
      float cosX = QuickFloat.cos(orientation[1]);
      float sinY = QuickFloat.sin(orientation[2]);
      float cosY = QuickFloat.cos(orientation[2]);
      float sinZ = QuickFloat.sin(orientation[0]);
      float cosZ = QuickFloat.cos(orientation[0]);

      // rotation about x-axis (pitch)
      xM[0] = 1.0f;
      xM[1] = 0.0f;
      xM[2] = 0.0f;
      xM[3] = 0.0f;
      xM[4] = cosX;
      xM[5] = sinX;
      xM[6] = 0.0f;
      xM[7] = -sinX;
      xM[8] = cosX;

      // rotation about y-axis (roll)
      yM[0] = cosY;
      yM[1] = 0.0f;
      yM[2] = sinY;
      yM[3] = 0.0f;
      yM[4] = 1.0f;
      yM[5] = 0.0f;
      yM[6] = -sinY;
      yM[7] = 0.0f;
      yM[8] = cosY;

      // rotation about z-axis (azimuth)
      zM[0] = cosZ;
      zM[1] = sinZ;
      zM[2] = 0.0f;
      zM[3] = -sinZ;
      zM[4] = cosZ;
      zM[5] = 0.0f;
      zM[6] = 0.0f;
      zM[7] = 0.0f;
      zM[8] = 1.0f;

      // Build the composite rotation... rotation order is y, x, z (roll,
      // pitch, azimuth)
      rotationFromOrientMatrix = matrixMultiplication(xM, yM);
      rotationFromOrientMatrix = matrixMultiplication(zM, rotationFromOrientMatrix);
//      Matrix.multiplyMV(rotationFromOrientMatrix, 0, xM, 0, yM, 0);
//      Matrix.multiplyMV(rotationFromOrientMatrix, 0, zM, 0, rotationFromOrientMatrix, 0);
      return rotationFromOrientMatrix;
   }

   /**
    * Multiply A by B.
    *
    * @param A
    * @param B
    * @return A*B
    */
   private float[] matrixMultiplication(float[] A, float[] B)
   {
      float[] result = new float[9];

      result[0] = A[0] * B[0] + A[1] * B[3] + A[2] * B[6];
      result[1] = A[0] * B[1] + A[1] * B[4] + A[2] * B[7];
      result[2] = A[0] * B[2] + A[1] * B[5] + A[2] * B[8];

      result[3] = A[3] * B[0] + A[4] * B[3] + A[5] * B[6];
      result[4] = A[3] * B[1] + A[4] * B[4] + A[5] * B[7];
      result[5] = A[3] * B[2] + A[4] * B[5] + A[5] * B[8];

      result[6] = A[6] * B[0] + A[7] * B[3] + A[8] * B[6];
      result[7] = A[6] * B[1] + A[7] * B[4] + A[8] * B[7];
      result[8] = A[6] * B[2] + A[7] * B[5] + A[8] * B[8];

      return result;
   }
}
