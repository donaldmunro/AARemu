/*
 *  CaptureCameraPreview.java
 *  ARToolKit5
 *
 *  This file is part of ARToolKit.
 *
 *  ARToolKit is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Lesser General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  ARToolKit is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with ARToolKit.  If not, see <http://www.gnu.org/licenses/>.
 *
 *  As a special exception, the copyright holders of this library give you
 *  permission to link this library with independent modules to produce an
 *  executable, regardless of the license terms of these independent modules, and to
 *  copy and distribute the resulting executable under terms of your choice,
 *  provided that you also meet, for each linked independent module, the terms and
 *  conditions of the license of that module. An independent module is a module
 *  which is neither derived from nor based on this library. If you modify this
 *  library, you may extend this exception to your version of the library, but you
 *  are not obligated to do so. If you do not wish to do so, delete this exception
 *  statement from your version.
 *
 *  Copyright 2015 Daqri, LLC.
 *  Copyright 2011-2015 ARToolworks, Inc.
 *
 *  Author(s): Julian Looser, Philip Lamb
 *
 */

package to.augmented.reality.android.em.artoolkitem.camera;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.Camera;
import android.util.Log;
import android.view.SurfaceHolder;
import org.artoolkit.ar.base.FPSCounter;
import org.artoolkit.ar.base.camera.CameraEventListener;
import org.artoolkit.ar.base.camera.CaptureCameraPreview;
import to.augmented.reality.android.em.ARCamera;
import to.augmented.reality.android.em.artoolkitem.CameraConfiguration;

import java.io.File;
import java.io.IOException;

public class ARemCaptureCameraPreview extends CaptureCameraPreview implements SurfaceHolder.Callback, Camera.PreviewCallback
//===========================================================================================================================
{
   private static final String TAG = "CaptureCameraPreview";

   protected final Context context;

   protected CameraConfiguration configuration;

   private ARCamera camera = null;

   private int captureWidth;

   private int captureHeight;

   private int captureRate;

   private FPSCounter fpsCounter = new FPSCounter();

   private CameraEventListener listener;

   public ARemCaptureCameraPreview(Context context, CameraEventListener cel, CameraConfiguration conf)
   //------------------------------------------------------------------------------------------
   {
      super(context, cel);
      SurfaceHolder holder = this.getHolder();
      holder.removeCallback(this);
      holder.addCallback(this);
      this.context = context;
      this.configuration = conf;
      setCameraEventListener(cel);
   }

   public void setCameraEventListener(CameraEventListener cel) { listener = cel; }


   @SuppressLint("NewApi")
   @Override
   public void surfaceCreated(SurfaceHolder holder)
   //----------------------------------------------
   {
      int cameraIndex = configuration.cameraNo;
      try
      {
         camera = new ARCamera(context, cameraIndex, null, new File(configuration.recordingDirectory),
                               configuration.isRepeat);
         camera = ARCamera.open();
         Camera.Parameters params = camera.getParameters();
         params.setPreviewFrameRate(configuration.fps);
         camera.setParameters(params);
         camera.setRenderMode(configuration.renderMode);
      }
      catch (Exception e)
      {
         Log.e(TAG, "surfaceCreated(): Cannot open camera.", e);
         return;
      }
      try
      {
         camera.setPreviewDisplay(holder);
      }
      catch (IOException exception)
      {
         Log.e(TAG, "surfaceCreated(): IOException setting display holder");
         camera.release();
         camera = null;
         Log.i(TAG, "surfaceCreated(): Released camera");
         return;
      }

   }

   @Override
   public void surfaceDestroyed(SurfaceHolder holder)
   //------------------------------------------------
   {

      if (camera != null)
      {
         camera.setPreviewCallback(null);
         camera.stopPreview();
         camera.release();
         camera = null;
      }

      if (listener != null) listener.cameraPreviewStopped();
   }


   @SuppressWarnings("deprecation") // setPreviewFrameRate, getPreviewFrameRate
   @Override
   public void surfaceChanged(SurfaceHolder holder, int format, int w, int h)
   //------------------------------------------------------------------------
   {

      if (camera == null)
      {
         // Camera wasn't opened successfully?
         Log.e(TAG, "surfaceChanged(): No camera in surfaceChanged");
         return;
      }

      Log.i(TAG, "surfaceChanged(): Surfaced changed, setting up camera and starting preview");


      Camera.Parameters parameters = camera.getParameters();
      captureWidth = parameters.getPreviewSize().width;
      captureHeight = parameters.getPreviewSize().height;
      captureRate = parameters.getPreviewFrameRate();
      int pixelformat = parameters.getPreviewFormat(); // android.graphics.imageformat
      if (pixelformat == ImageFormat.UNKNOWN)
         pixelformat = PixelFormat.RGBA_8888;
      PixelFormat pixelinfo = new PixelFormat();
      PixelFormat.getPixelFormatInfo(pixelformat, pixelinfo);
      int cameraIndex = 0;
      boolean cameraIsFrontFacing = false;
      cameraIsFrontFacing = false;

      int bufSize = captureWidth * captureHeight * pixelinfo.bitsPerPixel / 8;

      camera.setPreviewCallback(this);
      camera.startPreview();

      if (listener != null)
         listener.cameraPreviewStarted(captureWidth, captureHeight, captureRate, cameraIndex, cameraIsFrontFacing);

   }

   @Override
   public void onPreviewFrame(byte[] data, Camera camera)
   //----------------------------------------------------
   {

      if (listener != null)
         listener.cameraPreviewFrame(data);
      if (fpsCounter.frame())
         Log.i(TAG, "onPreviewFrame(): Camera capture FPS: " + fpsCounter.getFPS());
   }

}
