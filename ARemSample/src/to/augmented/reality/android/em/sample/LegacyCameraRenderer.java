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

package to.augmented.reality.android.em.sample;

import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import to.augmented.reality.android.common.gl.GLTexture;
import to.augmented.reality.android.em.ARCamera;
import to.augmented.reality.android.em.ARImageFormat;
import to.augmented.reality.android.em.ARSensorManager;
import to.augmented.reality.android.em.Latcheable;
import to.augmented.reality.android.em.ReviewListenable;

public class LegacyCameraRenderer extends GLRenderer
//=================================================
{
   private static final String TAG = LegacyCameraRenderer.class.getSimpleName();

   private ARCamera camera = null;
   public ARCamera getLegacyCamera() { return camera; }

   private int cameraId = -1;

   private Camera realCamera = null;

   private Camera.PreviewCallback previewCallback;

   private boolean isUseOwnBuffers = true;

   private int bufferSize = 0, nv21BufferSize = 0;

   public LegacyCameraRenderer(MainActivity activity, ARSurfaceView surfaceView) { super(activity, surfaceView); }

   protected boolean initCamera(StringBuilder errbuf) throws Exception
   //------------------------------------------------------------------
   {
      if ( (headerFile == null) || (! headerFile.exists()) || (! headerFile.canRead()) )
         throw new RuntimeException("Invalid or non-existent replay header file (" +
                                          ((headerFile == null) ? "null" : headerFile.getAbsolutePath()));
      try
      {
         stopCamera();
         if (EmulationControls.IS_EMULATE_CAMERA)
         {
            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
            int frontId = -1, backId = -1;
            for (int i = 0; i < Camera.getNumberOfCameras(); i++)
            {
               Camera.getCameraInfo(i, cameraInfo);
               if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK)
                  backId = i;
               else if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)
                  frontId = i;
            }
            if (backId >= 0)
            {
               cameraId = backId;
               try { realCamera = Camera.open(cameraId); } catch (Exception _e) { realCamera = null; }
               if (realCamera != null)
               {
                  camera = new ARCamera(activity, cameraId, realCamera);
                  camera = (ARCamera) ARCamera.open(cameraId);
               }
            }
            if ( (camera == null) && (frontId >= 0) )
            {
               cameraId = frontId;
               try { realCamera = Camera.open(cameraId); } catch (Exception _e) { realCamera = null; }
               if (realCamera != null)
               {
                  camera = new ARCamera(activity, cameraId, realCamera);
                  camera = (ARCamera) ARCamera.open(cameraId);
               }
            }
            if (camera == null)
            {
               camera = new ARCamera(activity, -1);
               cameraId = Integer.parseInt(camera.getId());
               camera = (ARCamera) ARCamera.open(cameraId);
            }
            if (camera != null)
               camera.setFiles(headerFile, framesFile, null, null);
            else
               return false;
   //         setDisplayOrientation();
            Camera.Parameters cameraParameters = camera.getParameters();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)
               if (cameraParameters.isVideoStabilizationSupported())
                  cameraParameters.setVideoStabilization(true);
            List<Camera.Size> L = cameraParameters.getSupportedPreviewSizes();
            Camera.Size sz = cameraParameters.getPreviewSize();
            List<String> focusModes = cameraParameters.getSupportedFocusModes();
   //         if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO))
   //            cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
   //         else
            if  (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_INFINITY))
               cameraParameters.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
            if (cameraParameters.isZoomSupported())
               cameraParameters.setZoom(0);
            cameraParameters.setPreviewFrameRate(30000);
            camera.setParameters(cameraParameters);
            camera.setRepeat(EmulationControls.REPEAT);
            camera.setFrameRate(EmulationControls.FPS);
         }
         else
         {
            camera = new ARCamera(activity, -1); // -1 creates an ARCamera without a real Camera delegate
                                                // use a valid camera id or pass a Camera instance to have delegation
            cameraId = Integer.parseInt(camera.getId());
            camera.setFiles(headerFile, framesFile, null, null);
            previewWidth = camera.getPreviewWidth();
            previewHeight = camera.getPreviewHeight();
            bufferSize = camera.getPreviewBufferSize();
            camera.setFrameRate(EmulationControls.FPS);
            camera.setRepeat(EmulationControls.REPEAT);
            if (bufferSize == -1)
            {
               switch (camera.getFileFormat())
               {
                  case RGBA:     bufferSize = previewWidth * previewHeight * 4; break;
                  case NV21:     bufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;    break;
                  case YUV_420:  bufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8;    break;
                  default: throw new RuntimeException("Cannot determine buffer size. (BufferSize and FileFormat are missing from the header file");
               }
            }
         }
         if (EmulationControls.DIRTY_VIDEO)
            camera.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
         else
            camera.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
         ARCamera.RecordFileFormat fileFormat = camera.getFileFormat();
         switch (fileFormat)
         {
            case NV21:
            case YUV_420:
            case RGBA:
               textureFormat = GLTexture.TextureFormat.RGBA;
               break;
            default: throw new RuntimeException(fileFormat.name() + ": Unknown recording file format");
         }
         return true;
      }
      catch (Exception e)
      {
         camera = null;
         toast(String.format("Could not obtain rear facing camera (%s). Check if it is in use by another application.",
                             e.getMessage()));
         Log.e(TAG, "Camera.open()", e);
         if (errbuf != null)
            errbuf.append(e.getMessage());
         return false;
      }
   }

   public boolean startPreview(CountDownLatch latch, ARSensorManager sensorManager)
   //------------------------------------------------------------------------------
   {
      if (camera == null) return false;
      if (isPreviewing)
      {
         try
         {
            camera.setPreviewCallbackWithBuffer(null);
            camera.stopPreview();
            isPreviewing = false;
            previewCallback = null;
         }
         catch (Exception e)
         {
            Log.e(TAG, "", e);
         }
      }
      if (EmulationControls.IS_EMULATE_CAMERA)
      {
         Camera.Size cameraSize = camera.getParameters().getPreviewSize();
         previewWidth = cameraSize.width;
         previewHeight = cameraSize.height;
      }
      else
      {
         previewWidth = camera.getHeaderInt("PreviewWidth", -1);
         previewHeight = camera.getHeaderInt("PreviewHeight", -1);
      }

      if ( (previewWidth < 0) || (previewHeight < 0) )
         throw new RuntimeException("Invalid resolution " + previewWidth + "x" + previewHeight);

      bufferSize = camera.getHeaderInt("BufferSize", -1);
      if (bufferSize == -1)
      {
         switch (camera.getFileFormat())
         {
//            case RGB:      bufferSize = previewWidth * previewHeight * 3;
//               bufferSize += bufferSize % 4;
//               break;
            case RGBA:     bufferSize = previewWidth * previewHeight * 4; break;
//            case RGB565:   bufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(ImageFormat.RGB_565) / 8; break;
            case NV21:     bufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;    break;
            case YUV_420:  bufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8;    break;
            default: throw new RuntimeException("Cannot determine buffer size. (BufferSize and FileFormat are missing from the header file");
         }
      }
      switch (camera.getFileFormat())
      {
         case NV21:
            nv21BufferSize = bufferSize;
            bufferSize = previewWidth * previewHeight * 4;
            if (isUseOwnBuffers)
               cameraBuffer  = new byte[nv21BufferSize];
            break;
         case YUV_420:
            nv21BufferSize = bufferSize;
            bufferSize = previewWidth * previewHeight * 4;
            if (isUseOwnBuffers)
               cameraBuffer  = new byte[nv21BufferSize];
            break;
//         case RGB:
//            bufferSize += bufferSize % 4;
         case RGBA:
//         case RGB565:
            if (isUseOwnBuffers)
               cameraBuffer  = new byte[bufferSize];
      }
      previewBuffer = null;
      if (previewByteBuffer != null)
         previewByteBuffer.clear();
      previewByteBuffer = null;
      previewBuffer = new byte[bufferSize];

      previewByteBuffer = ByteBuffer.allocateDirect(bufferSize);
      previewByteBuffer.put(previewBuffer);
      previewByteBuffer.rewind();
      previewCallback = new CameraPreviewCallback();
      if (EmulationControls.IS_EMULATE_CAMERA)
      {
         try
         {
            Camera.Parameters cameraParameters = camera.getParameters();
            cameraParameters.setPreviewSize(previewWidth, previewHeight);
            cameraParameters.setPreviewFormat(ARImageFormat.RGBA);
            camera.setParameters(cameraParameters);
            if (isUseOwnBuffers)
            {
               camera.setPreviewCallbackWithBuffer(previewCallback);
               camera.addCallbackBuffer(cameraBuffer);
            }
            else
               camera.setPreviewCallback(previewCallback);
            SurfaceTexture previewSurfaceTexture = new SurfaceTexture(10);
            camera.setPreviewTexture(previewSurfaceTexture);
         }
         catch (final Exception e)
         {
            Log.e(TAG, "Initialising camera preview", e);
            toast("Error initialising camera preview: " + e.getMessage());
            return false;
         }
      }
      else
         camera.setPreviewCallback(previewCallback);
      if ( (camera instanceof Latcheable) && (latch != null) )
      {
         ((Latcheable) camera).setLatch(latch);
         super.latch = latch;
      }
      camera.setARSensorManager(sensorManager);
      camera.startPreview();
      isPreviewing = true;
      return true;
   }

   public boolean isReviewing()
   //--------------------------
   {
      if (camera != null)
         return camera.isReviewing();
      return false;
   }

   public void stopReviewing()
   //-------------------------
   {
      if (camera != null)
         camera.stopReview();
   }

   public float getReviewCurrentBearing()
   //------------------------------------
   {
      if (camera != null)
         return camera.getReviewCurrentBearing();
      return -1;
   }

   public void setReviewBearing(float bearing) { if (camera != null) camera.setReviewCurrentBearing(bearing); }


   public void review(float startBearing, float endBearing, int pauseMs, boolean isRepeat, ReviewListenable reviewListenable)
   //---------------------------------------------------------------------------------------------------------------------
   {
      if ( (camera == null) || (camera.isReviewing()) ) return;
      if ( (camera != null) && (isPreviewing) )
         camera.review(startBearing, endBearing, pauseMs, isRepeat, reviewListenable);
   }

   protected void stopCamera()
   //----------------------
   {
      if (camera != null)
      {
         if (isPreviewing)
         {
            try
            {
//               if (previewSurfaceTexture != null)
//                  previewSurfaceTexture.setOnFrameAvailableListener(null);
               camera.setPreviewCallbackWithBuffer(null);
               camera.stopPreview();
            }
            catch (Exception e)
            {
               Log.e(TAG, "", e);
            }
         }
         try { camera.release(); } catch (Exception _e) { Log.e(TAG, _e.getMessage()); }
      }
      camera = null;
      if (realCamera != null)
         try { realCamera.release(); } catch (Exception e) {}
      realCamera = null;
      isPreviewing = false;
   }

   protected void releaseCameraFrame(boolean isPreviewed)
   //---------------------------------------------------
   {
      if ( (camera != null) && (isPreviewed) && (isUseOwnBuffers) )
         camera.addCallbackBuffer(cameraBuffer);
   }

   class CameraPreviewCallback implements Camera.PreviewCallback
   //==================================================================
   {
      RenderScript rsNv21toRGBA;
      ScriptIntrinsicYuvToRGB YUVToRGBA;
      Allocation ain, aOut;

      public CameraPreviewCallback()
      //-----------------------------
      {
         if (LegacyCameraRenderer.this.camera.getFileFormat() == ARCamera.RecordFileFormat.NV21)
         {
            rsNv21toRGBA = RenderScript.create(activity);
            YUVToRGBA = ScriptIntrinsicYuvToRGB.create(rsNv21toRGBA, Element.U8_4(rsNv21toRGBA));
            Type.Builder yuvType = new Type.Builder(rsNv21toRGBA, Element.U8(rsNv21toRGBA)).setX(previewWidth).
                  setY(previewHeight).setMipmaps(false).setYuvFormat(ImageFormat.NV21);
            Type.Builder rgbaType = new Type.Builder(rsNv21toRGBA, Element.RGBA_8888(rsNv21toRGBA)).setX(previewWidth).
                  setY(previewHeight).setMipmaps(false);
            ain = Allocation.createTyped(rsNv21toRGBA, yuvType.create(), Allocation.USAGE_SCRIPT);
            aOut = Allocation.createTyped(rsNv21toRGBA, rgbaType.create(), Allocation.USAGE_SCRIPT);
         }
      }

      @Override
      public void onPreviewFrame(byte[] data, Camera camera)
      //----------------------------------------------------
      {
         if (data == null)
         {
            if ( (isUseOwnBuffers) && (camera != null) )
               camera.addCallbackBuffer(cameraBuffer);
            return;
         }
         if (LegacyCameraRenderer.this.camera.getFileFormat() == ARCamera.RecordFileFormat.NV21)
         {
            ain.copyFrom(data);
            YUVToRGBA.setInput(ain);
            YUVToRGBA.forEach(aOut);
            aOut.copyTo(previewBuffer);
            synchronized (this)
            {
               previewByteBuffer.clear();
               previewByteBuffer.put(previewBuffer);
            }
         }
         else
         {
            synchronized (this)
            {
               previewByteBuffer.clear();
               previewByteBuffer.put(data);
            }
         }
         isUpdateSurface = true;
         view.requestRender();
      }
   }
}
