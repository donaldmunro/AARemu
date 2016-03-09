package to.augmented.reality.android.em;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import to.augmented.reality.android.common.sensor.orientation.OrientationProvider;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

abstract public class ARCameraCommon
//==================================
{
   final protected String TAG = this.getClass().getSimpleName();

   public enum RecordFileFormat { RGBA, RGB, RGB565, NV21 }

   public enum DelegationTypes { ALL, READ, NONE }

   private static final String TRUE = "true";
   private static final String FALSE = "false";

   protected File headerFile;
   protected File framesFile;
   protected Context context = null;
   protected boolean isOpen = false;
   protected Surface surface = null;
   // Scaled by 1000 as per Camera.Parameters.setPreviewFpsRange. 15 or 15000 is the default set by Android Camera.Parameters
   // Camera.Parameters.setPreviewFrameRate is not scaled by 1000, Camera.Parameters.setPreviewFpsRange is.
   protected int previewFrameRate = 15000;
   protected int previewFrameRateMin = 15000;
   protected int previewWidth =-1;
   protected int previewHeight =-1;
   protected float focalLength = -1;
   protected float fovx = -1;
   protected float fovy = -1;

   protected int id = -1;
   public int getId() {  return id;  }

   private SurfaceTexture surfaceTexture = null;
   protected boolean isPreviewing = false;
   protected boolean isOneShot = false;
   private boolean hasBuffer = false;
   protected boolean isUseBuffer = false;
   protected int renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY;
   protected OrientationProvider.ORIENTATION_PROVIDER orientationProviderType = OrientationProvider.ORIENTATION_PROVIDER.DEFAULT;
   protected RecordFileFormat fileFormat = RecordFileFormat.RGBA;
   public RecordFileFormat getFileFormat() { return fileFormat; }

   public boolean isUseBuffer() { return isUseBuffer; }

   protected int orientation = 0;
   protected Camera.ErrorCallback errorCallback;

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
   public DelegationTypes delegationType = DelegationTypes.READ;

   protected final ExecutorService playbackExecutor = Executors.newSingleThreadExecutor(new ThreadFactory()
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
   protected Future<?> playbackFuture;
   protected PlaybackThread playbackThread = null;

   public ARCameraCommon(Context context) { this.context = context; }

   /**
    * Set the 360 degree recording header and frames files to use for playback.
    * Setting either file to null will call release and stop previewing.
    * @param headerFile The recording header file of the 360 degree recording.
    * @param framesFile The recording frames file of the 360 degree recording.
    * @throws FileNotFoundException
    */
   public void setFiles(File headerFile, File framesFile) throws IOException
   //------------------------------------------------------------------------
   {
      if ( (headerFile == null) || (framesFile == null) )
      {
         Log.d(TAG, "Setting recording files to null");
         try { close(); } catch (Exception _e) { Log.e(TAG, "", _e); }
         this.headerFile = this.framesFile = null;
         return;
      }
      if ( (this.headerFile != null) && (this.headerFile.equals(headerFile)) &&
            (this.framesFile != null) && (this.framesFile.equals(framesFile)) )
         return;
      if ( (! headerFile.exists()) || (! headerFile.canRead()) )
         throw new FileNotFoundException("Header file " + headerFile.getAbsolutePath() + " not found or not readable");
      if ( (! framesFile.exists()) || (! framesFile.canRead()) )
         throw new FileNotFoundException("Frames file " + framesFile.getAbsolutePath() + " not found or not readable");
      headers = parseHeader(headerFile);
      bufferSize = getMapInt(headers, "BufferSize", -1);
      String s = headers.get("OrientationProvider");
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
      this.headerFile = headerFile;
      this.framesFile = framesFile;
   }


   private static Map<String, String> parseHeader(File headerFile) throws IOException
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
         case RGB565:   count = 2; break;
         case RGB:      count = 3; break;
         case NV21:     count = ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
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
   public void setPreviewTexture(SurfaceTexture surfaceTexture) { this.surfaceTexture = surfaceTexture;  }

   LocationThread locationHandlerThread = null;
   LocationListener locationListener = null;

   /**
    * Sets a LocationListener instance which will receive the dummy location from the recording.
    * @param locationListener
    */
   public void setLocationListener(LocationListener locationListener) { this.locationListener = locationListener; }

   /**
    * Sets a BearingListener instance which will receive the latest bearing.
    * @see to.augmented.reality.android.em.BearingListener
    * @param bearingListener
    */
   public void setBearingListener(BearingListener bearingListener) { this.bearingListener = bearingListener; }


   /**
    * Starts review mode. In this mode instead of using the bearing obtained from the sensors the display bearing is
    * cycled between a start and end bearing to allow debugging AR applications without having to manually move the
    * device.
    * @param startBearing The start bearing
    * @param endBearing The end bearing.
    * @param pauseMs Number of milliseconds to pause between frames.
    * @param isRepeat <i>true></i> to repeatedly display review. The review will alternate between starting at
    *                 startBearing and ending at endBearing and vice-versa.
    * @param reviewable A callback class to alert client code at different points of the review. Can be null to not
    *                   receive any callbacks. @see Reviewable
    */
   public void startReview(float startBearing, float endBearing, int pauseMs, boolean isRepeat, Reviewable reviewable)
   //------------------------------------------------------------------------------------------------------------
   {
      if ( (playbackThread != null) && (playbackThread.isStarted()) )
         playbackThread.review(startBearing, endBearing, pauseMs, isRepeat, reviewable);
   }

   /**
    * Stops the recording preview.
    */
   public void stopPreview()
   //-----------------------------
   {
      if (playbackThread != null)
      {
         playbackThread.mustStop = true;
         if (playbackThread.isStarted())
         {
            if (playbackFuture != null)
            {
               try { playbackFuture.get(300, TimeUnit.MILLISECONDS); } catch (Exception _e) { }
               if (!playbackFuture.isDone())
                  playbackFuture.cancel(true);
            }
         }
      }
      if (locationHandlerThread != null)
         locationHandlerThread.quit();
      locationHandlerThread = null;
   }

   /**
    * Review callback interface.
    */
   public interface Reviewable
   //=========================
   {
      /**
       * Called before starting review
       */
      void onReviewStart();

      /**
       * Called before displaying the review frame for the specified bearing.
       * @param bearing
       */
      void onReview(float bearing);

      /**
       * Called after displaying the review frame for the specified bearing.
       * @param bearing
       */
      void onReviewed(float bearing);

      /**
       * Called when the review completes or is terminated.
       */
      void onReviewComplete();
   }

   public boolean isReviewing() { if ( (playbackThread != null) && (playbackThread.isStarted()) ) return playbackThread.isReviewing(); return false;}
   public float getReviewStartBearing() { if ( (playbackThread != null) && (playbackThread.isStarted()) ) return playbackThread.getReviewStartBearing(); return -1; }
   public float getReviewEndBearing() { if ( (playbackThread != null) && (playbackThread.isStarted()) ) return playbackThread.getReviewEndBearing(); return -1;}
   public float getReviewCurrentBearing() { if ( (playbackThread != null) && (playbackThread.isStarted()) ) return playbackThread.getReviewCurrentBearing(); return -1; }
   public int getReviewPause() { if ( (playbackThread != null) && (playbackThread.isStarted()) ) return playbackThread.getReviewPause(); return -1; }
   public void setReviewCurrentBearing(float bearing) { if ( (playbackThread != null) && (playbackThread.isStarted()) ) playbackThread.setReviewCurrentBearing(bearing); }
   public boolean isReviewRepeating() { if ( (playbackThread != null) && (playbackThread.isStarted()) ) return playbackThread.isReviewRepeating(); return false;}

   /**
    * Stops the review
    */
   public void stopReview()
   //----------------------
   {
      if ( (playbackThread != null) && (playbackThread.isStarted()) )
         playbackThread.stopReview();
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

   final static private int MAX_QUEUE_BUFFERS = 5;
   final ArrayBlockingQueue<byte[]> bufferQueue = new ArrayBlockingQueue<byte[]>(MAX_QUEUE_BUFFERS);

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
      bufferQueue.offer(callbackBuffer);
   }

   public int getFrameRate() { return previewFrameRate; }

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
}
