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
package to.augmented.reality.android.aremu.opencv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.SystemClock;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicYuvToRGB;
import android.renderscript.Type;
import android.util.AttributeSet;
import android.util.Log;
import android.view.ViewGroup;

import org.opencv.android.CameraBridgeViewBase;
import org.opencv.android.JavaCameraView;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import to.augmented.reality.android.em.ARCamera;

/**
 OpenCV4Android provides an Android View which users can use in their user interfaces which provides
 camera preview and display facilities by using the Android Camera class and which is based
 on an abstract class called CameraBridgeViewBase. EmulatorCameraView is an ARem supplied class which
 is also derived from CameraBridgeViewBase and provides view that utilises the ARCamera class instead of the Android
 Camera class. This is implemented in a separate module to avoid making the base functionality in the AARemu framework
 dependent on OpenCV.
 */
public class EmulatorCameraView extends CameraBridgeViewBase implements Camera.PreviewCallback
//============================================================================================
{
   final static private String TAG = EmulatorCameraView.class.getSimpleName();
   private static final int MAGIC_TEXTURE_ID = 10;

   protected ARCamera camera;

   public ARCamera getArEmCamera() { return camera; }
   final protected Context context;
   private SurfaceTexture surfaceTexture = null;
   private File headerFile = null, framesFile = null, orientationFile = null, locationFile = null;
   public File getHeaderFile() { return headerFile; }
   public File getFramesFile() { return framesFile; }
   public File getOrientationFile() { return orientationFile; }
   public File getLocationFile() { return locationFile; }

   private byte[] buffer = null;
   private Bitmap cacheBitmap;
   private FrameRingBuffer frameBuffer;
   private ExecutorService previewPool;
   private Future<?> previewFuture;

   public EmulatorCameraView(Context context, int cameraId) { super(context, cameraId); this.context = context; }

   public EmulatorCameraView(Context context, AttributeSet attrs)
   {
      super(context, attrs);
      this.context = context;
   }

   /**
    * Sets the recording files used by the ARCamera class for playback.
    * @param headerFile
    * @param framesFile
    * @return
    */
   final public boolean setRecordingFiles(File headerFile, File framesFile)
   //----------------------------------------------------------------------
   {
      return setRecordingFiles(headerFile, framesFile, null, null);
   }

   /**
    * Sets the recording files used by the ARCamera class for playback.
    * @param headerFile
    * @param framesFile
    * @param orientationFile File containing orientation data (for RecordingType.FREE only)
    * @param locationFile File containing location data (for RecordingType.FREE only)
    * @return
    */
   final public boolean setRecordingFiles(File headerFile, File framesFile, File orientationFile,
                                          File locationFile)
   //---------------------------------------------------------------------------------------------
   {
      if (headerFile == null)
      {
         this.headerFile = this.framesFile = null;
         disconnectCamera();
         return true;
      }
      if ( (! headerFile.exists()) || (! headerFile.canRead()) )
      {
         Log.e(TAG, "Header file " + headerFile.getAbsolutePath() + " does not exist or is not readable");
         return false;
      }
      if ( (framesFile != null) && ( (! framesFile.exists()) || (! framesFile.canRead()) ) )
      {
         Log.e(TAG, "Frames file " + framesFile.getAbsolutePath() + " does not exist or is not readable");
         return false;
      }
      if ( (this.headerFile != null) && (this.headerFile.equals(headerFile)) &&
           (this.framesFile != null) && (this.framesFile.equals(framesFile)) &&
           (previewFuture != null) && (! previewFuture.isDone()) )
         return true;
      this.headerFile = headerFile;
      this.framesFile = framesFile;
      this.orientationFile = orientationFile;
      this.locationFile = locationFile;
      connectCamera(getWidth(), getHeight());
      return true;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   protected boolean connectCamera(int width, int height)
   //----------------------------------------------------
   {
      if (headerFile == null)
         return true;
      if (! initializeCamera(width, height))
         return false;
      if (previewPool != null)
         previewPool.shutdownNow();
      previewPool = Executors.newSingleThreadExecutor(new ThreadFactory()
      {
         @Override
         public Thread newThread(Runnable r)
         {
            Thread t = new Thread(r);
            t.setDaemon(true);
            t.setName("Frame Preview");
            return t;
         }
      });
      previewFuture = previewPool.submit(new FrameThread());
      camera.startPreview();
      return true;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   protected void disconnectCamera()
   //------------------------------
   {
      mustStop = true;
      frameAvailCond.open();
      if (camera != null)
         camera.release();
      try
      {
         previewFuture.get(400, TimeUnit.MILLISECONDS);
      }
      catch (Exception e)
      {
         previewFuture.cancel(true);
         previewPool.shutdownNow();
      }
   }

   private boolean initializeCamera(int width, int height)
   //-----------------------------------------------------
   {
      boolean result = true;
      synchronized (this)
      {
         if (camera != null)
            try { camera.release(); } catch (Exception _e) {}
         camera = null;
         Camera realCamera = null;
         int backId =-1, frontId =-1;
         Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
         for (int i = 0; i < Camera.getNumberOfCameras(); i++)
         {
            Camera.getCameraInfo(i, cameraInfo);
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK)
               backId= i;
            else if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT)
               frontId = i;
         }
         if (mCameraIndex == CAMERA_ID_ANY)
         {
            int id = (backId >= 0) ? backId : (frontId >= 0) ? frontId : -1;
            if (id >= 0)
               try { realCamera = Camera.open(id); } catch (Exception e) { realCamera = null; Log.e(TAG, "camera id " + id, e); }
            if (realCamera == null)
            {
               id = (id == backId) ? frontId : backId;
               try { realCamera = Camera.open(id); } catch (Exception e) { realCamera = null; Log.e(TAG, "camera id " + id, e); }
            }
            if (realCamera != null)
               new ARCamera(context, id, realCamera);
            else
            {
               new ARCamera(context, -1);
               id = -1;
            }
            camera = (ARCamera) ARCamera.open(id);
         }
         else
         {
            int id =-1;
            if (mCameraIndex == CAMERA_ID_BACK)
            {
               try { realCamera = Camera.open(backId); } catch (Exception e) { realCamera = null; }
               id = backId;
            }
            else if (mCameraIndex == CAMERA_ID_FRONT)
            {
               try { realCamera = Camera.open(frontId); } catch (Exception e) { realCamera = null; }
               id = frontId;
            }
            if (realCamera != null)
               new ARCamera(context, id, realCamera);
            else
               new ARCamera(context, id);
            camera = (ARCamera) ARCamera.open(id);
         }

         if (camera == null)
            return false;
         try
         {
            if (headerFile != null)
               camera.setFiles(headerFile, framesFile, orientationFile, locationFile);
            else
            {
               Log.w(TAG, "Recording files not set in initializeCamera. Call setRecordingFiles in onCreate.");
               return true;
            }
            Camera.Parameters params = camera.getParameters();
            List<Camera.Size> sizes = params.getSupportedPreviewSizes();
            if (sizes != null)
            {
               Size frameSize = calculateCameraFrameSize(sizes, new JavaCameraView.JavaCameraSizeAccessor(), width, height);
               params.setPreviewSize((int) frameSize.width, (int) frameSize.height);
               camera.setParameters(params);
               params = camera.getParameters();

               mFrameWidth = params.getPreviewSize().width;
               mFrameHeight = params.getPreviewSize().height;

               if ( (getLayoutParams().width == ViewGroup.LayoutParams.MATCH_PARENT) &&
                    (getLayoutParams().height == ViewGroup.LayoutParams.MATCH_PARENT) )
                  mScale = Math.min(((float) height) / mFrameHeight, ((float) width) / mFrameWidth);
               else
                  mScale = 0;

               if (mFpsMeter != null)
                  mFpsMeter.setResolution(mFrameWidth, mFrameHeight);
               int bufferSize = camera.getPreviewBufferSize();
               buffer = new byte[bufferSize];

               camera.addCallbackBuffer(buffer);
               camera.setPreviewCallbackWithBuffer(this);

               frameBuffer = new FrameRingBuffer(2, bufferSize);
               AllocateCache();
//               cameraFrames = new AREmuCameraFrame[2];
//               cameraFrames[0] = new AREmuCameraFrame(frameChain[0], camera.getFileFormat(), mFrameWidth, mFrameHeight);
//               cameraFrames[1] = new AREmuCameraFrame(frameChain[1], camera.getFileFormat(), mFrameWidth, mFrameHeight);

               if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB)
               {
                  surfaceTexture = new SurfaceTexture(MAGIC_TEXTURE_ID);
                  camera.setPreviewTexture(surfaceTexture);
               } else
                  camera.setPreviewDisplay(null);
               camera.startPreview();
            }
            else
               result = false;
         }
         catch (Exception e)
         {
            result = false;
            Log.e(TAG, "", e);
         }
      }
      return result;
   }

   /**
    * {@inheritDoc}
    */
   @Override
   public void onPreviewFrame(byte[] data, Camera camera)
   //----------------------------------------------------
   {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
         frameBuffer.push(SystemClock.elapsedRealtimeNanos(), data);
      else
         frameBuffer.push(System.nanoTime(), data);
      frameAvailCond.open();
      if (this.camera != null)
         this.camera.addCallbackBuffer(buffer);
   }

   final ConditionVariable frameAvailCond = new ConditionVariable(false);
   private boolean mustStop = false;

   private class FrameThread implements Runnable
   //===========================================
   {
      private final ARCamera.RecordFileFormat format;

      private FrameThread()
      {
         format = camera.getFileFormat();
      }

      @Override
      public void run()
      //---------------
      {
         try
         {
            byte[] buffer;
            AREmuCameraFrame frame = new AREmuCameraFrame(camera.getPreviewBufferSize(), format, mFrameWidth, mFrameHeight);
            while (!mustStop)
            {
               if (frameAvailCond.block(300))
               {
                  frameAvailCond.close();
                  buffer = frameBuffer.pop();
                  if (buffer != null)
                  {
                     frame.set(buffer);
                     deliverAndDrawFrame(frame);
                  }
               }
            }
         }
         catch (Exception e)
         {
            Log.e(TAG, "FrameThread", e);
            throw new RuntimeException("FrameThread", e);
         }
      }
   }

   private class AREmuCameraFrame implements CvCameraViewFrame
   //=========================================================
   {
      final int width, height;
      final ARCamera.RecordFileFormat format;
      byte[] data;
      Mat rgba, rgb, grey = new Mat(), nv21;
      RenderScript rsYUVtoRGBA = null;
      ScriptIntrinsicYuvToRGB YUVToRGB = null;
      Allocation aIn, aOut;
      byte[] rgbaBuffer = null;

      private AREmuCameraFrame(int bufferSize, ARCamera.RecordFileFormat format, int width, int height)
      //-----------------------------------------------------------------------------------------
      {
         this.width = width;
         this.height = height;
         this.format = format;
         this.data  = new byte[bufferSize];
         rgba = new Mat(height, width, CvType.CV_8UC4); // NOT width, height !
         switch (format)
         {
            case RGBA:  rgb = new Mat(height, width, CvType.CV_8UC3); break;
            case NV21:
               nv21 = new Mat(height + (height/2), width, CvType.CV_8UC1);
               try
               {
                  rsYUVtoRGBA = RenderScript.create(context);
                  Type.Builder yuvType = new Type.Builder(rsYUVtoRGBA, Element.U8(rsYUVtoRGBA)).setX(width).
                        setY(height).setMipmaps(false);
                  yuvType.setYuvFormat(ImageFormat.NV21);
                  aIn = Allocation.createTyped(rsYUVtoRGBA, yuvType.create(), Allocation.USAGE_SCRIPT);
                  Type.Builder rgbType = null;
                  YUVToRGB = ScriptIntrinsicYuvToRGB.create(rsYUVtoRGBA, Element.U8_4(rsYUVtoRGBA));
                  rgbType = new Type.Builder(rsYUVtoRGBA, Element.RGBA_8888(rsYUVtoRGBA));
                  rgbaBuffer = new byte[width*height*4];
                  rgbType.setX(width).setY(height).setMipmaps(false);
                  aOut = Allocation.createTyped(rsYUVtoRGBA, rgbType.create(), Allocation.USAGE_SCRIPT);
               }
               catch (Exception e)
               {
                  rsYUVtoRGBA = null;
                  YUVToRGB = null;
                  Log.e(TAG, "Renderscript NV21 to RGBA conversion setup failed. Using OpenCV cvtColor", e);
               }
               break;
         }
      }

      public void set(byte[] data)
      //--------------------------
      {
         System.arraycopy(data, 0, this.data, 0, data.length);
      }

      @Override
      public Mat rgba()
      //---------------
      {
         switch (format)
         {
            case RGBA:     rgba.put(0, 0, data); break;
            case NV21:
               if (YUVToRGB == null)
               {
                  nv21.put(0, 0, data);
                  Imgproc.cvtColor(nv21, rgba, Imgproc.COLOR_YUV2RGBA_NV21, 4);
               }
               else
               {
                  aIn.copyFrom(data);
                  YUVToRGB.setInput(aIn);
                  YUVToRGB.forEach(aOut);
                  aOut.copyTo(rgbaBuffer);
                  rgba.put(0, 0, rgbaBuffer);
               }
               break;
            default:       throw new UnsupportedOperationException(format.name() +  " not yet supported");
         }
         return rgba;
      }

      @Override
      public Mat gray()
      //--------------
      {
         Imgproc.cvtColor(rgba(), grey, Imgproc.COLOR_RGBA2GRAY, 1);
         return grey;
      }
   }

   public class FrameRingBuffer
   //==========================
   {
      class RingBufferContent
      //=====================
      {
         long timestamp;
         byte[] buffer;

         RingBufferContent(int bufferSize) { buffer = new byte[bufferSize]; timestamp = -1; }
      }

      RingBufferContent[] buffers;
      int head, tail, length, count, size;
      public synchronized int length() { return length; }

      public FrameRingBuffer(int count, int size)
      //-----------------------------------------
      {
         buffers = new RingBufferContent[count];
         for (int i=0; i<count; i++)
            buffers[i] = new RingBufferContent(size);
         this.count = count;
         this.size = size;
         head = tail = length = 0;
      }

      public synchronized void clear() { head = tail = length = 0; }

      public boolean isEmpty() { return (length == 0); }

      public boolean isFull() { return (length >= count); }

      public synchronized int push(long timestamp, byte[] buffer)
      //---------------------------------------------------------
      {
         if (length >= count)
         {
            tail = indexIncrement(tail);
            length--;
         }
         buffers[head].timestamp = timestamp;
         System.arraycopy(buffer, 0, buffers[head].buffer, 0, size);
         head = indexIncrement(head);
         length++;
         return count - length;
      }

      public synchronized byte[] pop()
      //-----------------------------------------
      {
         byte[] data = null;
         if (length > 0)
         {
            data = buffers[tail].buffer;
            tail = indexIncrement(tail);
            length--;
         }
         return data;
      }

      public long peek(byte[] buffer)
      //----------------------------
      {
         if (length > 0)
         {
            System.arraycopy(buffers[tail].buffer, 0, buffer, 0, size);
            return buffers[tail].timestamp;
         }
         return -1;
      }

      private int indexIncrement(int i)
      {
         return  (++i >= count) ? 0 : i;
      }

//   private int indexDecrement(int i) { return (0 == i) ? (count - 1) : (i - 1);  }
   }

}
