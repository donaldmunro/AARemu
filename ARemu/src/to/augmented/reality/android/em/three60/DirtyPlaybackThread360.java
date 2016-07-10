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
import to.augmented.reality.android.common.sensor.orientation.*;
import to.augmented.reality.android.em.ARCamera;
import to.augmented.reality.android.em.Reviewable;
import to.augmented.reality.android.em.Stoppable;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Playback thread used when ARCamera.renderMode is GLSurfaceView.RENDERMODE_WHEN_DIRTY
 * @see ARCamera#setRenderMode
 */
public class DirtyPlaybackThread360 extends PlaybackThread360 implements Runnable, Stoppable, Reviewable
//======================================================================================================
{
   final static private String TAG = DirtyPlaybackThread360.class.getSimpleName();

   public DirtyPlaybackThread360(Context context, File framesFile, int bufferSize, float recordingIncrement,
                                 OrientationProvider.ORIENTATION_PROVIDER providerType, ARCamera.RecordFileFormat fileFormat)
   //-------------------------------------------------------------------------------------------
   {
      super(context, framesFile, bufferSize, recordingIncrement, providerType, fileFormat, null, -1);
      bearingAvailCondVar = new ConditionVariable(false);
   }

   public DirtyPlaybackThread360(Context context, File framesFile, int bufferSize, float recordingIncrement,
                                 OrientationProvider.ORIENTATION_PROVIDER providerType, ARCamera.RecordFileFormat fileFormat,
                                 ArrayBlockingQueue<byte[]> bufferQueue, int fps)
   //-------------------------------------------------------------------------------------------
   {
      super(context, framesFile, bufferSize, recordingIncrement, providerType, fileFormat, bufferQueue, fps);
      bearingAvailCondVar = new ConditionVariable(false);
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
      try
      {
         framesRAF = new RandomAccessFile(framesFile, "r");
         isStarted = true;
         while (! mustStop)
         {
            if (! bearingAvailCondVar.block(300))
               continue;
            bearingAvailCondVar.close();
            currentBearing = bearing;
            if (currentBearing < 0)
            {
               try { Thread.sleep(100); } catch (Exception _e) {}
               continue;
            }
            currentBearing = (float) (Math.rint(currentBearing*10.0f)/10.0);
            if (currentBearing >= 360)
               currentBearing -= 360;
            if ( ( (currentBearing <= startBearing) || (currentBearing > endBearing) ) &&
                   (! bufferQueue.isEmpty()) )
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
               if (previewCallback != null)
                  previewCallback.onPreviewFrame(frameBuffer, null);
               else
                  captureCallback.onPreviewFrame(frameBuffer);
               if (! isUseBuffer)
                  bufferQueue.add(frameBuffer);
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
         mustStop = true;
      }
   }
}
