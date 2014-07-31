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

package to.augmented.reality.android.em;

import android.content.*;
import android.hardware.*;
import android.os.*;
import android.util.*;
import to.augmented.reality.android.common.sensor.orientation.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Playback thread used when ARCamera.renderMode is GLSurfaceView.RENDERMODE_CONTINUOUSLY
 * @see ARCamera#setRenderMode
 */
public class ContinuousPlaybackThread extends PlaybackThread
//==========================================================
{
   final static private String TAG = ContinuousPlaybackThread.class.getSimpleName();

   public ContinuousPlaybackThread(Context context, ARCamera camera, File framesFile, int bufferSize,
                                   float recordingIncrement, Camera.PreviewCallback callback,
                                   boolean isOneShot, BearingListener bearingListener,
                                   ArrayBlockingQueue<byte[]> bufferQueue,
                                   OrientationProvider.ORIENTATION_PROVIDER providerType,
                                   ARCamera.RecordFileFormat fileFormat)
   //-----------------------------------------------------------------------------------------------
   {
      super(context, camera, framesFile, bufferSize, recordingIncrement, callback, isOneShot, bearingListener,
            bufferQueue, providerType, fileFormat);
   }

   /**
    * Called between calling the preview callback in order to reduce the frame rate approximately to the value specified
    * in Camera.Parameters. The default implementation sleeps for idleTimeNS nanoseconds. Note this is approximate as
    * invoking the sleep method has overhead too, the idle time should perhaps be reduced by about 1 to 1.5 milliseconds
    * to account for this.
    * @param idleTimeNS The time in nanoseconds in which the method should return.
    */
   protected void onIdle(long idleTimeNS)
   //------------------------------------
   {
      try { Thread.sleep(idleTimeNS / 1000000L); } catch (InterruptedException e) { mustStop = true; }
   }

   @Override
   public void run()
   //---------------
   {
      if (! onSetupOrientationSensor())
         throw new RuntimeException("Error Initializing rotation sensor");
      RandomAccessFile framesRAF = null;
      byte[] frameBuffer = null;
      float offset =-1;
      int fileOffset =-1;
      boolean isDirty = false;
      int fps = camera.getFrameRate()/1000;
      long fpsInterval = -1L;
      if (fps > 0)
         fpsInterval = 1000000000L / fps;
      try
      {
         framesRAF = new RandomAccessFile(framesFile, "r");
         isStarted = true;
         final boolean isUseBuffer = camera.isUseBuffer;
         if (! isUseBuffer)
         {  // If not using user defined buffers create an internal buffer of buffers
            if (bufferQueue == null)
               bufferQueue = new ArrayBlockingQueue<byte[]>(PREALLOCATED_BUFFERS);
            for (int i=0; i<PREALLOCATED_BUFFERS; i++)
               bufferQueue.add(new byte[bufferSize]);
         }
         long startTime = SystemClock.elapsedRealtimeNanos(), endTime;
         while (! mustStop)
         {
            currentBearing = bearing;
            if (currentBearing < 0)
            {
               try { Thread.sleep(100); } catch (Exception _e) {}
               continue;
            }
            currentBearing = (float) (Math.rint(currentBearing*10.0f)/10.0);
            if (currentBearing >= 360)
               currentBearing = 0;
            if ( ( (currentBearing <= startBearing) || (currentBearing > endBearing) ) &&
                  (bufferQueue.peek() != null) )
            {
               frameBuffer = bufferQueue.poll();
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
            if (fps > 0)
            {
               endTime = SystemClock.elapsedRealtimeNanos();
               long dt = endTime - startTime;
               if (dt < fpsInterval)
                  onIdle(dt);
               callback.onPreviewFrame(frameBuffer, null);
               startTime = endTime;
            }
            else
               callback.onPreviewFrame(frameBuffer, null);

            if (isOneShot)
            {
               camera.stopPreview();
               break;
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
