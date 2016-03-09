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

package to.augmented.reality.android.common.math;

/**
 * Some float (as opposed to double functionality,
 */
public class QuickFloat
//======================
{
   public static final String LOGTAG = QuickFloat.class.getSimpleName();

   public static final float E = 2.7182818284590452354f;

   public static final float PI = 3.14159265358979323846f;

   public static final float TWO_PI = 6.2831853071795864769f;

   public static final float HALF_PI = 1.5707963267948966192f;

   public static final float QUARTER_PI = 0.78539816339744830961f;

   public static final float THREE_QUARTER_PI = 2.0943951023931953f;

   public static final float EPSILON_1 = 0.000000001f;

   public static float pow(float a, float b) { return (float) java.lang.Math.pow(a, b); }

   public static float sin(float a) { return (float) java.lang.Math.sin(a); }

   public static float cos(float a) { return (float) java.lang.Math.cos(a); }

   public static float tan(float a) { return (float) java.lang.Math.tan(a); }

   public static float acos(float a) { return (float) java.lang.Math.acos(a); }

   public static float sqrt(float a) { return (float) java.lang.Math.sqrt(a); }

   public static float log2(double a) { return (float) (Math.log(a) / Math.log(2)); }

   static public void vecAdd(float[] v1, float[] v2, int offset, int count, float[] result)
   //--------------------------------------------------------------------------------------
   {
      final int n = offset + count;
      for (int i=offset; i<n; i++)
         result[i] = v1[i] + v2[i];
   }

   static public void vecSubtract(float[] v1, float[] v2, int offset, int count, float[] result)
   //--------------------------------------------------------
   {
      final int n = offset + count;
      for (int i=offset; i<n; i++)
         result[i] = v1[i] - v2[i];
   }

   static public float vecNormal(float[] v, int offset, int count)
   //-------------------------------------------------------------
   {
      final int n = offset + count;
      float norm = 0;
      for (int i=offset; i<n; i++)
         norm += v[i] * v[i];
      return sqrt(norm);
   }

   static public void vecNormalize(float[] v, int offset, int count)
   //-------------------------------------------------------------------------------
   {
      final int n = offset + count;
      float norm = 0;
      for (int i=offset; i<n; i++)
         norm += v[i] * v[i];
      norm = sqrt(norm);
      for (int i=offset; i<n; i++)
         v[i] = v[i] / norm;
   }

   static public float vecDot(float[] v1, float[] v2, int offset, int count)
   //-----------------------------------------------------------------------------------
   {
      final int n = offset + count;
      float result = 0;
      for (int i=offset; i<n; i++)
         result += v1[i] * v2[i];
      return result;
   }

   static public final float[] vec3Cross(float[] v1, float[] v2, float[] result)
   //-------------------------------------------------------------
   {
      result[0] = v1[1]*v2[2] - v1[2]*v2[1];
      result[1] = v2[0]*v1[2] - v2[2]*v1[0];
      result[2] = v1[0]*v2[1] - v1[1]*v2[0];
      return result;
   }


   public static float atan2(float y, float x) { return (float) Math.atan2(y, x); }

   public static float abs(float x) { return (float) Math.abs(x); }

   public static boolean equals(float A, float B, float epsilon) { return (Math.abs(A - B) <= epsilon); }

   public static int compare(float x, float y, float epsilon)
   //--------------------------------------------------------
   {
      float diff = x - y;
      if (Math.abs(diff) <= epsilon)
         return 0;
      else
         return (int) Math.signum(diff);
   }

   public static float asin(float v) { return (float) Math.asin(v); }

   public static float len(float x, float y, float z) {  return sqrt(x*x + y*y + z*z); }

   public static float len(float[] v)
   //--------------------------------
   {
      float mag = 0;
      for (int i=0; i<v.length; i++)
         mag += v[i]*v[i];
      return sqrt(mag);
   }

   public static float lenSquare(float[] v)
   //--------------------------------
   {
      float mag = 0;
      for (int i=0; i<v.length; i++)
         mag += v[i]*v[i];
      return mag;
   }

   public static void vecScalarMultiply(float[] v, int offset, int count, float scalar)
   //----------------------------------------------------------------------------------
   {
      final int n = offset + count;
      for (int i=offset; i<n; i++)
         v[i] = v[i] * scalar;
   }
}
