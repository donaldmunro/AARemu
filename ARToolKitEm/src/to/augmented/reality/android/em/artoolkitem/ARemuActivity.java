/*
 *  ARActivity.java
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

package to.augmented.reality.android.em.artoolkitem;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ConfigurationInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;
import org.artoolkit.ar.base.AndroidUtils;
import org.artoolkit.ar.base.NativeInterface;
import org.artoolkit.ar.base.camera.CameraEventListener;
import org.artoolkit.ar.base.camera.CameraPreferencesActivity;
import org.artoolkit.ar.base.camera.CaptureCameraPreview;
import org.artoolkit.ar.base.rendering.ARRenderer;
import to.augmented.reality.android.em.artoolkitem.camera.ARemCaptureCameraPreview;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.util.Locale;

public abstract class ARemuActivity extends Activity implements CameraEventListener
//=================================================================================
{
   protected final static String TAG = "ARemuActivity";

   final static protected ARemToolKit ARTK = ARemToolKit.getInstance();

   protected ARRenderer renderer;

   protected FrameLayout mainLayout;

   protected ARemCaptureCameraPreview preview;

   protected GLSurfaceView glView;

   protected boolean firstUpdate = false;

   private int previewWidth = 0, previewHeight = 0;

   protected abstract ARRenderer supplyRenderer();

   protected abstract FrameLayout supplyFrameLayout();

   protected abstract CameraConfiguration supplyConfiguration();

   @Override
   public void onCreate(Bundle savedInstanceState)
   //---------------------------------------------
   {
      super.onCreate(savedInstanceState);

      PreferenceManager.setDefaultValues(this, org.artoolkit.ar.base.R.xml.preferences, false);

      // Correctly configures the activity window for running AR in a layer
      // on top of the camera preview. This includes entering
      // fullscreen landscape mode and enabling transparency.
      requestWindowFeature(Window.FEATURE_NO_TITLE);
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
      getWindow().setFormat(PixelFormat.TRANSLUCENT);
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
      AndroidUtils.reportDisplayInformation(this);
   }

   @Override
   protected void onStart()
   //----------------------
   {
      super.onStart();
      if (ARTK.initialiseNative(this.getCacheDir().getAbsolutePath()) == false)
      { // Use cache directory for Data files.

         new AlertDialog.Builder(this)
               .setMessage("The native library is not loaded. The application cannot continue.")
               .setTitle("Error")
               .setCancelable(true)
               .setNeutralButton(android.R.string.cancel,
                                 new DialogInterface.OnClickListener() {
                                    public void onClick(DialogInterface dialog, int whichButton) {
                                       finish();
                                    }
                                 })
               .show();

         return;
      }

      mainLayout = supplyFrameLayout();
      if (mainLayout == null) {
         Log.e(TAG, "onStart(): Error: supplyFrameLayout did not return a layout.");
         return;
      }

      renderer = supplyRenderer();
      if (renderer == null)
      {
         Log.e(TAG, "onStart(): Error: supplyRenderer did not return a renderer.");
         // No renderer supplied, use default, which does nothing
         renderer = new ARRenderer();
      }

   }
   @SuppressWarnings("deprecation") // FILL_PARENT still required for API level 7 (Android 2.1)
   @Override
   public void onResume()
   //--------------------
   {
      super.onResume();
      // Create the camera preview
      CameraConfiguration conf = supplyConfiguration();
      preview = new ARemCaptureCameraPreview(this, this, conf);

      Log.i(TAG, "onResume(): CaptureCameraPreview created");

      // Create the GL view
      glView = new GLSurfaceView(this);

      // Check if the system supports OpenGL ES 2.0.
      final ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
      final ConfigurationInfo configurationInfo = activityManager.getDeviceConfigurationInfo();
      final boolean supportsEs2 = configurationInfo.reqGlEsVersion >= 0x20000;
      if (!supportsEs2)
         throw new RuntimeException("OpenGL 2 support required");
      glView.setEGLContextClientVersion(2);
      glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
      glView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
      glView.setRenderer(renderer);
      glView.setRenderMode(conf.renderMode);
      glView.setZOrderMediaOverlay( true);

      mainLayout.addView(preview, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT,
                                                             ViewGroup.LayoutParams.FILL_PARENT));
      mainLayout.addView(glView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.FILL_PARENT,
                                                            ViewGroup.LayoutParams.FILL_PARENT));
   }

   public void resizeLayout(int width, int height)
   //---------------------------------------------
   {
      if (mainLayout == null)
         return;
      ViewGroup.LayoutParams params = mainLayout.getLayoutParams();
      params.width = width;
      params.height = height;
      mainLayout.setLayoutParams(params);
   }

   @Override
   protected void onPause()
   //---------------------
   {
      //Log.i(TAG, "onPause()");
      super.onPause();

      if (glView != null) glView.onPause();

      // System hardware must be released in onPause(), so it's available to
      // any incoming activity. Removing the CameraPreview will do this for the
      // camera. Also do it for the GLSurfaceView, since it serves no purpose
      // with the camera preview gone.
      mainLayout.removeView(glView);
      mainLayout.removeView(preview);
   }

   @Override
   public void onStop()
   //------------------
   {
      Log.i(TAG, "onStop(): Activity stopping.");
      super.onStop();
   }

   @Override
   public boolean onCreateOptionsMenu(Menu menu)
   //-------------------------------------------
   {
      MenuInflater inflater = getMenuInflater();
      inflater.inflate(org.artoolkit.ar.base.R.menu.options, menu);
      return true;
   }

   @Override
   public boolean onOptionsItemSelected(MenuItem item)
   //-------------------------------------------------
   {
      if (item.getItemId() == org.artoolkit.ar.base.R.id.settings) {
         startActivity(new Intent(this, CameraPreferencesActivity.class));
         return true;
      } else {
         return super.onOptionsItemSelected(item);
      }
   }

   public CaptureCameraPreview getCameraPreview() { return preview; }

   public GLSurfaceView getGLView() { return glView; }

   @Override
   public void cameraPreviewStarted(int width, int height, int rate, int cameraIndex, boolean cameraIsFrontFacing)
   //--------------------------------------------------------------------------------------------------------------
   {
      resizeLayout(width, height);
      if (ARTK.initialiseARem(width, height, "Data/camera_para.dat", cameraIndex, cameraIsFrontFacing))
      {
         Log.i(TAG, "getGLView(): Camera initialised");
         previewWidth = width;
         previewHeight = height;
      }
      else
         throw new RuntimeException("getGLView(): Error initialising camera. Cannot continue.");

      Toast.makeText(this, "Camera settings: " + width + "x" + height + "@" + rate + "fps", Toast.LENGTH_SHORT).show();
      firstUpdate = true;
   }

   int seq = 1;

   @Override
   public void cameraPreviewFrame(byte[] frame)
   //------------------------------------------
   {

      if (firstUpdate)
      {
         // ARToolKit has been initialised. The renderer can now add markers, etc...
         if (renderer.configureARScene())
            Log.i(TAG, "cameraPreviewFrame(): Scene configured successfully");
         else
            throw new RuntimeException("cameraPreviewFrame(): Error configuring scene. Cannot continue.");
         firstUpdate = false;
      }

      if (ARTK.convertAndDetect(frame))
      {
         if (glView != null)
            glView.requestRender();
//         SimpleGLES20Renderer glesRenderer = (SimpleGLES20Renderer) renderer;
//         for (int i=0; i<glesRenderer.getNoMarkers(); i++)
//         {
//            int mid = glesRenderer.getMarkerID(i);
//            if ( (mid >= 0) && (ARTK.queryMarkerVisible(mid)) )
//               Log.i(TAG, "Visible: " + mid);
//               save(seq++, previewWidth, previewHeight, frame);
//         }
         onFrameProcessed();
      }
      else
         Log.w(TAG, "convertAndDetect failed");

   }

   static public void save(int seq, int width, int height, byte[] frame)
   //-------------------------------------------------------------------
   {
      String name = String.format(Locale.US, "/sdcard/artoolkit-%06d.png", seq);
      BufferedOutputStream bos = null;
      try
      {
         bos = new BufferedOutputStream(new FileOutputStream(name), 65535);
         int[] colors = new int[frame.length/4];
         int i=0, j = 0;
         while (i<frame.length)
         {
            int r = (int) frame[i++];
            if (r < 0) r = 256 + r;
            int g = (int) frame[i++];
            if (g < 0) g = 256 + g;
            int b = (int) frame[i++];
            if (b < 0) b = 256 + b;
            int a = frame[i++];
            if (a < 0) a = 256 + a;
            colors[j++] = Color.argb(a, r, g, b);
         }
         Bitmap bmp = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
         bmp.compress(Bitmap.CompressFormat.PNG, 0, bos);
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
      }
      finally
      {
         if (bos != null)
            try { bos.close(); } catch (Exception _e) {}
      }
   }

   public void onFrameProcessed() { }

   @Override
   public void cameraPreviewStopped() { ARTK.cleanup(); }

   protected void showInfo()
   //-----------------------
   {

      AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(this);

      dialogBuilder.setMessage("ARToolKit Version: " + NativeInterface.arwGetARToolKitVersion());

      dialogBuilder.setCancelable(false);
      dialogBuilder.setPositiveButton("Close", new DialogInterface.OnClickListener() {
         public void onClick(DialogInterface dialog, int id) {
            dialog.cancel();
         }
      });

      AlertDialog alert = dialogBuilder.create();
      alert.setTitle("ARToolKit");
      alert.show();


   }

}
