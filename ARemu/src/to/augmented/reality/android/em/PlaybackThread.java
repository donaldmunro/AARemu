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

import android.app.Activity;
import android.content.Context;
import android.hardware.Camera;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.ConditionVariable;
import android.util.Log;
import to.augmented.reality.android.common.math.*;
import to.augmented.reality.android.common.sensor.orientation.*;

import java.io.File;
import java.util.concurrent.*;

import static to.augmented.reality.android.common.sensor.orientation.OrientationProvider.ORIENTATION_PROVIDER;

/**
 * Base class for playback threads. Review API is also implemented here.
 */
abstract public class PlaybackThread implements Runnable
//======================================================
{
   final static private String TAG = PlaybackThread.class.getSimpleName();
   final static protected int PREALLOCATED_BUFFERS = 3;
   final static int REMAP_X = SensorManager.AXIS_X,  REMAP_Y = SensorManager.AXIS_Z;

   volatile protected boolean mustStop = false;

   final Context context;
   final protected int bufferSize;
   final protected float recordingIncrement;
   protected final Camera.PreviewCallback callback;
   protected final boolean isOneShot;
   protected final ARCamera camera;
   protected ConditionVariable bearingAvailCondVar = null;
   protected final BearingListener bearingListener;
   protected ArrayBlockingQueue<byte[]> bufferQueue;

   protected ORIENTATION_PROVIDER providerType;
   OrientationProvider orientationProvider = null;
   protected ARCamera.RecordFileFormat fileFormat = ARCamera.RecordFileFormat.RGB;

   volatile protected float bearing = -1;

   float startBearing = Float.MAX_VALUE, endBearing = -1, currentBearing =-1;

   protected File framesFile = null;
   final long fileLen;
   private SensorEventListener rotationListener;
   protected boolean isStarted = false;

   volatile protected boolean isReview = false;
   final private ExecutorService reviewExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
   {
      @Override
      public Thread newThread(Runnable r)
      //---------------------------------
      {
         Thread t = new Thread(r);
         t.setDaemon(true);
         t.setName("Preview");
         t.setPriority(Thread.NORM_PRIORITY);
         return t;
      }
   });
   private Future<?> reviewFuture;
   private int reviewPause = 0;

   public PlaybackThread(Context context, ARCamera camera, File framesFile, int bufferSize, float recordingIncrement,
                         Camera.PreviewCallback callback, boolean isOneShot,
                         BearingListener bearingListener, ArrayBlockingQueue<byte[]> bufferQueue,
                         ORIENTATION_PROVIDER providerType, ARCamera.RecordFileFormat fileFormat)
   //----------------------------------------------------------------------------------------------
   {
      this.context = context;
      this.camera = camera;
      this.fileLen = framesFile.length();
      this.framesFile = framesFile;
      this.bufferSize = bufferSize;
      this.recordingIncrement = (float) (Math.rint(recordingIncrement*10.0f)/10.0);
      this.callback = callback;
      if (callback == null)
         throw new RuntimeException("callback cannot be null");
      this.isOneShot = isOneShot;
      this.bearingListener = bearingListener;
      this.bufferQueue = bufferQueue;
      this.providerType = providerType;
      this.fileFormat = fileFormat;
   }

   @Override abstract public void run();

   public boolean isStarted() { return isStarted; }

   public void stop()
   //----------------
   {
      mustStop = true;
      if (bearingAvailCondVar != null)
         bearingAvailCondVar.open();
   }

   float reviewStartBearing =-1, reviewEndBearing =-1;
   boolean isReviewRepeating = false;

   public boolean isReviewing() { return isReview; }
   public float getReviewStartBearing() { return reviewStartBearing; }
   public float getReviewEndBearing() { return reviewEndBearing; }
   public float getReviewCurrentBearing() {  return bearing; }
   public int getReviewPause() { return reviewPause; }
   public void setReviewCurrentBearing(float bearing)
   {
      if ( (isReview) && (bearing >= reviewStartBearing) && (bearing <= reviewEndBearing) )
         this.bearing = bearing;
   }
   public boolean isReviewRepeating() { return isReviewRepeating; }

   public void review(final float startBearing, final float endBearing, final int pauseMs, final boolean isRepeat,
                      final ARCamera.Reviewable reviewable)
   //-------------------------------------------------------------------------------------------
   {
      if ( (isReview) || (! isStarted) ) return;
      isReview = true;
      final boolean isOrienting = orientationProvider.isStarted();
      reviewFuture = reviewExecutor.submit(new Runnable()
      //=================================================
      {
         @Override
         public void run()
         //---------------
         {
            try
            {
               if (isOrienting)
                  orientationProvider.setSuspended(true);
               bearing = startBearing;
               reviewStartBearing = startBearing;
               reviewEndBearing = endBearing;
               isReviewRepeating = isRepeat;
               reviewPause = pauseMs;
               if (reviewable != null)
                  reviewable.onReviewStart();
               do
               {
                  while ((bearing < endBearing) && (! mustStop) && (isReview))
                  {
                     if (reviewable != null)
                        reviewable.onReview(bearing);
                     bearingAvailCondVar.open();
                     if (pauseMs > 0)
                        try { Thread.sleep(pauseMs); } catch (Exception _e) { break; }
                     if ( (mustStop) || (! isReview) ) break;
                     if (reviewable != null)
                        reviewable.onReviewed(bearing);
                     bearing += recordingIncrement;
//                     Log.i(TAG, "Preview bearing " + bearing);
                     if (bearingListener != null)
                        bearingListener.onBearingChanged(bearing);
                  }
                  if ( (mustStop) || (! isReview) )
                     break;
                  if (isRepeat)
                  {
                     bearing = endBearing;
                     while ((bearing >= startBearing) && (! mustStop) && (isReview))
                     {
                        if (reviewable != null)
                           reviewable.onReview(bearing);
                        bearingAvailCondVar.open();
                        if (pauseMs > 0)
                           try { Thread.sleep(pauseMs); } catch (Exception _e) { break; }
                        if ( (mustStop) || (! isReview) ) break;
                        if (reviewable != null)
                           reviewable.onReviewed(bearing);
                        bearing -= recordingIncrement;
                        if (bearingListener != null)
                           bearingListener.onBearingChanged(bearing);
                     }
                  }
               } while ( (isRepeat) && (! mustStop) && (isReview) );
               if (reviewable != null)
                  reviewable.onReviewComplete();
            }
            catch (Exception e)
            {
               Log.e(TAG, "previewMode run", e);
            }
            finally
            {
               reviewStartBearing = reviewEndBearing = bearing = -1;
               isReviewRepeating = false;
               if (isOrienting)
                  orientationProvider.setSuspended(false);
               isReview = false;
            }
         }
      });
   }

   public void stopReview()
   //-----------------------
   {
      if (! isReview) return;
      isReview = false;
      try
      {
         reviewFuture.get(reviewPause+200, TimeUnit.MILLISECONDS);
      }
      catch (Exception e)
      {
         if (! reviewFuture.isDone())
            reviewFuture.cancel(true);
         if (! orientationProvider.isStarted())
            orientationProvider.start();
      }
   }

   protected void onIdle(long idleTimeNS) { throw new RuntimeException("onIdle not implemented"); }

   protected boolean onSetupOrientationSensor()
   //------------------------------------------
   {
      SensorManager sensorManager = (SensorManager) context.getSystemService(Activity.SENSOR_SERVICE);
      if (providerType == ORIENTATION_PROVIDER.DEFAULT)
      {
         if (OrientationProvider.supportsOrientationProvider(context, ORIENTATION_PROVIDER.STABLE_FUSED_GYROSCOPE_ROTATION_VECTOR))
            providerType = ORIENTATION_PROVIDER.STABLE_FUSED_GYROSCOPE_ROTATION_VECTOR;
         else if (OrientationProvider.supportsOrientationProvider(context, ORIENTATION_PROVIDER.ROTATION_VECTOR))
            providerType = ORIENTATION_PROVIDER.ROTATION_VECTOR;
         else if (OrientationProvider.supportsOrientationProvider(context, ORIENTATION_PROVIDER.FUSED_GYRO_ACCEL_MAGNETIC))
            providerType = ORIENTATION_PROVIDER.FUSED_GYRO_ACCEL_MAGNETIC;
         else
            providerType = ORIENTATION_PROVIDER.ACCELLO_MAGNETIC;
      }
      switch (providerType)
      {
         case STABLE_FUSED_GYROSCOPE_ROTATION_VECTOR:
            orientationProvider = new StableFusedGyroscopeRotationVector(sensorManager);
            break;
         case FAST_FUSED_GYROSCOPE_ROTATION_VECTOR:
            orientationProvider = new FastFusedGyroscopeRotationVector(sensorManager);
            break;
         case ROTATION_VECTOR:
            orientationProvider = new RotationVectorProvider(sensorManager);
            break;
         case FUSED_GYRO_ACCEL_MAGNETIC:
            orientationProvider = new FusedGyroAccelMagnetic(sensorManager);
            break;
         case ACCELLO_MAGNETIC:
            orientationProvider = new AccelerometerCompassProvider(sensorManager);
            break;
      }
      orientationProvider.setOrientationListener(new OrientationListenable()
      //--------------------------------------------------------------------
      {
         float[] RM = new float[16];

         @Override
         public void onOrientationListenerUpdate(float[] R, Quaternion Q, long timestamp)
         //-------------------------------------------------------------------------------
         {
            SensorManager.remapCoordinateSystem(R, REMAP_X, REMAP_Y, RM);
            bearing = (float) Math.toDegrees(Math.atan2(RM[1], RM[5]));
            if (bearing < 0)
               bearing += 360;
            if (bearing >= 360)
               bearing -= 360;
            if (bearingAvailCondVar != null)
               bearingAvailCondVar.open();
            if (bearingListener != null)
               bearingListener.onBearingChanged(bearing);
         }
      });
      orientationProvider.initiate();
      return orientationProvider.isStarted();
   }

//   protected boolean onSetupRotationSensor()
//   //------------------------------------
//   {
//      SensorManager sensorManager = (SensorManager) context.getSystemService(Activity.SENSOR_SERVICE);
//      Sensor rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
//      rotationListener = new SensorEventListener()
//      //==========================================
//      {
//         public float[] R = new float[16], RM = new float[16];
//
//         @Override
//         public void onSensorChanged(SensorEvent event)
//         //--------------------------------------------
//         {
//            SensorManager.getRotationMatrixFromVector(R, event.values);
//            int worldX = SensorManager.AXIS_X,  worldY = SensorManager.AXIS_Z;
//            SensorManager.remapCoordinateSystem(R, worldX, worldY, RM);
//            float bearing = (float) Math.toDegrees(QuickFloat.atan2(RM[1], RM[5]));
//            if (bearing < 0)
//               bearing += 360;
//            onBearingChanged(bearing);
//         }
//
//         @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }
//      };
//      return sensorManager.registerListener(rotationListener, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
//   }
}
