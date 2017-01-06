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

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.Camera;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.ConditionVariable;
import android.renderscript.RenderScript;
import android.util.Log;
import android.view.Surface;
import to.augmented.reality.android.common.math.Quaternion;
import to.augmented.reality.android.common.sensor.orientation.AccelerometerCompassProvider;
import to.augmented.reality.android.common.sensor.orientation.FastFusedGyroscopeRotationVector;
import to.augmented.reality.android.common.sensor.orientation.FusedGyroAccelMagnetic;
import to.augmented.reality.android.common.sensor.orientation.OrientationListenable;
import to.augmented.reality.android.common.sensor.orientation.OrientationProvider;
import to.augmented.reality.android.common.sensor.orientation.RotationVectorProvider;
import to.augmented.reality.android.common.sensor.orientation.StableFusedGyroscopeRotationVector;
import to.augmented.reality.android.em.ARCamera;
import to.augmented.reality.android.em.AbstractARCamera;
import to.augmented.reality.android.em.ARCameraDevice;
import to.augmented.reality.android.em.BearingListener;
import to.augmented.reality.android.em.Latcheable;
import to.augmented.reality.android.em.ReviewListenable;
import to.augmented.reality.android.em.Stoppable;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import to.augmented.reality.android.em.rs.ScriptC_rgba2argb;

import static to.augmented.reality.android.common.sensor.orientation.OrientationProvider.ORIENTATION_PROVIDER;

/**
 * Base class for 360 playback threads. Review API is also implemented here.
 */
abstract public class PlaybackThread360 implements Runnable, Stoppable, Latcheable
//================================================================================
{
   final static private String TAG = PlaybackThread360.class.getSimpleName();
   final static protected int PREALLOCATED_BUFFERS = 3;
   final static int REMAP_X = SensorManager.AXIS_X,  REMAP_Y = SensorManager.AXIS_Z;

   protected final int fps;
   protected boolean isUseBuffer;
   volatile protected boolean mustStop = false;

   protected CountDownLatch startLatch = null;
   @Override public void setLatch(CountDownLatch latch) { startLatch = latch; }

   final protected int bufferSize;
   final protected float recordingIncrement;
   protected Camera.PreviewCallback previewCallback;
   protected ARCameraDevice.ARCaptureCallback captureCallback;
   protected ConditionVariable bearingAvailCondVar = null;
   protected BearingListener bearingListener;
   protected ArrayBlockingQueue<byte[]> bufferQueue;

   protected ORIENTATION_PROVIDER providerType;
   OrientationProvider orientationProvider = null;
   protected AbstractARCamera.RecordFileFormat fileFormat = ARCamera.RecordFileFormat.RGBA;

   volatile protected float bearing = -1;

   float startBearing = Float.MAX_VALUE, endBearing = -1, currentBearing =-1;

   protected File framesFile = null;
   final long fileLen;
   private SensorEventListener rotationListener;
   volatile protected boolean isStarted = false;
   @Override public boolean isStarted() { return isStarted; }

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

   private RenderScript renderscript = null;
   protected Surface surface; // Only not null If the ARCamera had a setPreviewDisplay(holder) call. The surface is from the holder
   protected final Context context; // ""
   protected int width, height; // Only necessary where surface is not null

   public PlaybackThread360(Context context, File framesFile, int bufferSize, float recordingIncrement,
                            ORIENTATION_PROVIDER providerType, ARCamera.RecordFileFormat fileFormat,
                            ArrayBlockingQueue<byte[]> bufferQueue, int fps,
                            int previewWidth, int previewHeight, Surface previewSurface)
   //----------------------------------------------------------------------------------------------
   {
      if (context == null)
         throw new RuntimeException("Context cannot be null");
      this.fileLen = framesFile.length();
      if (! framesFile.canRead())
         throw new RuntimeException("Frames file " + framesFile + " not readable");
      this.framesFile = framesFile;
      this.bufferSize = bufferSize;
      this.recordingIncrement = (float) (Math.rint(recordingIncrement*10.0f)/10.0);
      this.bufferQueue = bufferQueue;
      this.providerType = providerType;
      this.fileFormat = fileFormat;
      this.fps = fps;
      this.surface = previewSurface;
      this.context = context;
      width = previewWidth;
      height = previewHeight;
      if (this.surface != null)
         renderscript = RenderScript.create(context);
      this.isUseBuffer = (bufferQueue != null);
      if (! isUseBuffer)
      {  // If not using user defined buffers create an internal buffer of buffers
         this.bufferQueue = new ArrayBlockingQueue<byte[]>(PREALLOCATED_BUFFERS);
         for (int i=0; i<PREALLOCATED_BUFFERS; i++)
            this.bufferQueue.add(new byte[bufferSize]);
      }
   }

   public void setCameraListener(Object callback)
   //--------------------------------------------
   {
      if (callback == null)
         throw new RuntimeException("Camera callback cannot be null");
      if (callback instanceof Camera.PreviewCallback)
         previewCallback = (Camera.PreviewCallback) callback;
      else if (callback instanceof ARCameraDevice.ARCaptureCallback)
         captureCallback = (ARCameraDevice.ARCaptureCallback) callback;
      else
         throw new RuntimeException(callback.getClass().getName() + " must be one of " +
                                    Camera.PreviewCallback.class.getName() + " or " +
                                    ARCameraDevice.ARCaptureCallback.class.getName());
   }

   public void setBearingListener(BearingListener listener) { bearingListener = listener; }

   @Override abstract public void run();

   @Override
   public final void stop()
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
                      final ReviewListenable reviewListenable)
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
               if (reviewListenable != null)
                  reviewListenable.onReviewStart();
               do
               {
                  while ((bearing < endBearing) && (! mustStop) && (isReview))
                  {
                     if (reviewListenable != null)
                        reviewListenable.onReview(bearing);
                     bearingAvailCondVar.open();
                     if (pauseMs > 0)
                        try { Thread.sleep(pauseMs); } catch (Exception _e) { break; }
                     if ( (mustStop) || (! isReview) ) break;
                     if (reviewListenable != null)
                        reviewListenable.onReviewed(bearing);
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
                        if (reviewListenable != null)
                           reviewListenable.onReview(bearing);
                        bearingAvailCondVar.open();
                        if (pauseMs > 0)
                           try { Thread.sleep(pauseMs); } catch (Exception _e) { break; }
                        if ( (mustStop) || (! isReview) ) break;
                        if (reviewListenable != null)
                           reviewListenable.onReviewed(bearing);
                        bearing -= recordingIncrement;
                        if (bearingListener != null)
                           bearingListener.onBearingChanged(bearing);
                     }
                  }
               } while ( (isRepeat) && (! mustStop) && (isReview) );
               if (reviewListenable != null)
                  reviewListenable.onReviewComplete();
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

   public void drawToSurface(final byte[] buffer)
   //--------------------------------------------
   {
      int[] ARGB = new int[buffer.length/4];
      try
      {
         Type.Builder rgbaType = new Type.Builder(renderscript, Element.RGBA_8888(renderscript)).setX(width).
               setY( height).setMipmaps(false);
         Allocation aIn = Allocation.createTyped(renderscript, rgbaType.create(), Allocation.USAGE_SCRIPT);
         Type.Builder argbType = new Type.Builder(renderscript, Element.U32(renderscript)).setX(width).
               setY( height).setMipmaps(false);
         Allocation aOut = Allocation.createTyped(renderscript, argbType.create(), Allocation.USAGE_SCRIPT);
         ScriptC_rgba2argb rs = new ScriptC_rgba2argb(renderscript);
         aIn.copyFrom(buffer);
         rs.set_in(aIn);
         rs.forEach_rgba2argb(aOut);
         aOut.copyTo(ARGB);
      }
      catch (Exception e)
      {
         Log.e("PlaybackThreadFree", "drawToSurface: Renderscript RGBA to ARGB error", e);
         int i=0, j = 0;
         while (i<buffer.length)
         {
            int r = (int) buffer[i++];
            if (r < 0) r = 256 + r; // Brain-dead Java has no unsigned char
            int g = (int) buffer[i++];
            if (g < 0) g = 256 + g;
            int b = (int) buffer[i++];
            if (b < 0) b = 256 + b;
            int a = buffer[i++];
            if (a < 0) a = 256 + a;
            ARGB[j++] = Color.argb(a, r, g, b);
         }
      }

      Bitmap bmp = Bitmap.createBitmap(ARGB, width, height, Bitmap.Config.ARGB_8888);
      Canvas canvas = surface.lockCanvas(null);
      canvas.drawBitmap(bmp, 0, 0, null);
      surface.unlockCanvasAndPost(canvas);
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
