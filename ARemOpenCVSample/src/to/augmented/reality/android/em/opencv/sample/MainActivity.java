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

package to.augmented.reality.android.em.opencv.sample;

import android.app.*;
import android.hardware.*;
import android.location.*;
import android.os.*;
import android.util.*;
import android.view.*;
import android.widget.*;
import com.doubleTwist.drawerlib.*;
import org.opencv.android.*;
import org.opencv.core.*;
import to.augmented.reality.android.aremu.opencv.*;
import to.augmented.reality.android.em.*;

import java.io.*;
import java.util.*;

public class MainActivity extends Activity implements OpenDialog.DialogCloseable
//==============================================================================
{
   final static String TAG = MainActivity.class.getSimpleName();
   final static File DIR = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                                    "ARRecorder");
   final static int DEFAULT_REVIEW_DELAY = 40; //ms

   private File headerFile = null, framesFile = null;
   boolean isDrawerOpen = false, isOpeningDrawer = false;
   Bundle lastInstanceState;

   private ADrawerLayout drawerLayout;
   EmulatorCameraView cameraView;
   private TextView resolutionText, incrementText, locationText, bearingText;
   private View decorView = null;

   int uiOptions;
   final Handler hideHandler = new Handler();
   private int displayFlags = 0;
   final private Runnable hideRunner = new Runnable()
   //================================================
   {
      @Override public void run()
      //-------------------------
      {
         decorView.setSystemUiVisibility(uiOptions);
         ActionBar actionBar = getActionBar();
         if (actionBar != null)
            actionBar.hide();
      }
   };

   @Override
   protected void onCreate(Bundle B)
   //-------------------------------
   {
      super.onCreate(B);
      requestWindowFeature(Window.FEATURE_NO_TITLE);
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
      setupFullScreen();
      setContentView(R.layout.activity_main);
      drawerLayout = (ADrawerLayout) findViewById(R.id.drawer);
      int contentPadding = getResources().getDimensionPixelSize(R.dimen.drawer_padding);
      drawerLayout.setRestrictTouchesToArea(ADrawerLayout.LEFT_DRAWER, contentPadding);
      drawerLayout.setParalaxFactorX(0.5f);
      drawerLayout.setListener(new ADrawerLayout.DrawerLayoutListener()
      {
         // @formatter:off
         @Override public void onBeginScroll(ADrawerLayout dl, ADrawerLayout.DrawerState state) { }
         @Override public void onOffsetChanged(ADrawerLayout dl, ADrawerLayout.DrawerState state, float offsetXNorm, float offsetYNorm, int offsetX, int offsetY) { }
         @Override public void onPreClose(ADrawerLayout dl, ADrawerLayout.DrawerState state) { }

         @Override public void onPreOpen(ADrawerLayout dl, ADrawerLayout.DrawerState state) { isOpeningDrawer = true; }

         @Override public void onClose(ADrawerLayout dl, ADrawerLayout.DrawerState state, int closedDrawerId)
         //--------------------------------------------------------------------------------------------------
         {
            isDrawerOpen = isOpeningDrawer = false;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
            {
               hideHandler.removeCallbacks(hideRunner);
               hideHandler.postDelayed(hideRunner, 500);
            }
         }

         @Override
         public void onOpen(ADrawerLayout dl, ADrawerLayout.DrawerState state)
         //-------------------------------------------------------------------
         {
            isDrawerOpen = true;
            isOpeningDrawer = false;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
            {
               hideHandler.removeCallbacks(hideRunner);
               hideHandler.postDelayed(hideRunner, 500);
            }
         }
         // @formatter:on
      });
      drawerLayout.open();

      cameraView = (EmulatorCameraView) findViewById(R.id.emulator_view);
      cameraView.setVisibility(SurfaceView.VISIBLE);
      cameraView.setCvCameraViewListener(new CameraViewCallback());
      resolutionText = (TextView) findViewById(R.id.resolution_text);
      incrementText = (TextView) findViewById(R.id.text_increment);
      locationText = (TextView) findViewById(R.id.location_text);
      bearingText = (TextView) findViewById(R.id.bearing_text);

   }


   private class UpdateBearingRunner implements Runnable
   //============================================
   {
      volatile public float bearing;
      @Override public void run() { bearingText.setText(String.format("%.4f", bearing)); }
   };
   final private UpdateBearingRunner updateBearingRunner = new UpdateBearingRunner();

   private class UpdateLocationRunner implements Runnable
   //=====================================================
   {
      volatile public Location location;
      @Override public void run() { locationText.setText(formatLocation(location)); }
   };
   final private UpdateLocationRunner updateLocationRunner = new UpdateLocationRunner();

   private void startPreview()
   //-------------------------
   {
      if ( (headerFile == null) || (! headerFile.exists()) || (! headerFile.canRead()) )
      {
         Toast.makeText(MainActivity.this, "Open a valid recording first.", Toast.LENGTH_LONG).show();
         return;
      }
      if ( (framesFile == null) || (! framesFile.exists()) || (! framesFile.canRead()) )
      {
         Toast.makeText(MainActivity.this, "Open a valid recording first.", Toast.LENGTH_LONG).show();
         return;
      }
//      cameraView.setRecordingFiles(headerFile, framesFile);
      ARCamera camera = cameraView.getArEmCamera();
      camera.setLocationListener(new LocationListener()
      //===============================================
      {
         @Override public void onLocationChanged(final Location location)
         //--------------------------------------------------------------
         {
            if (! isDrawerOpen) return;
            updateLocationRunner.location = location;
            MainActivity.this.runOnUiThread(updateLocationRunner);
         }
         @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
         @Override public void onProviderEnabled(String provider) { }
         @Override public void onProviderDisabled(String provider) { }
      });
      camera.setBearingListener(new BearingListener()
      //=============================================
      {
         @Override
         public void onBearingChanged(float bearing)
         //-----------------------------------------
         {
            if (! isDrawerOpen) return;
            updateBearingRunner.bearing = bearing;
            MainActivity.this.runOnUiThread(updateBearingRunner);
         }
      });

   }

   private void setupFullScreen()
   //----------------------------
   {
      ActionBar actionBar = getActionBar();
      if (actionBar != null)
         actionBar.hide();
      View.OnSystemUiVisibilityChangeListener visibilityListener = new View.OnSystemUiVisibilityChangeListener()
      {
         @Override
         public void onSystemUiVisibilityChange(int visibility)
         //--------------------------------------------------------------
         {
            MainActivity.this.displayFlags = visibility;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            {
               hideHandler.removeCallbacks(hideRunner);
               hideHandler.postDelayed(hideRunner, 1000);
            }
//               if ((displayFlags & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0)
//               {
//                  hideHandler.removeCallbacks(hideRunner);
//                  hideHandler.postDelayed(hideRunner, 600);
//               }
         }
      };
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.ICE_CREAM_SANDWICH)
         getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
      else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN)
      {
         decorView = getWindow().getDecorView();
         uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
               View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN;
         decorView.setSystemUiVisibility(uiOptions);
         decorView.setOnSystemUiVisibilityChangeListener(visibilityListener);
      }
      else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
      {
         decorView = getWindow().getDecorView();
         uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
               View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
         decorView.setSystemUiVisibility(uiOptions);
         decorView.setOnSystemUiVisibilityChangeListener(visibilityListener);
      }
      else
      {
         decorView = getWindow().getDecorView().findViewById(android.R.id.content);
         uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
               View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION  |
               View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
         decorView.setSystemUiVisibility(uiOptions);
      }
   }

   @Override
   public void onWindowFocusChanged(boolean hasFocus)
   //------------------------------------------------
   {
      super.onWindowFocusChanged(hasFocus);
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
         if (hasFocus)
            decorView.setSystemUiVisibility(uiOptions);
   }

   public static String formatLocation(final Location location)
   //----------------------------------------------------------
   {
      String latitudeDirection = "N";
      float latitude = (float) location.getLatitude();
      if (location.getLatitude() < 0)
      {
         latitude = -latitude;
         latitudeDirection = "S";
      }
      String longitudeDirection = "W";
      float longitude = (float) location.getLongitude();
      if (location.getLatitude() < 0)
      {
         longitude = -longitude;
         longitudeDirection = "E";
      }
      return String.format("%8.4f%s %8.4f%s", latitude, latitudeDirection, longitude, longitudeDirection);
   }

   public void onOpen(View view)
   //---------------------------
   {
      FragmentTransaction ft = getFragmentManager().beginTransaction();
      OpenDialog dialog = OpenDialog.instance(DIR);
      dialog.show(ft, "OpenDialog");
   }

   @Override
   public void onDialogClosed(File dir, String filename, boolean isCancelled)
   //------------------------------------------------------------------------
   {
      if (! isCancelled)
      {
         final String basename;
         int p = filename.lastIndexOf('.');
         if (p > 0)
            basename = filename.substring(0, p);
         else
            basename = filename;
         headerFile = new File(dir, basename + ".head");
         if ( (headerFile == null) || (! headerFile.exists()) || (! headerFile.canRead()) )
         {
            Toast.makeText(MainActivity.this, headerFile.getAbsolutePath() + " not found or not readable", Toast.LENGTH_LONG).show();
            return;
         }
         framesFile = new File(dir, basename + ".frames");
         if ( (framesFile == null) || (! framesFile.exists()) || (! framesFile.canRead()) )
         {
            Toast.makeText(MainActivity.this, framesFile.getAbsolutePath() + " not found or not readable", Toast.LENGTH_LONG).show();
            return;
         }
         cameraView.setRecordingFiles(headerFile, framesFile);
         ARCamera camera = cameraView.getArEmCamera();
         if (camera == null)
         {
            Toast.makeText(this, "Could not obtain camera", Toast.LENGTH_LONG).show();
            return;
         }
         Camera.Parameters parameters = camera.getParameters();
         Camera.Size sz = parameters.getPreviewSize();
         int width = sz.width;
         int height = sz.height;
         if ( (width < 0) || (height < 0) )
         {
            Toast.makeText(MainActivity.this, headerFile.getAbsolutePath() + " does not contain preview width or height", Toast.LENGTH_LONG).show();
            return;
         }
         float increment = camera.getHeaderFloat("Increment", -1);
         if (increment < 0)
         {
            Toast.makeText(MainActivity.this, headerFile.getAbsolutePath() + " does not contain frame increment", Toast.LENGTH_LONG).show();
            return;
         }
         resolutionText.setText(String.format(Locale.US, "%dx%d", width, height));
         incrementText.setText(String.format(Locale.US, "%.1f", increment));
         String location = camera.getHeader("Location", "");
         locationText.setText(location.replaceAll("\\s+", ""));

         startPreview();
         drawerLayout.close();
      }
   }

   @Override
   protected void onSaveInstanceState(Bundle B)
   //-------------------------------------------------
   {
      super.onSaveInstanceState(B);
      B.putString("headerFile", headerFile.getAbsolutePath());
      B.putString("framesFile", framesFile.getAbsolutePath());
      lastInstanceState = new Bundle(B);
   }

   @Override
   protected void onPause()
   //----------------------
   {
      super.onPause();
      if (cameraView != null)
      {
         if ( (cameraView.getArEmCamera() != null))
            cameraView.getArEmCamera().release();
         cameraView.disableView();
      }
   }

   @Override
   protected void onRestoreInstanceState(Bundle B)
   //---------------------------------------------------------------
   {
      super.onRestoreInstanceState(B);
      if (B != null)
      {
         String s = B.getString("headerFile");
         if (s != null)
            headerFile = new File(s);
         s = B.getString("framesFile");
         if (s != null)
            framesFile = new File(s);
      }
   }

   private BaseLoaderCallback loaderCallback = new BaseLoaderCallback(this)
   //======================================================================
   {
      @Override
      public void onManagerConnected(int status)
      {
         switch (status)
         {
            case LoaderCallbackInterface.SUCCESS:
               Log.i(TAG, "OpenCV loaded successfully");
               cameraView.enableView();
               break;
            default:
               super.onManagerConnected(status);
               break;
         }
      }
   };

   @Override
   protected void onResume()
   //-----------------------
   {
      super.onResume();
      OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_2_4_9, this, loaderCallback);
      if ( (headerFile != null) && (framesFile != null) )
         cameraView.setRecordingFiles(headerFile, framesFile);
   }

   class CameraViewCallback implements CameraBridgeViewBase.CvCameraViewListener2
   //============================================================================
   {
      @Override
      public void onCameraViewStarted(int width, int height)
      {

      }

      @Override
      public void onCameraViewStopped()
      {

      }

      @Override
      public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame)
      {
         return inputFrame.rgba();
      }
   }
}
