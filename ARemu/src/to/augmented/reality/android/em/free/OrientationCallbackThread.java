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

package to.augmented.reality.android.em.free;

import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import to.augmented.reality.android.common.math.Quaternion;
import to.augmented.reality.android.common.sensor.orientation.OrientationListenable;
import to.augmented.reality.android.em.Latcheable;
import to.augmented.reality.android.em.Stoppable;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;

class OrientationCallbackThread implements Runnable, Stoppable, Latcheable
//========================================================================
{
   final static private String TAG = "free/" + OrientationCallbackThread.class.getSimpleName();

   private CountDownLatch startLatch;
   public void setLatch(CountDownLatch latch) { startLatch = latch; }
   private final OrientationListenable orientationListener;
   private final File orientationFile;
   private int version =-1;
   volatile private boolean isStop = false, isStarted = false;

   public void stop() { isStop = true; }

   @Override public boolean isStarted() { return isStarted; }

   public OrientationCallbackThread(File f, CountDownLatch startLatch, OrientationListenable orientationListener, int version)
   //--------------------------------------------------------------------------------------------------
   {
      this.startLatch = startLatch;
      this.orientationListener = orientationListener;
      this.orientationFile = f;
      this.version = version;
   }

   public OrientationCallbackThread(File f, OrientationListenable orientationListener, int version)
   //--------------------------------------------------------------------------------------------------
   {
      this.orientationListener = orientationListener;
      this.orientationFile = f;
      this.version = version;
   }

   @Override
   public void run()
   //---------------
   {
      startLatch.countDown();
      try { startLatch.await(); } catch (InterruptedException e) { return; }
      long startTime, processStart, timestamp = 0, lastTimestamp = 0;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
         processStart = startTime = SystemClock.elapsedRealtimeNanos();
      else
         processStart = startTime = System.nanoTime();
      DataInputStream dis = null;
      try
      {
         dis = new DataInputStream(new BufferedInputStream(new FileInputStream(orientationFile), 32768));
         float x, y, z, w, bearing;
         Quaternion Q = null;
         int rlen;
         float[] R = null;
         try
         {
            lastTimestamp = timestamp = dis.readLong();
            x = dis.readFloat();
            y = dis.readFloat();
            z = dis.readFloat();
            w = dis.readFloat();
            Q = new Quaternion(x, y, z, w);
            rlen = dis.readInt();
            R = new float[rlen];
            for (int i = 0; i < rlen; i++)
               R[i] = dis.readFloat();
            if (version > 20)
               bearing = dis.readFloat();
         }
         catch (EOFException e)
         {
            isStop = true;
         }
         byte[] ab = null;
         int alen = 0;
         isStarted = true;
         while (!isStop)
         {
            long timediff;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
               timediff = (timestamp - lastTimestamp - (SystemClock.elapsedRealtimeNanos() - processStart) - 1000L); // / 1000000L;
            else
               timediff = (timestamp - lastTimestamp - (System.nanoTime() - processStart) - 1000L);// / 1000000L;
            long now, then;
            if ( (timediff > 0) && (! isStop) )
            {
               if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                  now = SystemClock.elapsedRealtimeNanos();
               else
                  now = System.nanoTime();
               then = now + timediff;
               while ( (then > now) && (! isStop) )
               {
                  Thread.yield();
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                     now = SystemClock.elapsedRealtimeNanos();
                  else
                     now = System.nanoTime();
               }
//                  Thread.sleep(timediff);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
               processStart = SystemClock.elapsedRealtimeNanos();
            else
               processStart = System.nanoTime();

            orientationListener.onOrientationListenerUpdate(R, Q, timestamp);

            try
            {
               lastTimestamp = timestamp;
               timestamp = dis.readLong();
               x = dis.readFloat();
               y = dis.readFloat();
               z = dis.readFloat();
               w = dis.readFloat();
               Q = new Quaternion(x, y, z, w);
               rlen = dis.readInt();
               if (rlen != alen)
               {
                  ab = new byte[(rlen*Float.SIZE)/8];
                  alen = rlen;
               }
               dis.readFully(ab);
               if (version > 20)
                  bearing = dis.readFloat();
               FloatBuffer fb = ByteBuffer.wrap(ab).asFloatBuffer();
               fb.rewind();
               fb.get(R);
            }
            catch (EOFException e)
            {
               isStop = true;
            }
         }
      }
      catch (IOException e)
      {
         Log.e(TAG, "", e);
      }
      finally
      {
         if (dis != null)
            try { dis.close(); } catch (Exception _e) {}
      }
      isStarted = false;
   }
}
