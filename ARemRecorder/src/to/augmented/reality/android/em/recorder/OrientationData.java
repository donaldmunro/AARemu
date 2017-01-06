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

import android.hardware.SensorManager;
import to.augmented.reality.android.common.math.Quaternion;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

public class OrientationData
//=======================
{
   long timestamp = 0;
   Quaternion Q = null;
   float[] R = null;
   float bearing = -1.0f;
   public void resetBearing() { bearing = -1; }

   OrientationData() { }

   public OrientationData(long timestamp, Quaternion Q, float[] R)
   //-------------------------------------------------------------
   {
      this.timestamp = timestamp;
      this.Q = Q;
      this.R = R;
   }

   public int size() { return (Long.SIZE + Float.SIZE*(4 + R.length) + Integer.SIZE)/8; }

   static public OrientationData read(DataInputStream dis, long[] recordLen) throws IOException
   //-------------------------------------------------------------------------------------------
   {
      try
      {
         long timestamp = dis.readLong();
         float x = dis.readFloat();
         float y = dis.readFloat();
         float z = dis.readFloat();
         float w = dis.readFloat();
         Quaternion Q = new Quaternion(x, y, z, w);
         int rlen = dis.readInt();
         float[] R = new float[rlen];
         for (int i = 0; i < rlen; i++)
            R[i] = dis.readFloat();
         float bearing = dis.readFloat();
         OrientationData data = new OrientationData(timestamp, Q, R);
         recordLen[0] = (Long.SIZE + Float.SIZE*(5 + rlen) + Integer.SIZE) / 8;
         return data;
      }
      catch (EOFException e)
      {
         return null;
      }
   }

   public OrientationData(OrientationData other)
   //-----------------------------------------------
   {
      timestamp = other.timestamp;
      Q = new Quaternion(other.Q);
      R = Arrays.copyOf(other.R, other.R.length);
   }

   public void set(long timestamp, Quaternion Q, float[] R)
   //-----------------------------------------------------
   {
      this.timestamp = timestamp;
      this.Q = new Quaternion(Q);
      this.R = Arrays.copyOf(R, R.length);
   }

   public void write(DataOutputStream orientationWriter, float bearing) throws IOException
   //----------------------------------------------------------------------
   {
      orientationWriter.writeLong(timestamp);
      orientationWriter.writeFloat(Q.getX());
      orientationWriter.writeFloat(Q.getY());
      orientationWriter.writeFloat(Q.getZ());
      orientationWriter.writeFloat(Q.getW());
      orientationWriter.writeInt(R.length);
      for (int i=0; i<R.length; i++)
         orientationWriter.writeFloat(R[i]);
      orientationWriter.writeFloat((bearing < 0) ? bearing() : bearing);
   }

   public float bearing()
   //--------------------
   {
      if (bearing >= 0) return bearing;

      float[] RM = new float[16];
      int REMAP_X = SensorManager.AXIS_X,  REMAP_Y = SensorManager.AXIS_Z;
      SensorManager.remapCoordinateSystem(R, REMAP_X, REMAP_Y, RM);
//      float[] orientation = new float[3];
//      SensorManager.getOrientation(RM, orientation);
      bearing = (float) Math.toDegrees(Math.atan2(RM[1], RM[5]));
      if (bearing < 0)
         bearing += 360;

//      Log.i("OrientationData", "AXIS_Z " + bearing);
//      REMAP_Y = SensorManager.AXIS_MINUS_Z;
//      SensorManager.remapCoordinateSystem(R, REMAP_X, REMAP_Y, RM);
//      float bearing2 = (float) Math.toDegrees(Math.atan2(RM[1], RM[5]));
//      if (bearing2 < 0)
//         bearing2 += 360;
//      Log.i("OrientationData", "AXIS_MINUS_Z " + bearing2);
//      REMAP_Y = SensorManager.AXIS_MINUS_Y;
//      SensorManager.remapCoordinateSystem(R, REMAP_X, REMAP_Y, RM);
//      bearing2 = (float) Math.toDegrees(Math.atan2(RM[1], RM[5]));
//      if (bearing2 < 0)
//         bearing2 += 360;
//      Log.i("OrientationData", "AXIS_MINUS_Y " + bearing2);
//      REMAP_Y = SensorManager.AXIS_Y;
//      SensorManager.remapCoordinateSystem(R, REMAP_X, REMAP_Y, RM);
//      bearing2 = (float) Math.toDegrees(Math.atan2(RM[1], RM[5]));
//      if (bearing2 < 0)
//         bearing2 += 360;
//      Log.i("OrientationData", "AXIS_Y " + bearing2);

      return bearing;
   }
}
