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

import android.location.Location;
import android.location.LocationListener;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import to.augmented.reality.android.em.Latcheable;
import to.augmented.reality.android.em.LocationThread;
import to.augmented.reality.android.em.Stoppable;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;

class LocationQueuedCallbackThread implements Runnable, Stoppable, Latcheable
//===========================================================================
{
   final static private String TAG = "free/" + LocationQueuedCallbackThread.class.getSimpleName();

   private CountDownLatch startLatch;
   public void setLatch(CountDownLatch latch) { startLatch = latch; }
   private final ConcurrentLinkedQueue<Long> timestampQueue;
   private final LocationListener locationListener;
   private final File locationFile;
   volatile private boolean isStop = false, isStarted = false;

   @Override public boolean isStarted() { return isStarted; }

   public void stop() { isStop = true; }


   public LocationQueuedCallbackThread(File f, CountDownLatch startLatch, ConcurrentLinkedQueue<Long> timestampQueue,
                                       LocationListener locationListener)
   //--------------------------------------------------------------------------------------------------
   {
      this.startLatch = startLatch;
      this.timestampQueue = timestampQueue;
      this.locationListener = locationListener;
      this.locationFile = f;
   }

   public LocationQueuedCallbackThread(File f, ConcurrentLinkedQueue<Long> timestampQueue, LocationListener locationListener)
   //--------------------------------------------------------------------------------------------------
   {
      this.timestampQueue = timestampQueue;
      this.locationListener = locationListener;
      this.locationFile = f;
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
      long timestamp = 0, lastTimestamp = 0;
      try (DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(locationFile), 16384)))
      {
         Location location = null;
         try
         {
            timestamp =  dis.readLong();
            char ch = dis.readChar();
            boolean isGPSLocation = (ch == 'G');
            double latitude = dis.readDouble();
            double longitude = dis.readDouble();
            double altitude = dis.readDouble();
            float accuracy = dis.readFloat();
            location = LocationThread.createLocation(isGPSLocation, latitude, longitude, altitude, accuracy);
         }
         catch (Exception e)
         {
            isStop = true;
         }
         isStarted = true;
         while (!isStop)
         {
            Long nextTimestamp = timestampQueue.poll();
            if (nextTimestamp == null)
            {
               Thread.sleep(0, 100);
               continue;
            }
            while ((timestamp < nextTimestamp) && (!isStop))
            {
               long processStart;
               if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                  processStart = SystemClock.elapsedRealtimeNanos();
               else
                  processStart = System.nanoTime();

               locationListener.onLocationChanged(location);

               try
               {
                  lastTimestamp = timestamp;
                  timestamp =  dis.readLong();
                  char ch = dis.readChar();
                  boolean isGPSLocation = (ch == 'G');
                  double latitude = dis.readDouble();
                  double longitude = dis.readDouble();
                  double altitude = dis.readDouble();
                  float accuracy = dis.readFloat();
                  location = LocationThread.createLocation(isGPSLocation, latitude, longitude, altitude, accuracy);
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
                     Thread.sleep(20);
                     Long L = timestampQueue.poll();
                     if (L != null)
                        nextTimestamp = L;
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
      catch (InterruptedException e)
      {
         isStop = true;
      }
      isStarted = false;
   }
}
