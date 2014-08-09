/**
 * Copyright 2010 JogAmp Community. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 *    1. Redistributions of source code must retain the above copyright notice, this list of
 *       conditions and the following disclaimer.
 *
 *    2. Redistributions in binary form must reproduce the above copyright notice, this list
 *       of conditions and the following disclaimer in the documentation and/or other materials
 *       provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY JogAmp Community ``AS IS'' AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL JogAmp Community OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * The views and conclusions contained in the software and documentation are those of the
 * authors and should not be interpreted as representing official policies, either expressed
 * or implied, of JogAmp Community.
 *
 * Some missing functionality added by Donald Munro for AARemu.
 */
package to.augmented.reality.android.common.math;

public class Quaternion implements Cloneable
//==========================================
{
   protected float x, y, z, w;

   public Quaternion()
   //-----------------
   {
      this.x = 0;
      this.y = 0;
      this.z = 0;
      this.w = 1;
   }

   public Quaternion(float x, float y, float z, float w)
   //---------------------------------------------------
   {
      this.x = x;
      this.y = y;
      this.z = z;
      this.w = w;
   }

   public Quaternion(Quaternion q)
   //-----------------------------
   {
      this.x = q.x;
      this.y = q.y;
      this.z = q.z;
      this.w = q.w;
   }

   /**
    * Constructor to create a rotation based quaternion from two vectors
    *
    * @param vector1
    * @param vector2
    */
   public Quaternion(float[] vector1, float[] vector2)
   //--------------------------------------------------
   {
      float theta = QuickFloat.acos(dot(vector1, vector2));
      float[] cross = cross(vector1, vector2);
      cross = normalizeVec(cross);

      this.x = QuickFloat.sin(theta / 2) * cross[0];
      this.y = QuickFloat.sin(theta / 2) * cross[1];
      this.z = QuickFloat.sin(theta / 2) * cross[2];
      this.w = QuickFloat.cos(theta / 2);
      this.normalize();
   }

   /**
    * Transform the rotational quaternion to axis based rotation angles
    *
    * @return new float[4] with ,theta,Rx,Ry,Rz
    */
   public float[] toAxis()
   //---------------------
   {
      float[] vec = new float[4];
      float scale = QuickFloat.sqrt(x * x + y * y + z * z);
      vec[0] = QuickFloat.acos(w) * 2.0f;
      vec[1] = x / scale;
      vec[2] = y / scale;
      vec[3] = z / scale;
      return vec;
   }

   /**
    * Normalize a vector
    *
    * @param vector input vector
    * @return normalized vector
    */
   private float[] normalizeVec(float[] vector)
   {
      float[] newVector = new float[3];

      float d = QuickFloat.sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2]);
      if (d > 0.0f)
      {
         newVector[0] = vector[0] / d;
         newVector[1] = vector[1] / d;
         newVector[2] = vector[2] / d;
      }
      return newVector;
   }

   /**
    * compute the dot product of two points
    *
    * @param vec1 vector 1
    * @param vec2 vector 2
    * @return the dot product as float
    */
   private float dot(float[] vec1, float[] vec2)
   {
      return (vec1[0] * vec2[0] + vec1[1] * vec2[1] + vec1[2] * vec2[2]);
   }

   /**
    * cross product vec1 x vec2
    *
    * @param vec1 vector 1
    * @param vec2 vector 2
    * @return the resulting vector
    */
   private float[] cross(float[] vec1, float[] vec2)
   //-----------------------------------------------
   {
      float[] out = new float[3];

      out[0] = vec2[2] * vec1[1] - vec2[1] * vec1[2];
      out[1] = vec2[0] * vec1[2] - vec2[2] * vec1[0];
      out[2] = vec2[1] * vec1[0] - vec2[0] * vec1[1];

      return out;
   }

   public float getW()
   {
      return w;
   }

   public Quaternion setW(float w) { this.w = w;return this; }

   public float getX()
   {
      return x;
   }

   public Quaternion setX(float x) { this.x = x;return this; }

   public float getY()
   {
      return y;
   }

   public Quaternion setY(float y) { this.y = y; return this; }

   public float getZ()
   {
      return z;
   }

   public Quaternion setZ(float z) { this.z = z;return this; }

   public Quaternion setXYZW(float x, float y, float z, float w)
   //-----------------------------------------------------
   {
      this.x = x;
      this.y = y;
      this.z = z;
      this.w = w;
      return this;
   }

   public Quaternion setFrom(Quaternion q)
   //-------------------------------
   {
      this.x = q.x;
      this.y = q.y;
      this.z = q.z;
      this.w = q.w;
      return this;
   }
   /**
    * Add a quaternion
    *
    * @param q quaternion
    */
   public Quaternion add(Quaternion q)
   //---------------------------------
   {
      x += q.x;
      y += q.y;
      z += q.z;
      return this;
   }

   /**
    * Subtract a quaternion
    *
    * @param q quaternion
    */
   public Quaternion subtract(Quaternion q)
   //--------------------------------------
   {
      x -= q.x;
      y -= q.y;
      z -= q.z;
      return this;
   }

   /**
    * Divide a quaternion by a constant
    *
    * @param n a float to divide by
    */
   public Quaternion divide(float n)
   //-------------------------------
   {
      x /= n;
      y /= n;
      z /= n;
      return this;
   }

   /**
    * Multiply this quaternion by
    * the param quaternion
    *
    * @param other a quaternion to multiply with
    */
   public Quaternion multiply(Quaternion other)
   //------------------------------------------
   {
      final float newX = this.w * other.x + this.x * other.w + this.y * other.z - this.z * other.y;
      final float newY = this.w * other.y + this.y * other.w + this.z * other.x - this.x * other.z;
      final float newZ = this.w * other.z + this.z * other.w + this.x * other.y - this.y * other.x;
      final float newW = this.w * other.w - this.x * other.x - this.y * other.y - this.z * other.z;
      this.x = newX;
      this.y = newY;
      this.z = newZ;
      this.w = newW;
      return this;
   }

   /** Multiplies this quaternion with another one in the form of this = other * this
    *
    * @param other Quaternion to multiply with
    * @return This quaternion for chaining */
   public Quaternion multiplyLeft (Quaternion other)
   //----------------------------------------------
   {
      final float newX = other.w * this.x + other.x * this.w + other.y * this.z - other.z * y;
      final float newY = other.w * this.y + other.y * this.w + other.z * this.x - other.x * z;
      final float newZ = other.w * this.z + other.z * this.w + other.x * this.y - other.y * x;
      final float newW = other.w * this.w - other.x * this.x - other.y * this.y - other.z * z;
      this.x = newX;
      this.y = newY;
      this.z = newZ;
      this.w = newW;
      return this;
   }

   /**
    * Multiply this quaternion by the input quaternion and store the result in the out quaternion
    *
    * @param other
    * @param result
    */
   public Quaternion multiply(Quaternion other, Quaternion result)
   //-------------------------------------------------------------
   {
      if (other == result)
      {
         final Quaternion Q = new Quaternion(this);
         Q.multiply(other);
         result.setFrom(Q);
      }
      else
      {
         result.setFrom(this);
         result.multiply(other);
      }
      return result;
   }

   /** Multiplies this quaternion with another one in the form of this = other * this
    *
    * @param other Quaternion to multiply with
    * @return This quaternion for chaining */
   public Quaternion multiplyLeft (Quaternion other, Quaternion result)
   //-----------------------------------------------------------------
   {
      if (other == result)
      {
         final Quaternion Q = new Quaternion(this);
         Q.multiplyLeft(other);
         result.setFrom(Q);
      }
      else
      {
         result.setFrom(this);
         result.multiplyLeft(other);
      }
      return result;
   }

   /**
    * Multiply a quaternion by a constant
    *
    * @param n a float constant
    */
   public Quaternion multiply(float n)
   //---------------------------------
   {
      x *= n;
      y *= n;
      z *= n;
      return this;
   }

   public float dotProduct(Quaternion other)
   {
      return this.x * other.x + this.y * other.y + this.z * other.z + this.w * other.w;
   }

   /**
    * Normalize a quaternion.
    */
   public Quaternion normalize()
   //---------------------------
   {
      final float normal = QuickFloat.sqrt(w * w + x * x + y * y + z * z);
      if (normal == 0.0f)
      {
         w = 1.0f;
         x = y = z = 0.0f;
      } else
      {
         final float recip = 1.0f / normal;
         w *= recip;
         x *= recip;
         y *= recip;
         z *= recip;
      }
      return this;
   }

   /** @return the euclidian length of the specified quaternion */
   public final float length() { return QuickFloat.sqrt(x * x + y * y + z * z + w * w); }

   /**
    * Invert the quaternion If rotational,
    * will produce a the inverse rotation
    */
   public Quaternion inverse()
   //-------------------------
   {
      final float norm = w * w + x * x + y * y + z * z;
      final float recip = 1.0f / norm;

      w *= recip;
      x = -1 * x * recip;
      y = -1 * y * recip;
      z = -1 * z * recip;
      return this;
   }

   /** Conjugate this quaternion.
    *
    * @return This quaternion for chaining */
   public Quaternion conjugate()
   //----------------------------
   {
      x = -x;
      y = -y;
      z = -z;
      return this;
   }

   /** Conjugate the quaternion and put the result in the result quaternion parameter
    *
    * @param result A quaternion into which the result is placed. If null then a new Quaternion will be allocated.
    * @return The output quaternion.
    * */
   public Quaternion conjugate(Quaternion result)
   //--------------------------------------------
   {
      if (result == null)
         result = new Quaternion();
      result.x = -x;
      result.y = -y;
      result.z = -z;
      result.w =  w;
      return result;
   }

   /**
    * Build this quaternion from two direction vectors. This quaternion can then transform one of the directions into the other.
    */
   public Quaternion fromRotation(final float[] src, final float[] dst)
   //------------------------------------------------------------------
   {
      float magnitude = QuickFloat.sqrt(QuickFloat.lenSquare(src) * QuickFloat.lenSquare(dst));
      float real_part = magnitude + dot(src, dst);
      float[] imaginary_part;

      if (real_part < 1.e-6f * magnitude)
      {
        /* If src and dst are exactly opposite, rotate 180 degrees
         * around an arbitrary orthogonal axis. Axis normalisation
         * can happen later, when we normalise the quaternion. */
         real_part = 0.0f;
         imaginary_part = (QuickFloat.abs(src[0]) > QuickFloat.abs(src[2]))
             ? new float[] { -src[1], src[0], 0.f }
             : new float[] {0.f, -src[2], src[1] };
      }
      else // Otherwise, build quaternion the standard way.
         imaginary_part = cross(src, dst);
      x = imaginary_part[0];
      y = imaginary_part[1];
      z = imaginary_part[2];
      w = real_part;
//      return normalize(quat(real_part, w.x, w.y, w.z));
      return this;
   }

   public Quaternion fromRotation(final float radians, final float[] v)
   //------------------------------------------------------------------
{
   float half_angle = radians * 0.5f;
   float[] tmp = new float[v.length];
   for (int i=0; i<v.length; i++)
      tmp[i] = v[i];
   QuickFloat.vecNormalize(tmp, 0, tmp.length);
   QuickFloat.vecScalarMultiply(tmp, 0, tmp.length, QuickFloat.sin(half_angle));
   this.x = tmp[0];
   this.y = tmp[1];
   this.z = tmp[2];
   this.w = QuickFloat.cos(half_angle);
   return this;
}

   public Quaternion fromRotation(float radians, float x, float y, float z) { return fromRotation(radians,
                                                                                                  new float[]{x, y, z}); }


   public float[] rotate(float[] v, float[] result)
   //----------------------------------------------
   {
      Quaternion Q1 = new Quaternion(), Q2 = new Quaternion();
      Q1.setXYZW(v[0], v[1], v[2], 0.0f);
      conjugate(Q2);
      Q1.multiply(Q2);
      this.multiply(Q1, Q2);
      if (result == null)
         result = new float[v.length];
      result[0] = Q2.x;
      result[1] = Q2.y;
      result[2] = Q2.z;
      return result;
   }

   public Quaternion fromEuler(final float yawRadians, final float pitchRadians, final float rollRadians)
   //-------------------------------------------------------------------------------
   {
      final float hr = rollRadians * 0.5f;
      final float shr = QuickFloat.sin(hr);
      final float chr = QuickFloat.cos(hr);
      final float hp = pitchRadians * 0.5f;
      final float shp = QuickFloat.sin(hp);
      final float chp = QuickFloat.cos(hp);
      final float hy = yawRadians * 0.5f;
      final float shy = QuickFloat.sin(hy);
      final float chy = QuickFloat.cos(hy);
      final float chy_shp = chy * shp;
      final float shy_chp = shy * chp;
      final float chy_chp = chy * chp;
      final float shy_shp = shy * shp;

      x = (chy_shp * chr) + (shy_chp * shr);
      y = (shy_chp * chr) - (chy_shp * shr);
      z = (chy_chp * shr) - (shy_shp * chr);
      w = (chy_chp * chr) + (shy_shp * shr);
      return this;
   }

   public int getGimbalPole()
   //------------------------
   {
      final float t = y * x + z * w;
      return t > 0.499f ? 1 : (t < -0.499f ? -1 : 0);
   }


   /**
    * Get the roll euler angle in radians, which is the rotation around the z axis.
    * Requires that this quaternion is normalized.
    *
    * @return the rotation around the z axis in radians (between -PI and +PI)
    */
   public float getRoll()
   //--------------------
   {
      final int pole = getGimbalPole();
      return (pole == 0)
             ? QuickFloat.atan2(2f * (w * z + y * x), 1f - 2f * (x * x + z * z))
             : pole * 2f * QuickFloat.atan2(y, w);
   }

   /**
    * Get the pitch euler angle in radians, which is the rotation around the x axis.
    * Requires that this quaternion is normalized.
    *
    * @return the rotation around the x axis in radians (between -(PI/2) and +(PI/2))
    */
   public float getPitch()
   //---------------------
   {
      final int pole = getGimbalPole();
      return (pole == 0)
             ? QuickFloat.asin(2f * (w * x - z * y))
             : pole * QuickFloat.HALF_PI;
   }

   /**
    * Get the yaw euler angle in radians, which is the rotation around the y axis.
    * Requires that this quaternion is normalized.
    *
    * @return the rotation around the y axis in radians (between -PI and +PI)
    */
   public float getYaw()
   //-------------------
   {
      return (getGimbalPole() == 0)
             ? QuickFloat.atan2(2f * (y * w + x * z), 1f - 2f * (y * y + x * x))
             : 0f;
   }

   /**
    * Derive this quaternion from provided X, Y and Z axes.
    */
   public Quaternion fromAxes(boolean normalizeAxes, float Xx, float Xy, float Xz, float Yx, float Yy, float Yz,
                              float Zx, float Zy, float Zz)
   //----------------------------------------------------------------------------------------------------------
   {
      if (normalizeAxes)
      {
         final float lx = 1f / QuickFloat.len(Xx, Xy, Xz);
         final float ly = 1f / QuickFloat.len(Yx, Yy, Yz);
         final float lz = 1f / QuickFloat.len(Zx, Zy, Zz);
         Xx *= lx;
         Xy *= lx;
         Xz *= lx;
         Yz *= ly;
         Yy *= ly;
         Yz *= ly;
         Zx *= lz;
         Zy *= lz;
         Zz *= lz;
      }

      final float t = Xx + Yy + Zz;
      // we protect the division by s by ensuring that s>=1
      if (t >= 0)
      { // |w| >= .5
         float s = QuickFloat.sqrt(t + 1); // |s|>=1 ...
         w = 0.5f * s;
         s = 0.5f / s; // so this division isn't bad
         x = (Zy - Yz) * s;
         y = (Xz - Zx) * s;
         z = (Yx - Xy) * s;
      } else if ((Xx > Yy) && (Xx > Zz))
      {
         float s = QuickFloat.sqrt(1.0f + Xx - Yy - Zz); // |s|>=1
         x = s * 0.5f; // |x| >= .5
         s = 0.5f / s;
         y = (Yx + Xy) * s;
         z = (Xz + Zx) * s;
         w = (Zy - Yz) * s;
      } else if (Yy > Zz)
      {
         float s = QuickFloat.sqrt(1.0f + Yy - Xx - Zz); // |s|>=1
         y = s * 0.5f; // |y| >= .5
         s = 0.5f / s;
         x = (Yx + Xy) * s;
         z = (Zy + Yz) * s;
         w = (Xz - Zx) * s;
      } else
      {
         float s = QuickFloat.sqrt(1.0f + Zz - Xx - Yy); // |s|>=1
         z = s * 0.5f; // |z| >= .5
         s = 0.5f / s;
         x = (Xz + Zx) * s;
         y = (Zy + Yz) * s;
         w = (Yx - Xy) * s;
      }
      return this;
   }

   /**
    * Transform this quaternion to a
    * 4x4 column matrix representing the rotation
    *
    * @return new float[16] column matrix 4x4
    */
   public float[] toMatrix()
   //-----------------------
   {
      float[] matrix = new float[16];
      matrix[0] = 1.0f - 2 * y * y - 2 * z * z;
      matrix[1] = 2 * x * y + 2 * w * z;
      matrix[2] = 2 * x * z - 2 * w * y;
      matrix[3] = 0;

      matrix[4] = 2 * x * y - 2 * w * z;
      matrix[5] = 1.0f - 2 * x * x - 2 * z * z;
      matrix[6] = 2 * y * z + 2 * w * x;
      matrix[7] = 0;

      matrix[8] = 2 * x * z + 2 * w * y;
      matrix[9] = 2 * y * z - 2 * w * x;
      matrix[10] = 1.0f - 2 * x * x - 2 * y * y;
      matrix[11] = 0;

      matrix[12] = 0;
      matrix[13] = 0;
      matrix[14] = 0;
      matrix[15] = 1;
      return matrix;
   }

   public float[] toArray() { return new float[] { x, y, z, w }; }

   public float[] toAndroidQuaternion() { return new float[] { w, x, y, z }; }

   /**
    * Set this quaternion from a Spherical interpolation
    * of two param quaternion.
    *
    * @param a initial quaternion
    * @param b target quaternion
    * @param t float between 0 and 1 representing interp.
    */
   public Quaternion slerp(Quaternion a, Quaternion b, float t)
   //----------------------------------------------------------
   {
      float omega, cosom, sinom, sclp, sclq;
      cosom = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w;
      if ((1.0f + cosom) > QuickFloat.E)
      {
         if ((1.0f - cosom) > QuickFloat.E)
         {
            omega = QuickFloat.acos(cosom);
            sinom = QuickFloat.sin(omega);
            sclp = QuickFloat.sin((1.0f - t) * omega) / sinom;
            sclq = QuickFloat.sin(t * omega) / sinom;
         } else
         {
            sclp = 1.0f - t;
            sclq = t;
         }
         x = sclp * a.x + sclq * b.x;
         y = sclp * a.y + sclq * b.y;
         z = sclp * a.z + sclq * b.z;
         w = sclp * a.w + sclq * b.w;
      } else
      {
         x = -a.y;
         y = a.x;
         z = -a.w;
         w = a.z;
         sclp = QuickFloat.sin((1.0f - t) * QuickFloat.PI * 0.5f);
         sclq = QuickFloat.sin(t * QuickFloat.PI * 0.5f);
         x = sclp * a.x + sclq * b.x;
         y = sclp * a.y + sclq * b.y;
         z = sclp * a.z + sclq * b.z;
      }
      return this;
   }

   /**
    * Get a linear interpolation between this quaternion and the input quaternion, storing the result in the output
    * quaternion.
    *
    * @param input  The quaternion to be slerped with this quaternion.
    * @param result The quaternion to store the result in.
    * @param t      The ratio between the two quaternions where 0 <= t <= 1.0 . Increase value of t will bring rotation
    *               closer to the input quaternion.
    */
   public Quaternion slerpBetween(Quaternion input, Quaternion result, float t)
   //---------------------------------------------------------------------------
   {
      // Calculate angle between them.
      //double cosHalftheta = this.dotProduct(input);
      final Quaternion bufferQuat;
      float cosHalftheta = this.dotProduct(input);
      if (cosHalftheta < 0)
      {
         bufferQuat = new Quaternion();
         cosHalftheta = -cosHalftheta;
         bufferQuat.x = (-input.x);
         bufferQuat.y = (-input.y);
         bufferQuat.z = (-input.z);
         bufferQuat.w = (-input.w);
      } else
         bufferQuat = input;

      /**
       * if(dot < 0.95f){
       * double angle = Math.acos(dot);
       * double ratioA = Math.sin((1 - t) * angle);
       * double ratioB = Math.sin(t * angle);
       * double divisor = Math.sin(angle);
       *
       * //Calculate Quaternion
       * output.setW((float)((this.getW() * ratioA + input.getW() * ratioB)/divisor));
       * output.setX((float)((this.getX() * ratioA + input.getX() * ratioB)/divisor));
       * output.setY((float)((this.getY() * ratioA + input.getY() * ratioB)/divisor));
       * output.setZ((float)((this.getZ() * ratioA + input.getZ() * ratioB)/divisor));
       * }
       * else{
       * lerp(input, output, t);
       * }
       */
      // if qa=qb or qa=-qb then theta = 0 and we can return qa
      if (Math.abs(cosHalftheta) >= 1.0)
      {
         result.x = (this.x);
         result.y = (this.y);
         result.z = (this.z);
         result.w = (this.w);
      } else
      {
         float sinHalfTheta = QuickFloat.sqrt(1.0f - cosHalftheta * cosHalftheta);
         // if theta = 180 degrees then result is not fully defined
         // we could rotate around any axis normal to qa or qb
         //if(Math.abs(sinHalfTheta) < 0.001){
         //output.setW(this.getW() * 0.5f + input.getW() * 0.5f);
         //output.setX(this.getX() * 0.5f + input.getX() * 0.5f);
         //output.setY(this.getY() * 0.5f + input.getY() * 0.5f);
         //output.setZ(this.getZ() * 0.5f + input.getZ() * 0.5f);
         //  lerp(bufferQuat, output, t);
         //}
         //else{
         float halfTheta = QuickFloat.acos(cosHalftheta);

         float ratioA = QuickFloat.sin((1 - t) * halfTheta) / sinHalfTheta;
         float ratioB = QuickFloat.sin(t * halfTheta) / sinHalfTheta;

         //Calculate Quaternion
         result.w = ((w * ratioA + bufferQuat.w * ratioB));
         result.x = ((this.x * ratioA + bufferQuat.x * ratioB));
         result.y = ((this.y * ratioA + bufferQuat.y * ratioB));
         result.z = ((this.z * ratioA + bufferQuat.z * ratioB));
      }
      return result;
   }

   /**
    * Check if this quaternion is empty, ie (0,0,0,1)
    *
    * @return true if empty, false otherwise
    */
   public boolean isEmpty()
   //---------------------
   {
      if (w == 1 && x == 0 && y == 0 && z == 0)
         return true;
      return false;
   }

   /**
    * Check if this quaternion represents an identity
    * matrix, for rotation.
    *
    * @return true if it is an identity rep., false otherwise
    */
   public boolean isIdentity()
   {
      if (w == 0 && x == 0 && y == 0 && z == 0)
         return true;
      return false;
   }

   /**
    * compute the quaternion from a 3x3 column matrix
    *
    * @param m 3x3 column matrix
    */
   public void setFromMatrix(float[] m)
   //----------------------------------
   {
      float T = m[0] + m[4] + m[8] + 1;
      if (T > 0)
      {
         float S = 0.5f / QuickFloat.sqrt(T);
         w = 0.25f / S;
         x = (m[5] - m[7]) * S;
         y = (m[6] - m[2]) * S;
         z = (m[1] - m[3]) * S;
      } else
      {
         if ((m[0] > m[4]) & (m[0] > m[8]))
         {
            float S = QuickFloat.sqrt(1.0f + m[0] - m[4] - m[8]) * 2f; // S=4*qx
            w = (m[7] - m[5]) / S;
            x = 0.25f * S;
            y = (m[3] + m[1]) / S;
            z = (m[6] + m[2]) / S;
         } else if (m[4] > m[8])
         {
            float S = QuickFloat.sqrt(1.0f + m[4] - m[0] - m[8]) * 2f; // S=4*qy
            w = (m[6] - m[2]) / S;
            x = (m[3] + m[1]) / S;
            y = 0.25f * S;
            z = (m[7] + m[5]) / S;
         } else
         {
            float S = QuickFloat.sqrt(1.0f + m[8] - m[0] - m[4]) * 2f; // S=4*qz
            w = (m[3] - m[1]) / S;
            x = (m[6] + m[2]) / S;
            y = (m[7] + m[5]) / S;
            z = 0.25f * S;
         }
      }
   }

   /**
    * Check if the the 3x3 matrix (param) is in fact
    * an affine rotational matrix
    *
    * @param m 3x3 column matrix
    * @return true if representing a rotational matrix, false otherwise
    */
   public boolean isRotationMatrix(float[] m)
   {
      double epsilon = 0.01; // margin to allow for rounding errors
      if (Math.abs(m[0] * m[3] + m[3] * m[4] + m[6] * m[7]) > epsilon) return false;
      if (Math.abs(m[0] * m[2] + m[3] * m[5] + m[6] * m[8]) > epsilon) return false;
      if (Math.abs(m[1] * m[2] + m[4] * m[5] + m[7] * m[8]) > epsilon) return false;
      if (Math.abs(m[0] * m[0] + m[3] * m[3] + m[6] * m[6] - 1) > epsilon) return false;
      if (Math.abs(m[1] * m[1] + m[4] * m[4] + m[7] * m[7] - 1) > epsilon) return false;
      if (Math.abs(m[2] * m[2] + m[5] * m[5] + m[8] * m[8] - 1) > epsilon) return false;
      return (Math.abs(determinant(m) - 1) < epsilon);
   }

   private float determinant(float[] m)
   {
      return m[0] * m[4] * m[8] + m[3] * m[7] * m[2] + m[6] * m[1] * m[5] - m[0] * m[7] * m[5] - m[3] * m[1] * m[8] - m[6] * m[4] * m[2];
   }

   @Override
   /**
    * {@inheritDoc}
    */
   public boolean equals(Object other) { return equals(other, 0.0000001f); }

   public boolean equals(Object other, float epsilon)
   //------------------------------------------------
   {
      if (this == other) return true;
      if (other instanceof Quaternion)
      {
         final Quaternion q = (Quaternion) other;
         return ( QuickFloat.equals(x, q.x, epsilon) && QuickFloat.equals(y, q.y, epsilon) &&
                  QuickFloat.equals(z, q.z, epsilon) && QuickFloat.equals(w, q.w, epsilon) );
      }

      return false;
   }

   @Override
   public String toString () { return "( (" + x + ", " + y + ", " + z + "), " + w + " )"; }
}
