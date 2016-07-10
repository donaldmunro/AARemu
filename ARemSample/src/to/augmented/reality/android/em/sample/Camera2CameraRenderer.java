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
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CaptureRequest;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import to.augmented.reality.android.em.ARCameraCharacteristics;
import to.augmented.reality.android.em.ARCameraDevice;
import to.augmented.reality.android.em.ARCameraManager;
import to.augmented.reality.android.em.AbstractARCamera;
import to.augmented.reality.android.em.Latcheable;
import to.augmented.reality.android.em.ReviewListenable;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2CameraRenderer extends GLRenderer
//===================================================
{
   private static final String TAG = Camera2CameraRenderer.class.getSimpleName();

   private String cameraID;
   private int bufferSize;
   private Semaphore cameraOpenCloseLock = new Semaphore(1);
   private Handler cameraHandler = null;
   private ARCameraDevice cameraDevice;
   public ARCameraDevice getCamera2Camera() { return cameraDevice; }
   private AbstractARCamera.RecordFileFormat fileFormat = null;
   private CameraCaptureSession captureSession;

   class Camera2Callback implements ARCameraDevice.ARCaptureCallback
   //================================================================================
   {
      RenderScript rs;
      Allocation ain, aOut;
      ScriptIntrinsicYuvToRGB YUVToRGBA;

      public Camera2Callback()
      //----------------------
      {
         rs = RenderScript.create(activity);
         switch (fileFormat)
         {
            case YUV_420:
               YUVToRGBA = ScriptIntrinsicYuvToRGB.create(rs, Element.RGBA_8888(rs));
               Type.Builder yuvTypeBuilder = new Type.Builder(rs, Element.YUV(rs));
               yuvTypeBuilder.setX(previewWidth).setY(previewHeight).setYuvFormat(ImageFormat.YUV_420_888);
               ain = Allocation.createTyped(rs, yuvTypeBuilder.create(), Allocation.USAGE_SCRIPT);

               Type.Builder rgbTypeBuilder = new Type.Builder(rs, Element.RGBA_8888(rs));
               rgbTypeBuilder.setX(previewWidth).setY(previewHeight);
               aOut = Allocation.createTyped(rs, rgbTypeBuilder.create(), Allocation.USAGE_SCRIPT);
               break;
            case NV21:
               YUVToRGBA = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs));
               Type.Builder yuvType = new Type.Builder(rs, Element.U8(rs)).setX(previewWidth).
                     setY(previewHeight).setMipmaps(false).setYuvFormat(ImageFormat.NV21);
               Type.Builder rgbaType = new Type.Builder(rs, Element.RGBA_8888(rs)).setX(previewWidth).
                     setY(previewHeight).setMipmaps(false);
               ain = Allocation.createTyped(rs, yuvType.create(), Allocation.USAGE_SCRIPT);
               aOut = Allocation.createTyped(rs, rgbaType.create(), Allocation.USAGE_SCRIPT);
               break;
         }
      }

      @Override
      public void onPreviewFrame(byte[] data)
      //-------------------------------------
      {
         if (! isPreviewing) return;
         switch (fileFormat)
         {
            case NV21:
            case YUV_420:
               ain.copyFrom(data);
               YUVToRGBA.setInput(ain);
               YUVToRGBA.forEach(aOut);
               aOut.copyTo(previewBuffer);
               synchronized (this)
               {
                  previewByteBuffer.clear();
                  previewByteBuffer.put(previewBuffer);
               };
               break;
            case RGBA:
               synchronized (this)
               {
                  previewByteBuffer.clear();
                  previewByteBuffer.put(data);
               }
               break;
         }
         isUpdateSurface = true;
         view.requestRender();
      }
   };

   private final ARCameraDevice.StateCallback cameraStateCallback = new ARCameraDevice.StateCallback()
   //=========================================================================================
   {

      @Override public void onOpened(ARCameraDevice cameraDevice)
      //-------------------------------------------------------
      {
         Camera2CameraRenderer.this.cameraDevice = cameraDevice;
         fileFormat = cameraDevice.getFileFormat();
         Camera2CameraRenderer.this.cameraDevice.setPreviewCallback(new Camera2Callback());
         cameraOpenCloseLock.release();
         bufferSize = cameraDevice.getHeaderInt("BufferSize", -1);
         if (bufferSize == -1)
         {
            switch (cameraDevice.getFileFormat())
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
      }

      @Override public void onDisconnected(ARCameraDevice cameraDevice)
      //-------------------------------------------------------------
      {
         try { cameraDevice.close(); } catch (Exception e) {}
         Camera2CameraRenderer.this.cameraDevice = null;
         cameraOpenCloseLock.release();
      }

      @Override public void onError(ARCameraDevice cameraDevice, int error)
      //-------------------------------------------------------
      {
         Camera2CameraRenderer.this.cameraDevice = null;
         cameraOpenCloseLock.release();
      }
   };

   public Camera2CameraRenderer(MainActivity activity, ARSurfaceView surfaceView) { super(activity, surfaceView); }

   @Override
   protected boolean initCamera(StringBuilder errbuf) throws Exception
   //----------------------------------------------
   {
      boolean isLock = false;
      try
      {
         ARCameraManager manager = ARCameraManager.get(activity, headerFile, null, null, null, false);
         if (EmulationControls.IS_EMULATE_CAMERA)
         {
            String camList[] = manager.getCameraIdList();
            if (camList.length == 0)
            {
               Log.e(TAG, "Error: No camera");
               return false;
            }
            ARCameraCharacteristics characteristics = null;
            for (String cameraID : camList)
            {
               characteristics = manager.getCameraCharacteristics(cameraID);
               Integer facing = characteristics.get(ARCameraCharacteristics.LENS_FACING);
               if (facing == null) continue;
               if (facing == CameraCharacteristics.LENS_FACING_BACK)
               {
                  this.cameraID = cameraID;
                  break;
               }
            }
            if (cameraID == null)
            {
               this.cameraID = camList[0];
               characteristics = manager.getCameraCharacteristics(cameraID);
            }
            Integer level = characteristics.get(ARCameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            boolean isLegacyCamera = (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY);
            int[] formats = characteristics.get(ARCameraCharacteristics.SCALER_AVAILABLE_FORMATS);
            switch (formats[0])
            {
               case ImageFormat.NV21: fileFormat = AbstractARCamera.RecordFileFormat.NV21; break;
               case ImageFormat.YUV_420_888: fileFormat = AbstractARCamera.RecordFileFormat.YUV_420; break;
               default: fileFormat = AbstractARCamera.RecordFileFormat.RGBA; break;
            }

            ARCameraCharacteristics.ARStreamConfigurationMap streamConfig =
                  characteristics.get(ARCameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            for (Size psize : streamConfig.getOutputSizes(SurfaceTexture.class))
            {
               previewWidth = psize.getWidth();
               previewHeight = psize.getHeight();
            }
            if (! cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS))
               throw new RuntimeException("Time out waiting to lock camera opening.");
            isLock = true;
            manager.openCamera(cameraID, cameraStateCallback, null);
         }
         else
         {
            cameraID = "AR";
            cameraDevice = manager.getCamera(cameraID);
            if (cameraDevice == null)
               cameraDevice = ARCameraDevice.create(activity, cameraID, headerFile, null, null, null, null, false);
            String s = cameraDevice.getHeader("FileFormat");
            if (s != null)
               fileFormat = AbstractARCamera.RecordFileFormat.valueOf(s);
            else
               fileFormat = AbstractARCamera.RecordFileFormat.RGBA;
            Size previewSize = cameraDevice.getPreviewSize();
            previewWidth = previewSize.getWidth();
            previewHeight = previewSize.getHeight();
            bufferSize = cameraDevice.getPreviewBufferSize();
            if (bufferSize == -1)
            {
               switch (cameraDevice.getFileFormat())
               {
                  case RGBA:     bufferSize = previewWidth * previewHeight * 4; break;
                  case NV21:     bufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;    break;
                  case YUV_420:  bufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(ImageFormat.YUV_420_888) / 8;    break;
                  default: throw new RuntimeException("Cannot determine buffer size. (BufferSize and FileFormat are missing from the header file");
               }
            }
            cameraDevice.setPreviewCallback(new Camera2Callback());
         }
         if (EmulationControls.DIRTY_VIDEO)
            cameraDevice.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
         else
            cameraDevice.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
      }
      catch (SecurityException e)
      {
         Log.e(TAG, "", e);
      }
      catch (Throwable e)
      {
         Log.e(TAG, "", e);
         if (isLock)
            cameraOpenCloseLock.release();
         if (errbuf != null)
            errbuf.append(e.getMessage());
         return false;
      }
      return true;
   }

   @Override
   public boolean startPreview(CountDownLatch latch)
   //-----------------------------------------------
   {
      isPreviewing = false;
      if (previewWidth < 0 || previewHeight < 0)
         return false;
      if (null == cameraDevice)
      {
         Log.e(TAG, "createCameraPreviewSession: camera not initialised");
         return false;
      }
      previewBuffer = new byte[previewWidth*previewHeight*4];
      if (EmulationControls.IS_EMULATE_CAMERA)
      {
         try
         {
            cameraOpenCloseLock.acquire();
            if (null != captureSession)
            {
               cameraOpenCloseLock.release();
               Log.e(TAG, "createCameraPreviewSession: captureSession is already started");
               return false;
            }

            final Surface dummySurface = new Surface(new SurfaceTexture(0));
            final CaptureRequest.Builder previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            CameraCaptureSession.StateCallback cameraStatusCallback = new CameraCaptureSession.StateCallback()
            {
               @Override
               public void onConfigured(CameraCaptureSession session)
               //-----------------------------------------------------------------
               {
                  captureSession = session;
                  if (cameraHandler == null)
                     cameraHandler = createHandler("Camera2CameraRenderer");
                  try
                  {
                     if (previewRequestBuilder != null)
                     {
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                                  CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE,
                                                  CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                        previewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);
                        //                  previewRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        //                                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
                        //previewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps[fps.length - 1]);
                        previewBuffer = null;
                        if (previewByteBuffer != null)
                           previewByteBuffer.clear();
                        previewByteBuffer = null;
                        previewBuffer = new byte[bufferSize];

                        previewByteBuffer = ByteBuffer.allocateDirect(bufferSize);
                        previewByteBuffer.put(previewBuffer);
                        previewByteBuffer.rewind();
                        previewRequestBuilder.addTarget(dummySurface);
                        captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler);
                     }
   //                  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) captureSession.prepare(previewSurface);
                     isPreviewing = true;
                     Log.i(TAG, "CameraPreviewSession has been started");
                  }
                  catch (Throwable e)
                  {
                     Log.e(TAG, "createCaptureSession failed", e);
                     throw new RuntimeException(e);
                  }
                  finally
                  {
                     cameraOpenCloseLock.release();
                  }
               }

               @Override
               public void onConfigureFailed(CameraCaptureSession session)
               //---------------------------------------------------------
               {
                  Log.e(TAG, "createCameraPreviewSession failed");
                  cameraOpenCloseLock.release();
               }
            };
            cameraDevice.setFrameRate(EmulationControls.FPS);
            cameraDevice.setRepeat(EmulationControls.REPEAT);
            cameraDevice.createCaptureSession(Arrays.asList(dummySurface), cameraStatusCallback, cameraHandler);
            return true;
         }
         catch (CameraAccessException e)
         {
            Log.e(TAG, "createCameraPreviewSession");
         }
         catch (InterruptedException e)
         {
            throw new RuntimeException(
                  "Interrupted while createCameraPreviewSession", e);
         }
         finally
         {
            //cameraOpenCloseLock.release();
         }
      }
      else
      {
         previewBuffer = null;
         if (previewByteBuffer != null)
            previewByteBuffer.clear();
         previewByteBuffer = null;
         previewBuffer = new byte[bufferSize];

         previewByteBuffer = ByteBuffer.allocateDirect(bufferSize);
         previewByteBuffer.put(previewBuffer);
         previewByteBuffer.rewind();
         cameraDevice.setFrameRate(EmulationControls.FPS);
         cameraDevice.setRepeat(EmulationControls.REPEAT);
         if ( (cameraDevice instanceof Latcheable) && (latch != null) )
         {
            ((Latcheable) cameraDevice).setLatch(latch);
            super.latch = latch;
         }
         cameraDevice.startPreview();
         isPreviewing = true;
         return true;
      }
      return false;
   }

   @Override protected void stopCamera()
   //-----------------------------------
   {
      if (EmulationControls.IS_EMULATE_CAMERA)
      {
         if (captureSession != null)
            captureSession.close();
      }
      else
         cameraDevice.stopPreview();
      isPreviewing = false;
   }

   @Override protected void releaseCameraFrame(boolean isPreviewed) { }

   public boolean isReviewing()
   //--------------------------
   {
      if (cameraDevice != null)
         return cameraDevice.isReviewing();
      return false;
   }

   @Override
   public void stopReviewing()
   //-------------------------
   {
      if (cameraDevice != null)
         cameraDevice.stopReview();
   }

   @Override
   public float getReviewCurrentBearing()
   //------------------------------------
   {
      if (cameraDevice != null)
         return cameraDevice.getReviewCurrentBearing();
      return -1;
   }

   @Override
   public void setReviewBearing(float bearing) { if (cameraDevice != null) cameraDevice.setReviewCurrentBearing(bearing); }

   @Override
   public void review(float startBearing, float endBearing, int pauseMs, boolean isRepeat, ReviewListenable reviewListenable)
   //---------------------------------------------------------------------------------------------------------------------
   {
      if ( (cameraDevice == null) || (cameraDevice.isReviewing()) ) return;
      if ( (cameraDevice != null) && (isPreviewing) )
         cameraDevice.review(startBearing, endBearing, pauseMs, isRepeat, reviewListenable);
   }
}
