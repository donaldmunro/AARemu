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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

class OrientationQueuedCallbackThread implements Runnable, Stoppable, Latcheable
//===============================================================================
{
   final static private String TAG = "free/" + OrientationQueuedCallbackThread.class.getSimpleName();

   private CountDownLatch startLatch;
   public void setLatch(CountDownLatch latch) { startLatch = latch; }

   private final ConcurrentLinkedQueue<Long> timestampQueue;

   private final OrientationListenable orientationListener;

   private final File orientationFile;

   volatile private boolean isStop = false, isStarted = false;

   @Override public boolean isStarted() { return isStarted; }

   public void stop() { isStop = true; }

   public OrientationQueuedCallbackThread(File f, CountDownLatch startLatch,
                                          ConcurrentLinkedQueue<Long> timestampQueue,
                                          OrientationListenable orientationListener)
   //--------------------------------------------------------------------------------------------------
   {
      this.startLatch = startLatch;
      this.timestampQueue = timestampQueue;
      this.orientationListener = orientationListener;
      this.orientationFile = f;
   }

   public OrientationQueuedCallbackThread(File f, ConcurrentLinkedQueue<Long> timestampQueue,
                                          OrientationListenable orientationListener)
   //--------------------------------------------------------------------------------------------------
   {
      this.startLatch = null;
      this.timestampQueue = timestampQueue;
      this.orientationListener = orientationListener;
      this.orientationFile = f;
   }

   @Override
   public void run()
   //---------------
   {
      startLatch.countDown();
      try { startLatch.await(); } catch (InterruptedException e) { return; }
      long startTime;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
         startTime = SystemClock.elapsedRealtimeNanos();
      else
         startTime = System.nanoTime();
      Long nextTimestamp = -1L;
      long timestamp = 0, lastTimestamp = 0;
      DataInputStream dis = null;
      try
      {
         dis = new DataInputStream(new BufferedInputStream(new FileInputStream(orientationFile), 32768));
         float x, y, z, w;
         Quaternion Q = null;
         int rlen;
         float[] R = null;
         try
         {
            timestamp = dis.readLong();
            x = dis.readFloat();
            y = dis.readFloat();
            z = dis.readFloat();
            w = dis.readFloat();
            Q = new Quaternion(x, y, z, w);
            rlen = dis.readInt();
            R = new float[rlen];
            for (int i = 0; i < rlen; i++)
               R[i] = dis.readFloat();
         }
         catch (EOFException e)
         {
            isStop = true;
         }
         isStarted = true;
         byte[] ab = null;
         int alen = 0;
         while (! isStop)
         {
            nextTimestamp = timestampQueue.poll();
            if (nextTimestamp == null)
            {
               Thread.sleep(10);
               continue;
            }
            while ((timestamp < nextTimestamp) && (!isStop))
            {
               long processStart;
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
                  FloatBuffer fb = ByteBuffer.wrap(ab).asFloatBuffer();
                  fb.rewind();
                  fb.get(R);
               }
               catch (EOFException e)
               {
                  break;
               }
               catch (Exception e)
               {
                  Log.e(TAG, "", e);
                  throw new RuntimeException(e);
               }

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
                     Long L = timestampQueue.poll();
                     if (L != null)
                        nextTimestamp = L; // Slightly naughty - changes outer loop condition value
                     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                        now = SystemClock.elapsedRealtimeNanos();
                     else
                        now = System.nanoTime();
                  }
//                  Thread.sleep(timediff);
               }
            }
         }
      }
      catch (IOException e)
      {
         Log.e(TAG, "", e);
      }
//      catch (InterruptedException e)
//      {
//         isStop = true;
//      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         throw new RuntimeException(e);
      }
      finally
      {
         if (dis != null)
            try { dis.close(); } catch (Exception _e) {}
      }
      Log.d(TAG, "Last Orientation timestamp read " + timestamp + " " +  nextTimestamp);
      isStarted = false;
   }
}
