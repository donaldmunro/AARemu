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

package to.augmented.reality.android.em.three60;

import android.content.*;
import android.os.*;
import android.util.*;
import android.view.Surface;
import to.augmented.reality.android.common.sensor.orientation.*;
import to.augmented.reality.android.em.ARCamera;
import to.augmented.reality.android.em.Reviewable;
import to.augmented.reality.android.em.Stoppable;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Playback thread used when ARCamera.renderMode is GLSurfaceView.RENDERMODE_CONTINUOUSLY
 * @see ARCamera#setRenderMode
 */
public class ContinuousPlaybackThread360 extends PlaybackThread360 implements Runnable, Stoppable, Reviewable
//===========================================================================================================
{
   final static private String TAG = ContinuousPlaybackThread360.class.getSimpleName();

   public ContinuousPlaybackThread360(Context context, File framesFile, int bufferSize, float recordingIncrement,
                                      OrientationProvider.ORIENTATION_PROVIDER providerType, ARCamera.RecordFileFormat fileFormat,
                                      ArrayBlockingQueue<byte[]> bufferQueue, int fps,
                                      int previewWidth, int previewHeight, Surface previewSurface)
   //-----------------------------------------------------------------------------------------------
   {
      super(context, framesFile, bufferSize, recordingIncrement, providerType, fileFormat, bufferQueue, fps,
            previewWidth, previewHeight, previewSurface);
   }

   /**
    * Called between calling the preview previewCallback in order to reduce the frame rate approximately to the value specified
    * in Camera.Parameters. The default implementation sleeps for idleTimeNS nanoseconds. Note this is approximate as
    * invoking the sleep method has overhead too, the idle time should perhaps be reduced by about 1 to 1.5 milliseconds
    * to account for this.
    * @param idleTimeNS The time in nanoseconds in which the method should return.
    */
   protected void onIdle(long idleTimeNS)
   //------------------------------------
   {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
         SystemClock.sleep(idleTimeNS / 1000000L);
      else
         try { Thread.sleep(idleTimeNS / 1000000L); } catch (InterruptedException e) { stop(); }
   }

   @Override
   public void run()
   //---------------
   {
      if (! onSetupOrientationSensor())
         throw new RuntimeException("Error Initializing rotation sensor");
      if (startLatch != null)
      {
         startLatch.countDown();
         try { startLatch.await(); } catch (InterruptedException e) { return; }
      }
      mustStop = false;
      RandomAccessFile framesRAF = null;
      byte[] frameBuffer = null;
      float offset =-1;
      int fileOffset =-1;
      boolean isDirty = false;
//      int fps = camera.getFrameRate()/1000;
      long fpsInterval = -1L;
      if (fps > 0)
         fpsInterval = 1000000000L / fps;
      try
      {
         framesRAF = new RandomAccessFile(framesFile, "r");
         isStarted = true;
         long startTime, endTime;
         if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
            startTime = SystemClock.elapsedRealtimeNanos();
         else
            startTime = System.nanoTime();
         while (! mustStop)
         {
            currentBearing = bearing;
            if (currentBearing >= 0)
            {
               currentBearing = (float) (Math.rint(currentBearing * 10.0f) / 10.0);
               if (currentBearing >= 360)
                  currentBearing -= 360;
               if (((currentBearing <= startBearing) || (currentBearing > endBearing)) &&
                     (bufferQueue.peek() != null))
               {
                  try { frameBuffer = bufferQueue.take(); } catch (InterruptedException e) { break; }
                  offset = (float) (Math.floor(currentBearing / recordingIncrement) * recordingIncrement);
                  fileOffset = (int) (Math.floor(offset / recordingIncrement) * bufferSize);
                  framesRAF.seek(fileOffset);
                  try
                  {
                     framesRAF.readFully(frameBuffer);
                  }
                  catch (EOFException _e)
                  {
                     Arrays.fill(frameBuffer, (byte) 0);
                     Log.e(TAG, "Offset out of range: " + fileOffset + ", bearing was " + currentBearing, _e);
                  }
                  startBearing = offset;
                  endBearing = startBearing + recordingIncrement;
                  if (! isUseBuffer)
                     bufferQueue.add(frameBuffer);
                  isDirty = true;
               }
            }
            if (frameBuffer == null)
               continue;
            if (fps > 0)
            {
               if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                  endTime = SystemClock.elapsedRealtimeNanos();
               else
                  endTime = System.nanoTime();
               long dt = endTime - startTime;
               if (dt < fpsInterval)
                  onIdle((fpsInterval - dt)<<1);
               if (previewCallback != null)
               {
                  previewCallback.onPreviewFrame(frameBuffer, null);
                  if (surface != null)
                     super.drawToSurface(frameBuffer);
               }
               else
                  captureCallback.onPreviewFrame(frameBuffer);
               startTime = endTime;
            }
            else
            {
               if (previewCallback != null)
               {
                  previewCallback.onPreviewFrame(frameBuffer, null);
                  if (surface != null)
                     super.drawToSurface(frameBuffer);
               }
               else
                  captureCallback.onPreviewFrame(frameBuffer);
            }
         }
      }
      catch (Exception e)
      {
         Log.e(TAG, "PlaybackThread: bearing = " + currentBearing + " offset = " + offset + " file offset = " + fileOffset, e);
         throw new RuntimeException("PlaybackThread.run", e);
      }
      finally
      {
         if (framesRAF != null)
            try { framesRAF.close(); framesRAF = null; } catch (Exception e) {}
         isStarted = false;
      }
   }
}
