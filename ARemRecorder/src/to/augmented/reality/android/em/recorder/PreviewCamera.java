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

package to.augmented.reality.android.em.recorder;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import to.augmented.reality.em.recorder.ScriptC_yuv2grey;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class PreviewCamera implements Previewable, Bufferable, Freezeable
//=======================================================================
{
   static final private String LOGTAG = "PreviewCamera";
   static private final boolean DEBUG = false;

   private final GLSurfaceView surfaceView;
   private final GLRecorderRenderer renderer;
   private final RenderScript renderscript;
   private final NativeFrameBuffer frameBuffer;
   private final ConditionVariable frameAvailCondVar;
   private Semaphore mCameraOpenCloseLock = new Semaphore(1);
   private String cameraID = null;
   private int legacyCameraId = -1;
   private CameraDevice cameraDevice;
   private int cameraWidth, cameraHeight;
   private boolean hasYUV = false, hasNV21 = false;
   private int imageFormat =-1;
   private Range<Integer> fps[] = null;
   private CameraCaptureSession captureSession;
   private ImageReader previewImageReader;
   boolean isPreviewing = false;
   private Surface previewSurface, displaySurface;
   private volatile boolean mustBuffer = false;
   private boolean hasFlash = false;
   private HandlerThread cameraThread;
   private Handler cameraHandler;
   private HandlerThread previewThread;
   private Handler previewHandler;
   private HandlerThread captureThread;
   private Handler captureHandler;

   @Override public boolean hasFlash() { return hasFlash; }

   @Override public void bufferOn() { frameBuffer.bufferOn(); mustBuffer = true; }
   @Override  public void bufferOff() { frameBuffer.bufferOff(); mustBuffer = false; }
   @Override public void bufferClear() { frameBuffer.bufferClear(); }
   @Override public boolean bufferEmpty() { return frameBuffer.bufferEmpty(); }
   @Override public void writeFile(File f) throws IOException { frameBuffer.writeFile(f); }
   @Override public File writeFile() { return frameBuffer.writeFile(); }
   @Override public boolean closeFile() throws IOException { return frameBuffer.closeFile(); }
   @Override public void flushFile() { frameBuffer.flushFile(); }
   @Override public void writeOff() { frameBuffer.writeOff(); }
   @Override public void writeOn() { frameBuffer.writeOn(); }
   @Override public void push(long timestamp, byte[] data, int retries) { frameBuffer.push(timestamp, data, retries); }

   @Override public void startTimestamp(long timestamp) { frameBuffer.startTimestamp(timestamp); }
   @Override public int writeCount() { return frameBuffer.writeCount(); }
   @Override public long writeSize() { return frameBuffer.writeSize(); }
   @Override public void stop() { frameBuffer.stop(); }
   @Override public boolean openForReading() { return frameBuffer.openForReading(); }
   @Override public boolean openForReading(File f) { return frameBuffer.openForReading(f); }
   @Override public BufferData read() throws IOException { return frameBuffer.read(); }
   @Override public long readPos(long offset) { return frameBuffer.readPos(offset); }

   float focalLen = -1;
   public float getFocalLen() { return focalLen; }

   float fovx = -1;
   public float getFovx() { return fovx; }

   float fovy = -1;
   public float getFovy() { return fovy; }

   String[] resolutions = new String[0];

   boolean isFlashOn = false;
   @Override public boolean isFlashOn() { return isFlashOn; }
   @Override public void setFlash(boolean isOnOff) { isFlashOn = isOnOff; }

   public PreviewCamera(GLRecorderRenderer renderer, GLSurfaceView surfaceView, Surface displaySurface,
                        NativeFrameBuffer frameBuffer, ConditionVariable frameAvailCondVar)
   //--------------------------------------------------------------------------------------------
   {
      this.renderer = renderer;
      this.surfaceView = surfaceView;
      this.displaySurface = displaySurface;
      this.frameBuffer = frameBuffer;
      this.frameAvailCondVar = frameAvailCondVar;
      renderscript = RenderScript.create(renderer.activity);
   }

   @Override
   public boolean open(int face, int width, int height, StringBuilder errbuf) throws CameraAccessException
   //=====================================================================================================
   {
      boolean isLock = false;
      try
      {
         CameraManager manager = (CameraManager) surfaceView.getContext().getSystemService(Context.CAMERA_SERVICE);
         String camList[] = manager.getCameraIdList();
         if (camList.length == 0)
         {
            Log.e(LOGTAG, "Error: No camera");
            if (errbuf != null) errbuf.append("Error: No camera");
            return false;
         }
         CameraCharacteristics characteristics = null;
         for (String cameraID : camList)
         {
            characteristics = manager.getCameraCharacteristics(cameraID);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing == null) continue;
            if (face == facing)
            {
               this.cameraID = cameraID;
               break;
            }
         }
         if (cameraID == null)
         {
            Log.e(LOGTAG, "Could not find matching camera");
            if (errbuf != null) errbuf.append("Error: Could not find matching camera");
            return false;
         }
         characteristics = manager.getCameraCharacteristics(cameraID);
         Boolean isFlashAvail = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
         hasFlash =  ( (isFlashAvail != null) && (isFlashAvail) );
         if (!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS))
            throw new RuntimeException("Time out waiting to lock camera opening.");
         isLock = true;
         setPreviewSize(width, height, errbuf);
         imageFormat = ((hasYUV) ? ImageFormat.YUV_420_888 : ((hasNV21) ? ImageFormat.NV21 : -1));
         if (imageFormat < 0)
         {
            Log.e(LOGTAG, "No supported output formats");
            throw new RuntimeException("No supported output formats");
         }
//         float[] focalLens = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);

         int facing = Camera.CameraInfo.CAMERA_FACING_BACK;
         switch (face)
         {
            case CameraCharacteristics.LENS_FACING_BACK:
               facing = Camera.CameraInfo.CAMERA_FACING_BACK;
               break;
            case CameraCharacteristics.LENS_FACING_FRONT:
               facing = Camera.CameraInfo.CAMERA_FACING_FRONT;
               break;
         }
         Camera.CameraInfo camera1Info = new Camera.CameraInfo();
         Camera camera1 = null;
         try
         {
            for (int i = 0; i < Camera.getNumberOfCameras(); i++)
            {
               Camera.getCameraInfo(i, camera1Info);
               if (camera1Info.facing == facing)
               {
                  legacyCameraId = i;
                  try { camera1 = Camera.open(legacyCameraId); } catch (Exception _e) { camera1 = null; }
                  break;
               }
            }
            if (camera1 != null)
            {
               Camera.Parameters camera1Parameters = camera1.getParameters();
               fovx = camera1Parameters.getHorizontalViewAngle();
               fovy = camera1Parameters.getVerticalViewAngle();
               focalLen = camera1Parameters.getFocalLength();
            }
         }
         catch (Exception _e)
         {
            Log.e(LOGTAG, "", _e);
         }
         finally
         {
            if (camera1 != null)
               try { camera1.release(); } catch (Exception _e) { Log.e(LOGTAG, "", _e); }
         }

//       Android 5.0.1 bug (https://code.google.com/p/android/issues/detail?id=81984)
//       YUV_420_888 does not contain all U and V data
//       if (Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP)
//          imageFormat = ImageFormat.NV21;
         if ((Build.VERSION.SDK_INT == Build.VERSION_CODES.LOLLIPOP) && (hasNV21))
            imageFormat = ImageFormat.NV21;
         previewImageReader = ImageReader.newInstance(width, height, imageFormat, 2);
      }
      catch (SecurityException e)
      {
         Log.e(LOGTAG, "", e);
      }
      catch (Throwable e)
      {
         Log.e(LOGTAG, "", e);
         if (isLock)
            mCameraOpenCloseLock.release();
         return false;
      }
      return true;
   }

   private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback()
   //=========================================================================================
   {

      @Override public void onOpened(CameraDevice cameraDevice)
      //-------------------------------------------------------
      {
         PreviewCamera.this.cameraDevice = cameraDevice;
         mCameraOpenCloseLock.release();
         createCameraPreviewSession();
      }

      @Override public void onDisconnected(CameraDevice cameraDevice)
      //-------------------------------------------------------------
      {
         cameraDevice.close();
         PreviewCamera.this.cameraDevice = null;
         mCameraOpenCloseLock.release();
      }

      @Override public void onError(CameraDevice cameraDevice, int error)
      //-------------------------------------------------------
      {
         cameraDevice.close();
         PreviewCamera.this.cameraDevice = null;
         mCameraOpenCloseLock.release();
      }
   };

   @Override
   @SuppressLint({"NewApi", "MissingPermission, UseCheckPermission"})
   @SuppressWarnings({"ResourceType"})
   public void startPreview(boolean isFlashOn)
   //-----------------------
   {
      if (cameraWidth < 0 || cameraHeight < 0)
         return;
      this.isFlashOn = isFlashOn;
      CameraManager manager = (CameraManager) surfaceView.getContext().getSystemService(Context.CAMERA_SERVICE);
      if (manager == null)
         return;
      try
      {
         cameraThread = new HandlerThread("CameraPreview");
         cameraThread.start();
         cameraHandler = new Handler(cameraThread.getLooper());
         manager.openCamera(cameraID, cameraStateCallback, cameraHandler);
      }
      catch (Exception e)
      {
         Log.e(LOGTAG, "", e);
         throw new RuntimeException(e);
      }
   }

   private void createCameraPreviewSession()
   //---------------------------------------
   {
      try
      {
         mCameraOpenCloseLock.acquire();
         if (null == cameraDevice)
         {
            mCameraOpenCloseLock.release();
            Log.e(LOGTAG, "startPreview: camera isn't opened");
            return;
         }
         if (null != captureSession)
         {
            mCameraOpenCloseLock.release();
            Log.e(LOGTAG, "startPreview: captureSession is already started");
            return;
         }
         previewSurface = previewImageReader.getSurface();
         renderer.previewSurfaceTexture.setDefaultBufferSize(cameraWidth, cameraHeight);
         final CaptureRequest.Builder previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
         displaySurface = renderer.newDisplaySurface();
         previewRequestBuilder.addTarget(displaySurface);
         previewRequestBuilder.addTarget(previewSurface);
         previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
//         previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
//         previewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);
         if ( (fps != null) && (fps.length > 0) )
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps[fps.length - 1]);
//         previewRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON);
         if (isFlashOn)
         {
            CameraManager manager = (CameraManager) surfaceView.getContext().getSystemService(Context.CAMERA_SERVICE);
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraID);
            Boolean isFlashAvail = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if ( (isFlashAvail != null) && (isFlashAvail) )
               previewRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
         }
         CameraCaptureSession.StateCallback cameraStatusCallback = new CameraCaptureSession.StateCallback()
         {
            @Override
            public void onClosed(CameraCaptureSession session)
            {
               Log.i(LOGTAG, "Camera2 closed");
            }

            @Override
            public void onReady(CameraCaptureSession session)
            {
               Log.i(LOGTAG, "Camera2 ready");
            }

            @Override
            public void onConfigured(CameraCaptureSession session)
            //-----------------------------------------------------------------
            {
               captureSession = session;
               try
               {
                  previewThread = new HandlerThread("CameraPreview");
                  previewThread.start();
                  previewHandler = new Handler(previewThread.getLooper());
                  previewImageReader.setOnImageAvailableListener(imageAvailableListener, previewHandler);

                  captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, null); //cameraHandler);
                  Log.i(LOGTAG, "CameraPreviewSession has been started");
                  isPreviewing = true;
               }
               catch (Throwable e)
               {
                  Log.e(LOGTAG, "createCaptureSession failed", e);
                  throw new RuntimeException(e);
               }
               finally
               {
                  mCameraOpenCloseLock.release();
               }
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession session)
            //---------------------------------------------------------
            {
               Log.e(LOGTAG, "startPreview failed");
               mCameraOpenCloseLock.release();
            }
         };
         captureThread = new HandlerThread("CameraCapture");
         captureThread.start();
         captureHandler = new Handler(captureThread.getLooper());
         cameraDevice.createCaptureSession(Arrays.asList(previewSurface, displaySurface), cameraStatusCallback, captureHandler);
      }
      catch (CameraAccessException e)
      {
         Log.e(LOGTAG, "startPreview");
      }
      catch (InterruptedException e)
      {
         throw new RuntimeException("Interrupted while in startPreview", e);
      }
      finally
      {
         //mCameraOpenCloseLock.release();
      }
   }


   @Override
   public void close()
   //-----------------
   {
      Log.i(LOGTAG, "closeCamera");
      try
      {
         mCameraOpenCloseLock.acquire();
         if (null != captureSession)
         {
            captureSession.close();
            captureSession = null;
            isPreviewing = false;
         }
         if (null != cameraDevice)
         {
            cameraDevice.close();
            cameraDevice = null;
         }
         if (cameraThread != null)
            cameraThread.quit();
         cameraHandler = null;
         if (previewThread != null)
            previewThread.quit();
         previewHandler = null;
         if (captureThread != null)
            captureThread.quit();
         captureHandler = null;
      }
      catch (InterruptedException e)
      {
         throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
      }
      finally
      {
         mCameraOpenCloseLock.release();
      }
   }

   public void suspendPreview()
   //--------------------------
   {
      if (previewImageReader != null)
         previewImageReader.setOnImageAvailableListener(null, null);
   }

   public void restartPreview()
   //--------------------------
   {
      if ( (previewImageReader != null) && (imageAvailableListener != null) )
         previewImageReader.setOnImageAvailableListener(imageAvailableListener, cameraHandler);
   }

   @Override
   public void stopPreview()
   //----------------------
   {
      try
      {
         mCameraOpenCloseLock.acquire();
         if (null != captureSession)
         {
            captureSession.close();
            captureSession = null;
            isPreviewing = false;
         }
         displaySurface = renderer.newDisplaySurface();
      }
      catch (InterruptedException e)
      {
         throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
      }
      finally
      {
         mCameraOpenCloseLock.release();
      }
   }

   @Override public boolean isPreviewing() { return isPreviewing; }

   @Override public int getPreviewWidth() { return cameraWidth;}

   @Override public int getPreviewHeight() { return cameraHeight; }

   @Override public int getPreviewBufferSize() { return frameBuffer.getFrameSize(); }

   @Override public void setPreviewWidth(int width) { cameraWidth = width; }

   @Override public  void setPreviewHeight(int height) { cameraHeight = height; }

   @Override
   public String[] availableResolutions() { return resolutions; }

   public boolean setPreviewSize(final int width, final int height, StringBuilder errbuf)
   //------------------------------------------------------------------------------
   {
      Log.i(LOGTAG, "setPreviewSize: " + width + "x" + height);
      if (cameraID == null)
      {
         Log.e(LOGTAG, "Camera isn't initialized!");
         return false;
      }
      CameraManager manager = (CameraManager) surfaceView.getContext().getSystemService(Context.CAMERA_SERVICE);
      try
      {
         CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraID);
         fps = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
         StreamConfigurationMap streamConfig = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
         if (streamConfig != null)
         {
            int[] outputs = streamConfig.getOutputFormats();
            if (outputs != null)
            {
               for (int c : outputs)
               {
                  Log.i(LOGTAG, "Supported Camera Format: " + RecorderActivity.imageFormatString(c));
                  switch (c)
                  {
                     case ImageFormat.YUV_420_888: hasYUV = true; break;
                     case ImageFormat.NV21:        hasNV21 = true; break;
                  }
               }
            }
         }
         else
         {
            hasYUV = (streamConfig.getOutputSizes(ImageFormat.YUV_420_888) != null);
            hasNV21 = (streamConfig.getOutputSizes(ImageFormat.NV21) != null);
         }
         if ((!hasYUV) && (!hasNV21))
         {
            hasYUV = (streamConfig.getOutputSizes(ImageFormat.YUV_420_888) != null);
            hasNV21 = (streamConfig.getOutputSizes(ImageFormat.NV21) != null);
         }

         int bestWidth = 0, bestHeight = 0;
         float aspect = (float) width / height;
         Size[] sizes = null;
         if (streamConfig != null)
            sizes = streamConfig.getOutputSizes(SurfaceTexture.class);
         if (sizes != null)
         {
            int i = 0;
            resolutions = new String[sizes.length];
            for (Size psize : sizes)
            {
               int w = psize.getWidth(), h = psize.getHeight();
               resolutions[i++] = String.format("%dx%d", w, h);
               if ((width >= w) && (height >= h) && (bestWidth <= w) && (bestHeight <= h) &&
                     (Math.abs(aspect - (float) w / h) < 0.2))
               {
                  bestWidth = w;
                  bestHeight = h;
               }
            }
         }
         Log.i(LOGTAG, "best size: " + bestWidth + "x" + bestHeight);
         if ( (bestWidth == 0) || (bestHeight == 0) )
         {
            if (errbuf != null) errbuf.append("Error finding matching preview size for ").append(width).append('x').
                  append(height);
            Log.e(LOGTAG, "Error finding matching preview size");
            return false;
         }
         else
         {
            cameraWidth = bestWidth;
            cameraHeight = bestHeight;
            return true;
         }
      }
      catch (CameraAccessException e)
      {
         Log.e(LOGTAG, "cacPreviewSize - Camera Access Exception");
      }
      catch (IllegalArgumentException e)
      {
         Log.e(LOGTAG, "cacPreviewSize - Illegal Argument Exception");
      }
      catch (SecurityException e)
      {
         Log.e(LOGTAG, "cacPreviewSize - Security Exception");
      }
      return false;
   }

   @Override
   public void freeze(Bundle B)
   //------------------------
   {
      B.putString("cameraID", cameraID);
      B.putInt("cameraWidth", cameraWidth);
      B.putInt("cameraHeight", cameraHeight);
      B.putBoolean("hasYUV", hasYUV);
      B.putBoolean("hasNV21", hasNV21);
      B.putBoolean("isPreviewing", isPreviewing);
      B.putInt("ImageFormat", imageFormat);
   }

   @Override
   public void thaw(Bundle B)
   //--------------------------
   {
      cameraID = B.getString("cameraID");
      cameraWidth = B.getInt("cameraWidth");
      cameraHeight = B.getInt("cameraHeight");
      hasYUV = B.getBoolean("hasYUV");
      hasNV21 = B.getBoolean("hasNV21");
      isPreviewing = B.getBoolean("isPreviewing");
      imageFormat = B.getInt("ImageFormat");
   }

   @Override public PreviewData pop() { throw new RuntimeException("N/A (NativeFrameBuffer writes data)"); }

   @Override public String getPreviewFormat()
   //----------------------------------------
   {
      imageFormat = ((hasYUV) ? ImageFormat.YUV_420_888 : ((hasNV21) ? ImageFormat.NV21 : -1));
      switch (imageFormat)
      {
         case ImageFormat.YUV_420_888: return "YUV_420_888";
         case ImageFormat.NV21:        return "NV21";
         default:                      return "UNKNOWN";
      }
   }

   ByteBuffer imagebuf = null;

   @Override public void releaseFrame() { }

   private final ImageReader.OnImageAvailableListener imageAvailableListener
         = new ImageReader.OnImageAvailableListener()
   //========================================================================
   {
      @Override
      public void onImageAvailable(ImageReader reader)
      //----------------------------------------------
      {
         Image image;
         try { image = reader.acquireNextImage(); } catch (Exception e) { Log.e(LOGTAG, "", e); image = null; }
         if (image == null)
            return;
         if (mustBuffer)
         {
            Image.Plane[] planes = image.getPlanes();
            long timestamp = SystemClock.elapsedRealtimeNanos();
            Image.Plane Yplane = planes[0];
            Image.Plane Uplane = planes[1];
            Image.Plane Vplane = planes[2];
            ByteBuffer Y = Yplane.getBuffer();
            ByteBuffer U = Uplane.getBuffer();
            ByteBuffer V = Vplane.getBuffer();
            Y.rewind();
            U.rewind();
            V.rewind();
            final int ylen = Y.remaining();
            final int ulen = U.remaining();
            final int vlen = V.remaining();
            final int ustride = Uplane.getPixelStride();
            final int vstride = Vplane.getPixelStride();
            frameBuffer.pushYUV(timestamp, Y, ylen, U, ulen, ustride, V, vlen, vstride, 5);
         }
         image.close();
         frameAvailCondVar.open();
      }
   };

   public byte[] toRGBA(Context context, byte[] frame, int previewWidth, int previewHeight, int rgbaSize, byte[] grey)
   //----------------------------------------------------------------------------------------------------------------
   {
      try
      {
         final int inputFormat = ImageFormat.YUV_420_888;
         Type.Builder yuvTypeBuilder = new Type.Builder(renderscript, Element.YUV(renderscript));
         yuvTypeBuilder.setX(previewWidth).setY(previewHeight).setYuvFormat(inputFormat);
         Allocation allocYUVIn = Allocation.createTyped(renderscript, yuvTypeBuilder.create(), Allocation.USAGE_SCRIPT);

         Type.Builder rgbTypeBuilder = new Type.Builder(renderscript, Element.RGBA_8888(renderscript));
         rgbTypeBuilder.setX(previewWidth).setY(previewHeight);
         Allocation allocRGBAOut = Allocation.createTyped(renderscript, rgbTypeBuilder.create(), Allocation.USAGE_SCRIPT);

         ScriptIntrinsicYuvToRGB YUVToRGB = ScriptIntrinsicYuvToRGB.create(renderscript, Element.RGBA_8888(renderscript));
         allocYUVIn.copyFrom(frame);
         YUVToRGB.setInput(allocYUVIn);
         YUVToRGB.forEach(allocRGBAOut);
         byte[] rgbaBuffer = new byte[rgbaSize];
         allocRGBAOut.copyTo(rgbaBuffer);
         if (grey != null)
         {
            Type.Builder greyTypeBuilder = new Type.Builder(renderscript, Element.U8(renderscript));
            greyTypeBuilder.setX(cameraWidth).setY(cameraHeight);
            Allocation allocGrayOut = Allocation.createTyped(renderscript, greyTypeBuilder.create(), Allocation.USAGE_SCRIPT);
            ScriptC_yuv2grey rsYUVtoGrey = new ScriptC_yuv2grey(renderscript);
            allocYUVIn.copyFrom(frame);
            rsYUVtoGrey.set_in(allocYUVIn);
            rsYUVtoGrey.forEach_yuv2grey(allocGrayOut);
            allocGrayOut.copyTo(grey);
         }
         return rgbaBuffer;
      }
      catch (Exception e)
      {
         Log.e(LOGTAG, "", e);
         return null;
      }
   }
}
