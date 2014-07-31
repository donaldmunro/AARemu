package to.augmented.reality.android.em;

import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.text.TextUtils;
import android.util.Log;

import java.util.*;

/**
 * Internal Parameters class analogous to Camera.Parameters. Values from Camera.Parameter is copied to this
 * class when ARCamera.setParameters is called.
 */
public class Parameters
//=====================
{
   private static final String KEY_PREVIEW_SIZE = "preview-size";
   private static final String KEY_PREVIEW_FORMAT = "preview-format";
   private static final String KEY_PREVIEW_FRAME_RATE = "preview-frame-rate";
   private static final String KEY_PREVIEW_FPS_RANGE = "preview-fps-range";
   private static final String KEY_ROTATION = "rotation";
   private static final String KEY_FOCAL_LENGTH = "focal-length";
   private static final String KEY_HORIZONTAL_VIEW_ANGLE = "horizontal-view-angle";
   private static final String KEY_VERTICAL_VIEW_ANGLE = "vertical-view-angle";

   private static final String SUPPORTED_VALUES_SUFFIX = "-values";

   private static final String TRUE = "true";
   private static final String FALSE = "false";

   // Formats for setPreviewFormat and setPictureFormat.
   private static final String PIXEL_FORMAT_YUV422SP = "yuv422sp";
   private static final String PIXEL_FORMAT_YUV420SP = "yuv420sp";
   private static final String PIXEL_FORMAT_YUV422I = "yuv422i-yuyv";
   private static final String PIXEL_FORMAT_YUV420P = "yuv420p";
   private static final String PIXEL_FORMAT_RGB565 = "rgb565";
   private static final String PIXEL_FORMAT_JPEG = "jpeg";

   private ARCamera arCamera;
   private HashMap<String, String> map;

   int previewWidth =-1, previewHeight =-1;
   Camera.Size emulatedPreviewSize = null;
   List<Camera.Size> previewSizes = new ArrayList<Camera.Size>(1);
   List<ARCamera.Size> previewLocalSizes = new ArrayList<ARCamera.Size>(1);
   int previewFrameRate = 0, previewFrameRateMin = 0;
   private List<Integer> previewFrameRates = new ArrayList<Integer>();
   private int focalLength = -1;
   float fovx, fovy;

   public void setTo(Camera.Parameters params)
   //-----------------------------------------
   {
      if ( ( previewFrameRateMin > 0) && (previewFrameRate > 0) )
         params.setPreviewFpsRange(previewFrameRateMin, previewFrameRate);
      else if (previewFrameRate > 0)
         params.setPreviewFpsRange(10, previewFrameRate);
      if (previewFrameRate > 0)
         params.setPreviewFrameRate(previewFrameRate);
      params.setPreviewSize(previewWidth, previewHeight);
      final String s = String.format(Locale.US, "%dx%d", previewWidth, previewHeight);
      params.set(KEY_PREVIEW_SIZE, s);
      params.set(KEY_PREVIEW_SIZE + SUPPORTED_VALUES_SUFFIX, s); //If more than one then comma delimited
   }

   public void setFrom(Camera.Parameters params)
   //-------------------------------------------
   {
      int[] fpsrange = new int[2];
      fpsrange[0] = fpsrange[1] = Integer.MIN_VALUE;
      params.getPreviewFpsRange(fpsrange);
      int setfps = Integer.MIN_VALUE;
      try { setfps = params.getPreviewFrameRate(); } catch (Exception e) { setfps = Integer.MIN_VALUE; }
      int fps = Math.max(setfps, fpsrange[1]);
      if (fps > 0)
         this.setPreviewFrameRate(fps);
   }

   Parameters(ARCamera arCamera, Map<String, String> headers)
   //---------------------------------------------
   {
      this.arCamera = arCamera;
      map = new HashMap<String, String>(64);
      previewWidth = ARCamera.getMapInt(headers, "PreviewWidth", - 1);
      previewHeight = ARCamera.getMapInt(headers, "PreviewHeight", - 1);
      previewLocalSizes.add(new ARCamera.Size(previewWidth, previewHeight));
      focalLength = ARCamera.getMapInt(headers, "FocalLength", - 1);
      fovx = ARCamera.getMapInt(headers, "fovx", - 1);
      fovy = ARCamera.getMapInt(headers, "fovy", - 1);
      emulatedPreviewSize = arCamera.stealSize(previewWidth, previewHeight);
      if (emulatedPreviewSize == null)
      {
         if ((arCamera.camera != null) && (arCamera.camera.getParameters() != null))
            emulatedPreviewSize = arCamera.camera.getParameters().getPictureSize();
         if (emulatedPreviewSize == null)
         {
            for (int id = 0; id < Camera.getNumberOfCameras(); id++)
            {
               Camera realCamera = null;
               try
               {
                  realCamera = Camera.open(id);
                  if (realCamera != null)
                  {
                     Camera.Parameters hijackedParameters = realCamera.getParameters();
                     if (hijackedParameters != null)
                     {
                        emulatedPreviewSize = hijackedParameters.getPictureSize();
                        if (emulatedPreviewSize != null) break;
                     }
                  }
               }
               catch (Exception e)
               {
               }
               finally
               {
                  if (realCamera != null)
                     try { realCamera.release(); } catch (Exception _e) { }
               }
            }
            if (emulatedPreviewSize != null)
            {
               emulatedPreviewSize.width = previewWidth;
               emulatedPreviewSize.height = previewHeight;
               previewSizes.add(emulatedPreviewSize);
            }
         }
         if (emulatedPreviewSize != null)
         {
            emulatedPreviewSize.width = previewWidth;
            emulatedPreviewSize.height = previewHeight;
            previewSizes.add(emulatedPreviewSize);
         }
      }
      for (int i=10; i<=100; i +=10)
         previewFrameRates.add(i);
   }

   /**
    * Serializes the Map holding the parameters into a String.
    * @return
    */
   public String flatten()
   //--------------------
   {
      StringBuilder flattened = new StringBuilder(128);
      for (String k : map.keySet())
         flattened.append(k).append("=").append(map.get(k)).append(";");
      // chop off the extra semicolon at the end
      flattened.deleteCharAt(flattened.length() - 1);
      return flattened.toString();
   }

   /**
    * Deserializes the string versiuon of Map holding the parameters returned from flatten.
    * @param flattened
    */
   public void unflatten(String flattened)
   //-------------------------------------
   {
      map.clear();

      TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter(';');
      splitter.setString(flattened);
      for (String kv : splitter)
      {
         int pos = kv.indexOf('=');
         if (pos == -1)
         {
            continue;
         }
         String k = kv.substring(0, pos);
         String v = kv.substring(pos + 1);
         map.put(k, v);
      }
   }

   /**
    * Removes an item from the parameter Map.
    * @param key
    */
   public void remove(String key) { map.remove(key); }

   /**
    * Sets an item in the Parameter Map.
    * @param key
    * @param value
    */
   public void set(String key, String value)
   //---------------------------------------
   {
      if (key.indexOf('=') != -1 || key.indexOf(';') != -1 || key.indexOf(0) != -1)
      {
         Log.e(ARCamera.TAG, "Key \"" + key + "\" contains invalid character (= or ; or \\0)");
         return;
      }
      if (value.indexOf('=') != -1 || value.indexOf(';') != -1 || value.indexOf(0) != -1)
      {
         Log.e(ARCamera.TAG, "Value \"" + value + "\" contains invalid character (= or ; or \\0)");
         return;
      }
      map.put(key, value);
   }

   /**
    * Sets an integer in the Parameter Map.
    * @param key
    * @param value
    */
   public void set(String key, int value) { map.put(key, Integer.toString(value)); }

   public String get(String key) { return map.get(key); }

   public int getInt(String key) { return Integer.parseInt(map.get(key)); }


   /**
    * Not implemented as preview size is fixed to the recording size.
    * @param width
    * @param height
    */
   public void setPreviewSize(int width, int height) { }

   /**
    * @return Returns the preview size as set in the recording header file.
    */
   public Camera.Size getPreviewSize()
   //---------------------------------
   {
      if (emulatedPreviewSize != null)
      {
         emulatedPreviewSize.width = previewWidth;
         emulatedPreviewSize.height = previewHeight;
      }
      return emulatedPreviewSize;
   }

   /**
    * @return Returns the preview size as set in the recording header file as an internal Size instance and not a
    * Camera.Size one.
    */
   public ARCamera.Size getPreviewsize() { return new ARCamera.Size(previewWidth, previewHeight); }

   /**
    * Gets supported preview sizes as a List of length one containing the preview size specified in the recording
    * header file.
    * @return
    */
   public List<Camera.Size> getSupportedPreviewSizes() { return previewSizes; }

   /**
    * /**
    * Gets supported preview sizes as a List of length one of internal ARCamera.Size instances containing the
    * preview size specified in the recording header file.
    * @return
    */

   public List<ARCamera.Size> getSupportedPreviewsizes() { return previewLocalSizes; }

   /**
    * Sets the preview frame rate to use when renderMode is set to continuous.
    * @see #setRenderMode(int)
    * @param fps
    */
   public void setPreviewFrameRate(int fps) { previewFrameRate = fps; }

   /**
    * @return the preview frame rate as set by @link #setPreviewFrameRate
    */
   public int getPreviewFrameRate() { return previewFrameRate; }

   /**
    * @return A list of supported preview frame rates. Currently set to values between 10 and 100 although any frame
    * rate can be set in the set methods.
    */
   public List<Integer> getSupportedPreviewFrameRates() { return previewFrameRates; }

   /**
    * Sets a preview frame rate range to use when renderMode is set to continuous.
    * Currently only the max values is used.
    * @param min
    * @param max
    */
   public void setPreviewFpsRange(int min, int max)
   //----------------------------------------------
   {
      previewFrameRateMin = min;
      previewFrameRate = max;
   }

   /**
    * Sets a preview frame rate range to use when renderMode is set to continuous.
    * Currently only the max values is used.
    * @param range
    */
   public void getPreviewFpsRange(int[] range)
   //-----------------------------------------
   {
      if (range == null || range.length != 2)
         throw new IllegalArgumentException("range must be an array with two elements.");
      range[0] = previewFrameRateMin;
      range[1] = previewFrameRate;
   }

   /**
    * @return A list of supported preview frame rate ranges. Currently set to values between 10 and 100 although
    * any frame rate is allowed by the set methods.
    */
   public List<int[]> getSupportedPreviewFpsRange()
   //----------------------------------------------
   {
      List<int[]> L = new ArrayList<int[]>(7);
      for (int fps=10; fps<100; fps +=10)
         L.add(new int[] {fps, fps+10 });
      return L;
   }

   /**
    * The preview format set here is ignored but the Parameter Map is updated so getPreviewFormat will return the
    * value set here.
    * @param pixelFormat
    */
   public void setPreviewFormat(int pixelFormat)
   //--------------------------------------------
   {
      set(KEY_PREVIEW_FORMAT, Integer.toString(pixelFormat));
   }

   /**
    * Gets the preview format set by @link #setPreviewFormat
    * @return
    */
   public int getPreviewFormat()
   //----------------------------
   {
      String s = get(KEY_PREVIEW_FORMAT);
      if (s == null)
         return ImageFormat.NV21;
      try
      {
         return Integer.parseInt(s.trim());
      }
      catch (NumberFormatException e)
      {
         return ImageFormat.NV21;
      }
   }

   /**
    * Supported preview formats. If delegate camera not sepecified then returns a list comprising 2 elements namely
    * NV21 and YV12. If a delegate camera was specified and delegateType is ALL or READ then the delegate camera
    * list is returned.
    * @return
    */
   public List<Integer> getSupportedPreviewFormats()
   //------------------------------------------------
   {
      if ( (arCamera.camera == null) || (arCamera.camera.getParameters() == null) || (arCamera.delegationType == ARCamera.DelegationTypes.NONE))
      {
         ArrayList<Integer> formats = new ArrayList<Integer>();
         formats.add(ImageFormat.NV21);
         formats.add(ImageFormat.YV12);
         return formats;
      }
      else
         return arCamera.camera.getParameters().getSupportedPreviewFormats();
   }

   /**
    * sets the preview rotation. Currently not used by ARCamera but passed to delegage Camera instance if
    * delegateType is ALL
    * @param rotation
    */
   public void setRotation(int rotation)
   //----------------------------------
   {
      if (rotation == 0 || rotation == 90 || rotation == 180 || rotation == 270)
         set(KEY_ROTATION, Integer.toString(rotation));
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType == ARCamera.DelegationTypes.ALL) )
      {
         Camera.Parameters params = arCamera.camera.getParameters(); params.setRotation(rotation);
         arCamera.camera.setParameters(params);
      }
   }

   /**
    * @see android.hardware.Camera.Parameters#getWhiteBalance
    * @return
    */
   public String getWhiteBalance()
   //-----------------------------
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getWhiteBalance();
      return null;
   }

   public void setWhiteBalance(String value)
   //---------------------------------------
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType == ARCamera.DelegationTypes.ALL) )
      {
         Camera.Parameters params = arCamera.camera.getParameters(); params.setWhiteBalance(value);
         arCamera.camera.setParameters(params);
      }
   }

   public List<String> getSupportedWhiteBalance()
   //--------------------------------------------
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getSupportedWhiteBalance();
      return null;
   }

   public String getColorEffect()
   //----------------------------
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getColorEffect();
      return null;
   }

   public void setColorEffect(String value)
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType == ARCamera.DelegationTypes.ALL) )
      {
         Camera.Parameters params = arCamera.camera.getParameters(); params.setColorEffect(value);
         arCamera.camera.setParameters(params);
      }
   }

   public List<String> getSupportedColorEffects()
   //--------------------------------------------
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getSupportedColorEffects();
      return null;
   }

   public String getAntibanding()
   //----------------------------
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getAntibanding();
      return null;
   }

   public void setAntibanding(String antibanding)
   //-------------------------------------------
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType == ARCamera.DelegationTypes.ALL) )
      {
         Camera.Parameters params = arCamera.camera.getParameters(); params.setAntibanding(antibanding);
         arCamera.camera.setParameters(params);
      }
   }

   public List<String> getSupportedAntibanding()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getSupportedAntibanding();
      return null;
   }

   public String getSceneMode()
   //--------------------------
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getSceneMode();
      return null;
   }

   public void setSceneMode(String value)
   //------------------------------------
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType == ARCamera.DelegationTypes.ALL) )
      {
         Camera.Parameters params = arCamera.camera.getParameters(); params.setAntibanding(value);
         arCamera.camera.setParameters(params);
      }
   }

   public List<String> getSupportedSceneModes()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getSupportedSceneModes();
      return null;
   }

   public String getFlashMode()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getFlashMode();
      return null;
   }

   public void setFlashMode(String value)
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType == ARCamera.DelegationTypes.ALL) )
      {
         Camera.Parameters params = arCamera.camera.getParameters(); params.setFlashMode(value);
         arCamera.camera.setParameters(params);
      }
   }

   public List<String> getSupportedFlashModes()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getSupportedFlashModes();
      return null;
   }

   public String getFocusMode()
   //--------------------------
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getFocusMode();
      return Camera.Parameters.FOCUS_MODE_INFINITY;
   }

   public void setFocusMode(String value)
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType == ARCamera.DelegationTypes.ALL) )
      {
         Camera.Parameters params = arCamera.camera.getParameters(); params.setFocusMode(value);
         arCamera.camera.setParameters(params);
      }
   }

   public List<String> getSupportedFocusModes()
   //------------------------------------------
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getSupportedFocusModes();
      ArrayList<String> L = new ArrayList<String>(1);
      L.add(Camera.Parameters.FOCUS_MODE_INFINITY);
      return L;
   }

   public float getFocalLength() { return focalLength; }

   public float getHorizontalViewAngle() { return fovx; }

   public float getVerticalViewAngle() { return fovy; }

   public int getExposureCompensation()
   //----------------------------------
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getExposureCompensation();
      return 0;
   }

   public void setExposureCompensation(int value)
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType == ARCamera.DelegationTypes.ALL) )
      {
         Camera.Parameters params = arCamera.camera.getParameters(); params.setExposureCompensation(value);
         arCamera.camera.setParameters(params);
      }
   }

   public int getMaxExposureCompensation()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getMaxExposureCompensation();
      return 0;
   }

   public int getMinExposureCompensation()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getMinExposureCompensation();
      return 0;
   }

   public float getExposureCompensationStep()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getExposureCompensationStep();
      return 0;
   }

   public void setAutoExposureLock(boolean toggle)
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType == ARCamera.DelegationTypes.ALL) )
      {
         Camera.Parameters params = arCamera.camera.getParameters(); params.setAutoExposureLock(toggle);
         arCamera.camera.setParameters(params);
      }
   }

   public boolean getAutoExposureLock()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getAutoExposureLock();
      return false;
   }

   public boolean isAutoExposureLockSupported()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().isAutoExposureLockSupported();
      return false;
   }

   public void setAutoWhiteBalanceLock(boolean toggle)
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType == ARCamera.DelegationTypes.ALL) )
      {
         Camera.Parameters params = arCamera.camera.getParameters(); params.setAutoWhiteBalanceLock(toggle);
         arCamera.camera.setParameters(params);
      }
   }

   public boolean getAutoWhiteBalanceLock()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getAutoWhiteBalanceLock();
      return false;
   }

   public boolean isAutoWhiteBalanceLockSupported()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().isAutoWhiteBalanceLockSupported();
      return false;
   }

   public int getZoom()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getZoom();
      return 0;
   }

   public void setZoom(int value)
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType == ARCamera.DelegationTypes.ALL) )
      {
         Camera.Parameters params = arCamera.camera.getParameters(); params.setZoom(value);
         arCamera.camera.setParameters(params);
      }
   }

   public boolean isZoomSupported()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().isZoomSupported();
      return false;
   }

   public int getMaxZoom()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getMaxZoom();
      return 0;
   }

   public List<Integer> getZoomRatios()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getZoomRatios();
      return null;
   }

   public boolean isSmoothZoomSupported()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().isSmoothZoomSupported();
      return false;
   }

   public void getFocusDistances(float[] output)
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         arCamera.camera.getParameters().getFocusDistances(output);
   }

   public int getMaxNumFocusAreas()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getMaxNumFocusAreas();
      return 0;
   }

   public List<Camera.Area> getFocusAreas()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getFocusAreas();
      return null;
   }

   public void setFocusAreas(List<Camera.Area> focusAreas)
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType == ARCamera.DelegationTypes.ALL) )
      {
         Camera.Parameters params = arCamera.camera.getParameters(); params.setFocusAreas(focusAreas);
         arCamera.camera.setParameters(params);
      }
   }

   public int getMaxNumMeteringAreas()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getMaxNumMeteringAreas();
      return 0;
   }

   public List<Camera.Area> getMeteringAreas()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getMeteringAreas();
      return null;
   }

   public void setMeteringAreas(List<Camera.Area> meteringAreas)
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType == ARCamera.DelegationTypes.ALL) )
      {
         Camera.Parameters params = arCamera.camera.getParameters(); params.setMeteringAreas(meteringAreas);
         arCamera.camera.setParameters(params);
      }
   }

   public int getMaxNumDetectedFaces()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getMaxNumDetectedFaces();
      return 0;
   }

   public void setRecordingHint(boolean hint)
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType == ARCamera.DelegationTypes.ALL) )
      {
         Camera.Parameters params = arCamera.camera.getParameters(); params.setRecordingHint(hint);
         arCamera.camera.setParameters(params);
      }
   }

   public boolean isVideoSnapshotSupported()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().isVideoSnapshotSupported();
      return false;
   }

   public void setVideoStabilization(boolean toggle) { }

   public boolean getVideoStabilization()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().getVideoStabilization();
      return false;
   }

   public boolean isVideoStabilizationSupported()
   {
      if ( (arCamera.camera != null) && (arCamera.camera.getParameters() != null) && (arCamera.delegationType != ARCamera.DelegationTypes.NONE) )
         return arCamera.camera.getParameters().isVideoStabilizationSupported();
      return false;
   }


   private void splitInt(String str, int[] output)
   {
      if (str == null) return;

      TextUtils.StringSplitter splitter = new TextUtils.SimpleStringSplitter(',');
      splitter.setString(str);
      int index = 0;
      for (String s : splitter)
         output[index++] = Integer.parseInt(s);
   }

   // Returns the value of a float parameter.
   private float getFloat(String key, float defaultValue)
   {
      try
      {
         return Float.parseFloat(map.get(key));
      }
      catch (NumberFormatException ex)
      {
         return defaultValue;
      }
   }

   // Returns the value of a integer parameter.
   private int getInt(String key, int defaultValue)
   {
      try
      {
         return Integer.parseInt(map.get(key));
      }
      catch (NumberFormatException ex)
      {
         return defaultValue;
      }
   }

   private boolean same(String s1, String s2)
   {
      if (s1 == null && s2 == null) return true;
      if (s1 != null && s1.equals(s2)) return true;
      return false;
   }
}
