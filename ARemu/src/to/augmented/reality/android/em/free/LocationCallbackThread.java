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
import java.util.concurrent.CountDownLatch;

class LocationCallbackThread implements Runnable, Stoppable, Latcheable
//======================================================================
{
   final static private String TAG = "free/" + LocationCallbackThread.class.getSimpleName();

   private CountDownLatch startLatch;
   public void setLatch(CountDownLatch latch) { startLatch = latch; }
   private final LocationListener locationListener;
   private final File locationFile;
   volatile private boolean isStop = false, isStarted = false;

   @Override public boolean isStarted() { return isStarted; }

   public void stop() { isStop = true; }

   public LocationCallbackThread(File f, CountDownLatch startLatch, LocationListener locationListener)
   //--------------------------------------------------------------------------------------------------
   {
      this.startLatch = startLatch;
      this.locationListener = locationListener;
      this.locationFile = f;
   }

   public LocationCallbackThread(File f, LocationListener locationListener)
   //----------------------------------------------------------------------
   {
      this.startLatch = startLatch;
      this.locationListener = locationListener;
      this.locationFile = f;
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
         dis = new DataInputStream(new BufferedInputStream(new FileInputStream(locationFile), 16384));
         Location location = null;
         try
         {
            lastTimestamp = timestamp =  dis.readLong();
            char provider = (char) (dis.readByte() & 0xFF);
            boolean isGPSLocation = (provider == 'G');
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

            locationListener.onLocationChanged(location);

            try
            {
               lastTimestamp = timestamp;
               timestamp =  dis.readLong();
               char provider = (char) (dis.readByte() & 0xFF);
               boolean isGPSLocation = (provider == 'G');
               double latitude = dis.readDouble();
               double longitude = dis.readDouble();
               double altitude = dis.readDouble();
               float accuracy = dis.readFloat();
               location = LocationThread.createLocation(isGPSLocation, latitude, longitude, altitude, accuracy);
            }
            catch (EOFException e)
            {
               isStop = true;
            }
         }
      }
      catch (Exception e)
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
