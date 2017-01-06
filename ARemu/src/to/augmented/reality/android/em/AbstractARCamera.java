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

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import to.augmented.reality.android.common.sensor.orientation.OrientationListenable;
import to.augmented.reality.android.common.sensor.orientation.OrientationProvider;
import to.augmented.reality.android.em.free.ContinuousPlaybackThreadFree;
import to.augmented.reality.android.em.free.DirtyPlaybackThreadFree;
import to.augmented.reality.android.em.free.PlaybackThreadFree;
import to.augmented.reality.android.em.three60.ContinuousPlaybackThread360;
import to.augmented.reality.android.em.three60.DirtyPlaybackThread360;
import to.augmented.reality.android.em.three60.PlaybackThread360;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

abstract public class AbstractARCamera implements Reviewable, ARCameraInterface, Latcheable
//=========================================================================================
{
   final protected String TAG = this.getClass().getSimpleName();

   public enum RecordFileFormat { RGBA, NV21, YUV_420 }

   public enum DelegationType {ALL, READ, NONE }

   private static final String TRUE = "true";
   private static final String FALSE = "false";

   protected File headerFile = null, framesFile = null, orientationFile = null, locationFile = null;
   public File getHeaderFile() { return headerFile; }
   public File getFramesFile() { return framesFile; }
   public File getOrientationFile() { return orientationFile; }
   public File getLocationFile() { return locationFile; }

   protected Context context = null;
   protected boolean isOpen = false, isRepeat = false;
   @Override public void setRepeat(boolean isRepeat) { this.isRepeat = isRepeat; }

   protected FreePreviewListenable progress = null;
   @Override public void setFreePreviewListener(FreePreviewListenable progress) { this.progress = progress; }

   protected Surface surface = null;
   // Scaled by 1000 as per Camera.Parameters.setPreviewFpsRange. 15 or 15000 is the default set by Android Camera.Parameters
   // Camera.Parameters.setPreviewFrameRate is not scaled by 1000, Camera.Parameters.setPreviewFpsRange is.
   protected int previewFrameRate = 15000;
   protected int previewFrameRateMin = 15000;
   protected int previewWidth =-1;
   @Override public int getPreviewWidth() { return previewWidth; }

   protected int previewHeight =-1;
   @Override public int getPreviewHeight() { return previewHeight; }

   @TargetApi(Build.VERSION_CODES.LOLLIPOP)
   @Override public Size getPreviewSize() { return new Size(previewWidth, previewHeight); }
   protected float focalLength = -1;
   protected float fovx = -1;
   protected float fovy = -1;
   protected RecordingType recordingType = null;
   public RecordingType getRecordingType() { return recordingType; }

   protected CountDownLatch startLatch = null;

   protected ARSensorManager sensorManager = null;
   @Override public void setARSensorManager(ARSensorManager sensorManager) { this.sensorManager = sensorManager;  }

   /**
    * Sets a count down latch which can be used to synchronize starting the camera previewing thread with other threads,
    * eg a raw sensor thread implemented in ARSensorManager.
    * @param latch The count down latch.
    */
   @Override public void setLatch(CountDownLatch latch) { startLatch = latch; }

   protected String id = null;
   public String getId() {  return id;  }

   protected boolean isPreviewing = false;
   private boolean hasBuffer = false;
   protected boolean isUseBuffer = false;
   protected int renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY;
   protected OrientationProvider.ORIENTATION_PROVIDER orientationProviderType = OrientationProvider.ORIENTATION_PROVIDER.DEFAULT;
   protected RecordFileFormat fileFormat = RecordFileFormat.RGBA;
   public RecordFileFormat getFileFormat() { return fileFormat; }

   public boolean isUseBuffer() { return isUseBuffer; }

   protected int orientation = 0;

   protected Map<String, String> headers;
   protected BearingListener bearingListener = null;

   public String getHeader(String k) { return headers.get(k); }
   public String getHeader(String k, String def) { return (headers.containsKey(k)) ? headers.get(k) : def; }
   public int getHeaderInt(String k, int def) { return getMapInt(headers, k, def); }
   public float getHeaderFloat(String k, float def) { return getMapFloat(headers, k, def); }

   protected int bufferSize;

   public abstract void close() throws Exception;

   /**
    * If ALL and a delegate Camera instance was specified in the constructor then all non-implemented methods including
    * those that perform hardware operations such as zooming etc will be delegated.
    * If READ then only methods that read values from hardware cameras will be delegated.
    * If NONE then no delegation takes place, The delegate instance will only be used to obtain private classes
    */
   public DelegationType delegationType = DelegationType.READ;

   protected ExecutorService playbackExecutor = null;

   protected ExecutorService createPlaybackPool()
   //-------------------------------------------
   {
      playbackExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
      {
         @Override
         public Thread newThread(Runnable r)
         {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("Playback");
            return t;
         }
      });
      return playbackExecutor;
   }

   protected Future<?> playbackFuture;
   protected Runnable playbackThread = null;

   public AbstractARCamera(Context context) { this.context = context; }

   /**
    * Set the recording header and frames files to use for playback.
    * @param headerFile The recording header file of the 360 degree recording.
    * @param framesFile The recording frames file of the 360 degree recording. If
    *                   null then the frames file named in the header file is used.
    * @throws FileNotFoundException
    */
   public void setFiles(File headerFile, File framesFile, File orientationFile, File locationFile) throws IOException
   //------------------------------------------------------------------------
   {
      StringBuilder errbuf = new StringBuilder("ARCameraCommon.setFiles: ");
      if (! isFileReadable(headerFile, errbuf))
      {
         Log.e(TAG, errbuf.toString());
         throw new IOException(errbuf.toString());
      }
      this.headerFile = headerFile;
      this.framesFile = framesFile;
      headers = parseHeader(headerFile);
      String s;
      if ( (framesFile == null) || (! isFileReadable(framesFile, null)) )
      {
         s = headers.get("FramesFile");
         if (s != null)
         {
            framesFile = new File(s);
            if (! isFileReadable(framesFile, errbuf))
               throw new RuntimeException(errbuf.toString());
            else
               this.framesFile = framesFile;
         }
      }
      if (recordingType == null)
      {
         s = headers.get("Type");
         if (s != null)
            recordingType = RecordingType.valueOf(s);
      }
      if (orientationFile == null)
      {
         s = headers.get("FilteredOrientationFile");
         if (s == null)
            s = headers.get("OrientationFile");
         if (s != null)
         {
            orientationFile = new File(s);
            if ( (! orientationFile.exists()) || (! orientationFile.canRead()) || (orientationFile.isDirectory()) )
               throw new RuntimeException("Orientation file " + s + "specified in header file " + headerFile.getAbsolutePath()
                                                + " does not exist or cannot be read");
            else
               this.orientationFile = orientationFile;
         }
      }
      if (locationFile == null)
      {
         s = headers.get("LocationFile");
         if (s != null)
         {
            locationFile = new File(s);
            if ( (! locationFile.exists()) || (! locationFile.canRead()) || (locationFile.isDirectory()) )
               Log.w(TAG, "Location file " + s + "specified in header file " + headerFile.getAbsolutePath()
                          + " does not exist or cannot be read");
            else
               this.locationFile = locationFile;
         }
      }

      bufferSize = getMapInt(headers, "BufferSize", -1);
      s = headers.get("OrientationProvider");
      if (s != null)
         orientationProviderType = OrientationProvider.ORIENTATION_PROVIDER.valueOf(s);  // throws IllegalArgumentException
      else
         orientationProviderType = OrientationProvider.ORIENTATION_PROVIDER.DEFAULT;
      s = headers.get("FileFormat");
      if (s != null)
         fileFormat = RecordFileFormat.valueOf(s); // throws IllegalArgumentException
      previewWidth = getMapInt(headers, "PreviewWidth", - 1);
      previewHeight = getMapInt(headers, "PreviewHeight", - 1);
      if ( (previewWidth < 0) || (previewHeight < 0) )
         throw new RuntimeException("Header file " + headerFile.getAbsolutePath() +
                                          " does not contain valid preview width and/or height");
      focalLength = getMapFloat(headers, "FocalLength", - 1);
      fovx = getMapFloat(headers, "fovx", -1);
      fovy = getMapFloat(headers, "fovy", -1);
      float increment = getMapFloat(headers, "Increment", -1);
      if ( (recordingType == RecordingType.THREE60) && (increment < 0) )
         throw new RuntimeException("Invalid or no recording increment for RecordingType.THREE60 recording in header "
                                    + headerFile.getAbsolutePath());
   }


   public static Map<String, String> parseHeader(File headerFile) throws IOException
   //--------------------------------------------------------------------------------
   {
      BufferedReader br = null;
      Map<String, String> header = new HashMap<String, String>();
      try
      {
         br = new BufferedReader(new FileReader(headerFile));
         String line = null;
         while ( (line = br.readLine()) != null )
         {
            line = line.trim();
            if ( (line.isEmpty()) || (line.startsWith("#")) )
               continue;
            int p = line.indexOf('=');
            if (p < 0)
            {
               String error = "Invalid line " + line + " in header file " + headerFile.getAbsolutePath() + ". = not found";
               Log.e("ARCameraCommon", error);
               throw new RuntimeException(error);
            }
            String k = line.substring(0, p);
            String v = line.substring(p+1);
            header.put(k, v);
         }
      }
      finally
      {
         if (br != null)
            try { br.close(); } catch (Exception _e) {}
      }
      return header;
   }

   /**
    * Set the Orientation Provider type. @see to.augmented.reality.android.sensor.orientation.OrientationProvider
    * @param orientationProviderType
    */
   public void setOrientationProviderType(OrientationProvider.ORIENTATION_PROVIDER orientationProviderType) { this.orientationProviderType = orientationProviderType; }

   public OrientationProvider.ORIENTATION_PROVIDER getOrientationProviderType() { return orientationProviderType; }

   /**
    * Sets the recording file format. This is usually specified parsed from the header file so it should not need
    * to be specified.
    * @param format The format as specified by the RecordFileFormat enum.
    */
   public void setFileFormat(RecordFileFormat format) { fileFormat = format; }

   public void unlock() { }

   public void lock() { }


   /**
    * @return The location where the recording was made as recorded in the header file/
    */
   public Location getRecordedLocation()
   //-----------------------------------
   {
      Location location = null;
      if (headers != null)
      {
         String s = headers.get("Location");
         if (s != null)
         {
            String[] as = s.split(",");
            if ((as != null) && (as.length >= 2))
            {
               float latitude, longitude, altitude = 0;
               try
               {
                  latitude = Float.parseFloat(as[0].trim());
                  longitude = Float.parseFloat(as[1].trim());
                  if (as.length > 2)
                     try
                     {
                        altitude = Float.parseFloat(as[2].trim());
                     }
                     catch (NumberFormatException _e)
                     {
                        altitude = 0;
                     }
                  location = new Location(LocationManager.GPS_PROVIDER);
                  location.setTime(System.currentTimeMillis());
                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                     location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
                  else
                  {
                     // Kludge because some location APIs requires elapsedtime in nanos but call is not available in all Android versions.
                     try
                     {
                        Method makeCompleteMethod = null;
                        makeCompleteMethod = Location.class.getMethod("makeComplete");
                        if (makeCompleteMethod != null)
                           makeCompleteMethod.invoke(location);
                     }
                     catch (Exception e)
                     {
                        e.printStackTrace();
                     }
                  }

                  location.setLatitude(latitude);
                  location.setLongitude(longitude);
                  location.setAltitude(altitude);
                  location.setAccuracy(1.0f);
               }
               catch (Exception _e)
               {
                  Log.e(TAG, s, _e);
                  location = null;
               }
            }
         }
      }
      return location;
   }

   public int getPreviewBufferSize()
   //-------------------------------
   {
      if ( previewWidth < 0 || previewHeight < 0 || bufferSize < 0)
         return 0;
      int size = previewWidth * previewHeight;
      int count = -1;
      switch (fileFormat)
      {
         case RGBA:     count = 4; break;
//         case RGB565:   count = 2; break;
//         case RGB:      count = 3; break;
         case NV21:     count = ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
         case YUV_420:  count = ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8;
      }
      int length = size * count;
      if (length != bufferSize)
         throw new RuntimeException(String.format(Locale.US, "Header buffer size of %d does not match calculated buffer size of %d for size %dx%d",
                                                  bufferSize, length, previewWidth, previewHeight));
      return length;
   }

   /**
    * @see  android.hardware.Camera#setPreviewTexture
    * @param surfaceTexture
    */
   public void setPreviewTexture(SurfaceTexture surfaceTexture) { }

   protected LocationThread locationHandlerThread = null;
   protected LocationListener locationListener = null;
   protected OrientationListenable orientationCallback = null;

   /**
    * Sets a LocationListener instance which will receive the dummy location from the recording.
    * @param locationListener
    */
   public void setLocationListener(LocationListener locationListener) { this.locationListener = locationListener; }

   public void setOrientationListener(OrientationListenable listener) { this.orientationCallback = listener; }

   public void setBearingListener(BearingListener bearingListener) {}

   /**
    * Starts review mode. In this mode instead of using the bearing obtained from the sensors the display bearing is
    * cycled between a start and end bearing to allow debugging AR applications without having to manually move the
    * device.
    * @param startBearing The start bearing
    * @param endBearing The end bearing.
    * @param pauseMs Number of milliseconds to pause between frames.
    * @param isRepeat <i>true></i> to repeatedly display review. The review will alternate between starting at
    *                 startBearing and ending at endBearing and vice-versa.
    * @param reviewListenable A callback class to alert client code at different points of the review. Can be null to not
    *                   receive any callbacks. @see Reviewable
    */
   public void review(float startBearing, float endBearing, int pauseMs, boolean isRepeat, ReviewListenable reviewListenable)
   //------------------------------------------------------------------------------------------------------------
   {
      if ( (playbackThread != null) && (((Stoppable) playbackThread).isStarted()) )
         ((Reviewable) playbackThread).review(startBearing, endBearing, pauseMs, isRepeat, reviewListenable);
   }

   /**
    * @see Camera#startPreview()
    * Starts the preview of the recording.
    */
   public void startPreview()
   //------------------------
   {
      if ( (! isOpen) || (headers == null) || (headerFile == null) )
         throw new RuntimeException("Cannot start preview without proper initialisation");
      if (playbackFuture != null)
         stopPreview();
      try
      {
         int version = getMapInt(headers, "Version", -1);
         switch (recordingType)
         {
            case THREE60:
               float increment = -1f;
               if (headers != null)
                  increment = getMapFloat(headers, "Increment", -1);
               if (increment <= 0)
                  throw new RuntimeException("ERROR: Recording increment not found in header file");
               switch (renderMode)
               {
                  case GLSurfaceView.RENDERMODE_WHEN_DIRTY:
                     playbackThread = new DirtyPlaybackThread360(context, framesFile, bufferSize, increment,
                                                                 orientationProviderType, fileFormat, bufferQueue,
                                                                 previewFrameRate, previewWidth, previewHeight, surface);
                     break;
                  case GLSurfaceView.RENDERMODE_CONTINUOUSLY:
                     playbackThread = new ContinuousPlaybackThread360(context, framesFile, bufferSize, increment,
                                                                      orientationProviderType, fileFormat, bufferQueue,
                                                                      previewFrameRate, previewWidth, previewHeight, surface);
                     break;
                  default:
                     throw new RuntimeException("Invalid renderMode (" + renderMode + ")");
               }
               onSetCallback360((PlaybackThread360) playbackThread);
               break;
            case FREE:
               switch (renderMode)
               {
                  case GLSurfaceView.RENDERMODE_WHEN_DIRTY:
                     playbackThread = new DirtyPlaybackThreadFree(framesFile, orientationFile, locationFile, fileFormat,
                                                                  bufferSize, previewFrameRate, isRepeat, bufferQueue,
                                                                  sensorManager, previewWidth, previewHeight, context,
                                                                  surface, version, progress);
                     break;
                  case GLSurfaceView.RENDERMODE_CONTINUOUSLY:
                     playbackThread = new ContinuousPlaybackThreadFree(framesFile, orientationFile, locationFile,
                                                                       fileFormat, bufferSize, previewFrameRate,
                                                                       isRepeat, bufferQueue, sensorManager,
                                                                       previewWidth, previewHeight, context, surface,
                                                                       version, progress);
                     break;
                  default:
                     throw new RuntimeException("Invalid renderMode (" + renderMode + ")");
               }
               onSetCallbackFree((PlaybackThreadFree) playbackThread);
         }
         if (bufferQueue != null)
         {
            for (int i=0; i<bufferQueue.remainingCapacity(); i++)
               addCallbackBuffer(new byte[bufferSize]);
         }
         createPlaybackPool();
         if (startLatch != null)
            ((Latcheable) playbackThread).setLatch(startLatch);
         playbackFuture = playbackExecutor.submit(playbackThread);
         if ( (recordingType == RecordingType.THREE60) && (locationListener != null) )
         {
            locationHandlerThread = new LocationThread(Process.THREAD_PRIORITY_LESS_FAVORABLE, this, context,
                                                       locationListener);
            locationHandlerThread.start();
         }
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         stopPreview();
      }
   }

   protected abstract void onSetCallbackFree(PlaybackThreadFree playbackThread);

   protected abstract void onSetCallback360(PlaybackThread360 playbackThread);

   /**
    * Stops the recording preview.
    */
   public void stopPreview()
   //-----------------------------
   {
      if (playbackThread != null)
      {
         ((Stoppable) playbackThread).stop();
         try { Thread.sleep(100); } catch (Exception _e) {}
         if ( (playbackFuture != null) && (! playbackFuture.isDone()) )
         {
            try { playbackFuture.get(300, TimeUnit.MILLISECONDS); } catch (Exception _e) { }
            if (!playbackFuture.isDone())
               playbackFuture.cancel(true);
         }
      }
      if (locationHandlerThread != null)
         locationHandlerThread.quit();
      locationHandlerThread = null;
   }

   public boolean isReviewing()
   //--------------------------
   {
      if ((playbackThread != null) && (((Stoppable) playbackThread).isStarted()))
         return ((Reviewable) playbackThread).isReviewing(); return false;
   }

   public float getReviewStartBearing()
   //----------------------------------
   {
      if ((playbackThread != null) && (((Stoppable) playbackThread).isStarted()))
         return ((Reviewable) playbackThread).getReviewStartBearing(); return -1;
   }

   public float getReviewEndBearing()
   //-------------------------------
   {
      if ((playbackThread != null) && (((Stoppable) playbackThread).isStarted()))
         return ((Reviewable) playbackThread).getReviewEndBearing(); return -1;
   }

   public float getReviewCurrentBearing()
   //-----------------------------------
   {
      if ((playbackThread != null) && (((Stoppable) playbackThread).isStarted()))
         return ((Reviewable) playbackThread).getReviewCurrentBearing(); return -1;
   }

   public int getReviewPause()
   //-------------------------
   {
      if ((playbackThread != null) && (((Stoppable) playbackThread).isStarted()))
         return ((Reviewable) playbackThread).getReviewPause(); return -1;
   }

   public void setReviewCurrentBearing(float bearing)
   //------------------------------------------------
   {
      if ((playbackThread != null) && (((Stoppable) playbackThread).isStarted()))
         ((Reviewable) playbackThread).setReviewCurrentBearing(bearing);
   }

   public boolean isReviewRepeating()
   //--------------------------------
   {
      if ((playbackThread != null) && (((Stoppable) playbackThread).isStarted()))
         return ((Reviewable) playbackThread).isReviewRepeating(); return false;
   }

   /**
    * Stops the review
    */
   public void stopReview()
   //----------------------
   {
      if ( (playbackThread != null) && (((Stoppable)playbackThread).isStarted()) )
         ((Reviewable) playbackThread).stopReview();
   }

   /**
    * Return current preview state.
    */
   public boolean previewEnabled() { return isPreviewing; }

   /**
    * Sets the render mode for the recording playback. Can be one of GLSurfaceView.RENDERMODE_CONTINUOUSLY or
    * GLSurfaceView.RENDERMODE_WHEN_DIRTY. For GLSurfaceView.RENDERMODE_CONTINUOUSLY the preview callback is
    * continuously called even if there is no bearing change, while for GLSurfaceView.RENDERMODE_WHEN_DIRTY
    * the callback is only called when the bearing changes. If updating a OpenGL texture to display the preview
    * when using  continuous rendering, if flickering is encountered it may be due to the frame rate being to
    * high. Consider setting the frame rate using methods in @link android.hardware.Camera#Parameters.
    * @param renderMode GLSurfaceView.RENDERMODE_CONTINUOUSLY or GLSurfaceView.RENDERMODE_WHEN_DIRTY
    */
   public void setRenderMode(int renderMode)
   //---------------------------------------
   {
      switch (renderMode)
      {
         case GLSurfaceView.RENDERMODE_CONTINUOUSLY:
         case GLSurfaceView.RENDERMODE_WHEN_DIRTY:
            this.renderMode = renderMode;
            break;
         default:
            throw new RuntimeException("setRenderMode: renderMode must be GLSurfaceView.RENDERMODE_CONTINUOUSLY or GLSurfaceView.RENDERMODE_WHEN_DIRTY");
      }
   }

   public int getRenderMode() { return renderMode; }

   final static private int MAX_QUEUE_BUFFERS = 3;
   ArrayBlockingQueue<byte[]> bufferQueue = null;
   public void setBufferQueue(ArrayBlockingQueue<byte[]> bufferQueue) { this.bufferQueue = bufferQueue; }

   /**
    * @see android.hardware.Camera#addCallbackBuffer
    * @param callbackBuffer
    */
   public void addCallbackBuffer(byte[] callbackBuffer)
   //--------------------------------------------------
   {
      if (callbackBuffer.length < bufferSize)
         throw new RuntimeException("addCallbackBuffer: Buffer must be size " + bufferSize);
      isUseBuffer = true;
      if (bufferQueue == null)
         bufferQueue = new ArrayBlockingQueue<byte[]>(MAX_QUEUE_BUFFERS);
      bufferQueue.offer(callbackBuffer);
   }

   public int getFrameRate() { return previewFrameRate; }

   public void setFrameRate(int fps) { previewFrameRate = fps; }

   static public int getMapInt(Map<String, String> m, String k, int def)
   //---------------------------------------------------
   {
      String s = m.get(k);
      if (s == null)
         return def;
      try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
   }

   static public float getMapFloat(Map<String, String> m, String k, float def)
   //------------------------------------------------------------------------
   {
      String s = m.get(k);
      if (s == null)
         return def;
      try { return Float.parseFloat(s.trim()); } catch (NumberFormatException e) { return def; }
   }

   public static boolean isFileReadable(File f, StringBuilder errbuf)
   //----------------------------------------------------------------
   {
      if ( (f == null) || (! f.exists()) || (! f.isFile()) || (! f.canRead()) )
      {
         if (errbuf != null)
         {
            errbuf.append("Header file ").append(f).append(" inaccessible: ");
            if (f != null)
               errbuf.append("Exists: ").append(f.exists()).append(", is File: ").append(f.isFile())
                     .append(", Readable").append(f.canRead());
         }
         return false;
      }
      return true;
   }
}
