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

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentTransaction;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.util.Size;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;
import com.doubleTwist.drawerlib.ADrawerLayout;
import to.augmented.reality.android.common.math.Quaternion;
import to.augmented.reality.android.common.sensor.orientation.OrientationListenable;
import to.augmented.reality.android.em.ARCamera;
import to.augmented.reality.android.em.ARCameraCharacteristics;
import to.augmented.reality.android.em.ARCameraDevice;
import to.augmented.reality.android.em.ARCameraInterface;
import to.augmented.reality.android.em.ARCameraManager;
import to.augmented.reality.android.em.ARSensorManager;
import to.augmented.reality.android.em.BearingListener;
import to.augmented.reality.android.em.FreePreviewListenable;
import to.augmented.reality.android.em.RecordingType;
import to.augmented.reality.android.em.ReviewListenable;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;


public class MainActivity extends Activity implements OpenDialog.DialogCloseable
//==============================================================================
{
   final static String TAG = MainActivity.class.getSimpleName();

   private static File DIR;
   static
   {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
         DIR = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                        "ARRecorder");
      else
      {
         File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                             "../Documents/ARRecorder");
         if (! dir.mkdirs())
         {
            dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                           "ARRecorder");
            dir.mkdirs();
         }
         try { DIR = dir.getCanonicalFile(); } catch (IOException e) { DIR = dir.getAbsoluteFile(); }
      }
   }
   final static int DEFAULT_REVIEW_DELAY = 40; //ms

   private File headerFile = new File(DIR, "test.head");
   private File framesFile = new File(DIR, "test.frames");
   boolean isDrawerOpen = false, isOpeningDrawer = false;
   Bundle lastInstanceState;

   private ADrawerLayout drawerLayout;
   private ARSurfaceView previewSurface;
   private TextView resolutionText, incrementText, locationText, bearingText;
   private View decorView = null;
   private Button openButton, reviewButton;

   AlertDialog reviewDialog = null;
   boolean isStartReviewing = false;
   float reviewStartBearing =-1, reviewEndBearing =-1, reviewCurrentBearing =-1, lastStartBearing = -1, lastEndBearing = -1;
   int reviewPause =0, lastPause = -1;
   boolean isReviewing =false, isReviewRepeating =false, lastIsReviewRepeating = false;

   ARSensorManager sensorManager = null;

   int uiOptions;
   final Handler hideHandler = new Handler();
   private int displayFlags = 0;
   final private Runnable hideRunner = new Runnable()
   //================================================
   {
      @Override public void run()
      //-------------------------
      {
         if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN)
         {
            ActionBar bar = getActionBar();
            if (bar != null)
               bar.hide();
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
         }
         decorView.setSystemUiVisibility(uiOptions);
//         ActionBar actionBar = getActionBar();
//         if (actionBar != null)
//            actionBar.hide();
      }
   };
   private boolean isLocationAllowed = true;

   public boolean isLocationAllowed() { return isLocationAllowed; }

   private class UpdateBearingRunner implements Runnable
   //====================================================
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

   @Override
   protected void onCreate(Bundle B)
   //-------------------------------
   {
      super.onCreate(B);
      requestWindowFeature(Window.FEATURE_NO_TITLE);
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

      previewSurface = (ARSurfaceView) findViewById(R.id.camera_preview_surface);
      previewSurface.createRenderer();
      resolutionText = (TextView) findViewById(R.id.resolution_text);
      incrementText = (TextView) findViewById(R.id.text_increment);
      locationText = (TextView) findViewById(R.id.location_text);
      bearingText = (TextView) findViewById(R.id.bearing_text);
      reviewButton = (Button) findViewById(R.id.button_review);
      openButton = (Button) findViewById(R.id.open_button);
      ImageButton exitButton = (ImageButton) findViewById(R.id.button_exit);
      exitButton.setOnClickListener(new View.OnClickListener()
      {
         @Override
         public void onClick(View v)
         //-------------------------
         {
            finish();
            System.exit(1);
         }
      });

      if (B != null)
         previewSurface.onRestoreInstanceState(B);
   }

//   CountDownLatch latch = new CountDownLatch(2);

   private void startPreview()
   //-------------------------
   {
      if ( (headerFile == null) || (! headerFile.exists()) || (! headerFile.canRead()) )
      {
         Toast.makeText(MainActivity.this, "Open a valid recording first.", Toast.LENGTH_LONG).show();
         return;
      }
      File dir = headerFile.getParentFile();
      //previewSurface.setPreviewFiles(headerFile, framesFile);
      ARCameraInterface icamera;
      if (EmulationControls.USE_CAMERA2_API)
      {
         ARCameraDevice camera = previewSurface.getCamera2Camera();
         icamera = camera;
      }
      else
      {
         ARCamera camera = previewSurface.getLegacyCamera();
         icamera = camera;
      }
      if ( (icamera.getRecordingType() == RecordingType.THREE60) || (icamera.getLocationFile() != null) )
         icamera.setLocationListener(new LocationUpdateListener());
      if (icamera.getRecordingType() == RecordingType.FREE)
      {
         File sensorFile = new File(dir, "sensordata.raw");
         boolean isUsingVec = false;
         if ( (EmulationControls.USE_RAW_ROTATION_VEC) && (sensorFile.exists()) )
         {
//            icamera.setFreePreviewListener(new FreePreviewListenable()
//            {
//               @Override public void onStarted() { }
//
//               @Override public boolean onComplete(int iterNo)
//               //--------------------------------------------
//               {
//                  latch = new CountDownLatch(2);
//                  try { sensorManager.restart(latch); } catch (Exception e) { Log.e(TAG, "", e); }
//                  latch.countDown();
//                  try { latch.await(); } catch (InterruptedException e) { return false; }
//                  return true;
//               }
//
//               @Override public void onError(String msg, Exception e) { }
//            });
            SensorManager manager = (SensorManager) getSystemService(Activity.SENSOR_SERVICE);
            try
            {
               sensorManager = new ARSensorManager(manager, sensorFile);
               SensorEventListener listener = new SensorEventListener()
               {
                  float[] R = new float[16];

                  @Override
                  public void onSensorChanged(SensorEvent event)
                  //--------------------------------------------
                  {
                     if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR)
                     {
                        SensorManager.getRotationMatrixFromVector(R, event.values);
                        int REMAP_X = SensorManager.AXIS_X, REMAP_Y = SensorManager.AXIS_Z;
                        SensorManager.remapCoordinateSystem(R, REMAP_X, REMAP_Y, R);
                        updateBearingRunner.bearing = (float) Math.toDegrees(Math.atan2(R[1], R[5]));
                        if (updateBearingRunner.bearing < 0)
                           updateBearingRunner.bearing += 360;
                        MainActivity.this.runOnUiThread(updateBearingRunner);
                     }
                  }

                  @Override public void onAccuracyChanged(Sensor sensor, int i) { }
               };
               Sensor sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
               sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST);
               isUsingVec = true;
            }
            catch (Exception ee)
            {
               toast("Error creating ARSensorManager using sensor file " + sensorFile);
               Log.e(TAG, "Error creating ARSensorManager using sensor file " + sensorFile, ee);

            }
         }
         if (! isUsingVec)
         {
            icamera.setOrientationListener(new OrientationListenable()
            {
               @Override
               public void onOrientationListenerUpdate(float[] R, Quaternion Q, long timestamp)
               //------------------------------------------------------------------------------
               {
                  if (!isDrawerOpen) return;
                  int REMAP_X = SensorManager.AXIS_X, REMAP_Y = SensorManager.AXIS_Z;
                  SensorManager.remapCoordinateSystem(R, REMAP_X, REMAP_Y, R);
                  //      float[] orientation = new float[3];
                  //      SensorManager.getOrientation(RM, orientation);
                  updateBearingRunner.bearing = (float) Math.toDegrees(Math.atan2(R[1], R[5]));
                  if (updateBearingRunner.bearing < 0)
                     updateBearingRunner.bearing += 360;
                  MainActivity.this.runOnUiThread(updateBearingRunner);
               }
            });
         }
      }
      if (icamera.getRecordingType() == RecordingType.THREE60)
      {
         icamera.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
         icamera.setBearingListener(new BearingListener()
               //=============================================
         {
            @Override
            public void onBearingChanged(float bearing)
            //-----------------------------------------
            {
               if (!isDrawerOpen) return;
               updateBearingRunner.bearing = bearing;
               MainActivity.this.runOnUiThread(updateBearingRunner);
            }
         });
      }

      if (icamera.getRecordingType() == RecordingType.FREE)
         openButton.setText("Stop");
      else
         openButton.setText("Open");
//      previewSurface.startPreview(latch, sensorManager);
      previewSurface.startPreview(null, sensorManager);
   }

   class LocationUpdateListener implements LocationListener
   //======================================================
   {
      @Override
      public void onLocationChanged(final Location location)
      //--------------------------------------------------------------
      {
         if (!isDrawerOpen) return;
         updateLocationRunner.location = location;
         MainActivity.this.runOnUiThread(updateLocationRunner);
      }

      @Override
      public void onStatusChanged(String provider, int status, Bundle extras) { }

      @Override
      public void onProviderEnabled(String provider) { }

      @Override
      public void onProviderDisabled(String provider) { }
   }

   public void onReview(View view)
   //-----------------------------
   {
      if (reviewButton.getText().toString().equals("Stop"))
      {
         previewSurface.stopReviewing();
         return;
      }
      if ( (headerFile == null) || (! headerFile.exists()) || (! headerFile.canRead()) )
      {
         Toast.makeText(MainActivity.this, "Open a valid recording first.", Toast.LENGTH_LONG).show();
         return;
      }
      if ( (framesFile == null) || (! framesFile.exists()) || (! framesFile.canRead()) )
      {
         Toast.makeText(MainActivity.this, "Open a valid recording first (Frame file " +
               ((framesFile == null) ? "null" : framesFile.getName()) + " not found.", Toast.LENGTH_LONG).show();
         return;
      }
      if (previewSurface != null)
      {
         if (previewSurface.isReviewing())
         {
            previewSurface.stopReviewing();
            reviewButton.setText("Review");
         }
         else
         {
            if (isStartReviewing)
            {
               if (reviewDialog != null)
               {
                  reviewDialog.dismiss();
                  reviewDialog = null;
               }
               else
                  return;
            }
            if (! previewSurface.isPreviewing())
               startPreview();
            isStartReviewing = true;
            LayoutInflater inflater = LayoutInflater.from(this);
            final ViewGroup dialogLayout = (ViewGroup) inflater.inflate(R.layout.start_review, null);
            final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            final EditText startBearingText = (EditText) dialogLayout.findViewById(R.id.text_start_bearing);
            final EditText endBearingText = (EditText) dialogLayout.findViewById(R.id.text_end_bearing);
            final NumberPicker delayPicker = (NumberPicker) dialogLayout.findViewById(R.id.numberpicker_delay);
            final TextView displayDelayText = (TextView) dialogLayout.findViewById(R.id.text_display_delay);
            final CheckBox repeatCheckBox = (CheckBox) dialogLayout.findViewById(R.id.checkbox_repeat);
            startBearingText.setOnEditorActionListener(new TextView.OnEditorActionListener()
            {
               @Override
               public boolean onEditorAction(final TextView v, final int actionId, final KeyEvent event)
               //----------------------------------------------------------------------------------------
               {
                  if ( (event != null) && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) )
                  {
                     imm.hideSoftInputFromWindow(startBearingText.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
                     endBearingText.requestFocus();
                     return true;
                  }
                  return false;
               }
            });
            endBearingText.setOnEditorActionListener(new TextView.OnEditorActionListener()
            {
               @Override
               public boolean onEditorAction(final TextView v, final int actionId, final KeyEvent event)
               //----------------------------------------------------------------------------------------
               {
                  if ( (event != null) && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) )
                  {
                     imm.hideSoftInputFromWindow(endBearingText.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
                     return true;
                  }
                  return false;
               }
            });
            if (lastStartBearing > 0)
               startBearingText.setText(Float.toString(lastStartBearing));
            if (lastEndBearing > 0)
               endBearingText.setText(Float.toString(lastEndBearing));
            final String[] delayValues = new String[1000/5 + 1];
            delayValues[0] = "1";
            int defaultDelayIndex = 0;
            int currentDelay = (lastPause > 0) ? lastPause : DEFAULT_REVIEW_DELAY;
            for (int i=5, j=1; i<=1000; i+=5)
            {
               if (i == currentDelay)
                  defaultDelayIndex = j;
               delayValues[j++] = Integer.toString(i);

            }
            delayPicker.setDisplayedValues(delayValues);
            delayPicker.setWrapSelectorWheel(false);
            delayPicker.setMinValue(0);
            delayPicker.setMaxValue(delayValues.length-1);
            delayPicker.setValue(defaultDelayIndex);
            displayDelayText.setText(delayValues[defaultDelayIndex]);
            delayPicker.setOnValueChangedListener(new NumberPicker.OnValueChangeListener()
            {
               @Override
               public void onValueChange(NumberPicker picker, int oldVal, int newVal)
               {
                  displayDelayText.setText(delayValues[delayPicker.getValue()]);
               }
            });
            repeatCheckBox.setChecked(lastIsReviewRepeating);

            reviewDialog = new AlertDialog.Builder(this).create();
            reviewDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
//      recordDialog.setTitle("Recording");
            reviewDialog.setView(dialogLayout);
            reviewDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Ok", new DialogInterface.OnClickListener()
            //=============================================================================================
            {
               public void onClick(DialogInterface dialog, int whichButton)
               //-----------------------------------------------------------
               {
                  reviewButton.setText("Stop");
                  float bearingStart =0, bearingEnd =360;
                  int pause = 10;
                  String s = startBearingText.getText().toString();
                  if (s == null)
                     s = "0";
                  else
                     s = s.trim();
                  try { bearingStart = Float.parseFloat(s); } catch (Exception e) { bearingStart = 0; }
                  s = endBearingText.getText().toString();
                  if (s == null)
                     s = "360";
                  else
                     s = s.trim();
                  try { bearingEnd = Float.parseFloat(s); } catch (Exception e) { bearingEnd = 360; }
                  int i= delayPicker.getValue();
                  pause = Integer.parseInt(delayValues[i]);
                  boolean isRepeat = repeatCheckBox.isChecked();
                  isStartReviewing = false;
                  isReviewing = true;
                  lastStartBearing = reviewStartBearing = bearingStart;
                  lastEndBearing = reviewEndBearing = bearingEnd;
                  lastPause = reviewPause = pause;
                  lastIsReviewRepeating = isReviewRepeating = isRepeat;
                  previewSurface.review(bearingStart, bearingEnd, pause, isRepeat, new ReviewHandler());
                  MainActivity.this.runOnUiThread(new Runnable()
                  {
                     @Override public void run() { reviewDialog.dismiss(); reviewDialog = null; }
                  });
               }
            });
            reviewDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener()
            {
               public void onClick(DialogInterface dialog, int whichButton)
               //----------------------------------------------------------
               {
                  isStartReviewing = false;
                  MainActivity.this.runOnUiThread(new Runnable()
                  {
                     @Override public void run() { reviewDialog.dismiss(); reviewDialog = null; }
                  });
               }
            });
            reviewDialog.show();
         }
      }
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN)
            {
               ActionBar bar = MainActivity.this.getActionBar();
               if (bar != null)
                  bar.hide();
               MainActivity.this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                                                          WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
            else // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
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
               View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_FULLSCREEN;
         decorView.setSystemUiVisibility(uiOptions);
         decorView.setOnSystemUiVisibilityChangeListener(visibilityListener);
      }
      else
      {
         decorView = getWindow().getDecorView().findViewById(android.R.id.content);
         uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                     View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN |
                     View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
         decorView.setSystemUiVisibility(uiOptions);
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH)
         uiOptions |= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
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
      if (! hasPermissions(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA))
         requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.CAMERA }, 1);
      if (! hasPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
         requestPermissions(new String[]{ Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION }, 2);
      if (previewSurface.isPreviewing())
      {
         previewSurface.stopPreview();
         openButton.setText("Open");
      }
      else
      {
         FragmentTransaction ft = getFragmentManager().beginTransaction();
         OpenDialog dialog = OpenDialog.instance(DIR);
         dialog.show(ft, "OpenDialog");
      }
   }

   @SuppressLint("NewApi")
   public boolean hasPermissions(String... permissions)
   //--------------------------------------------------
   {
      if ( (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) &&
            (permissions != null) && (permissions.length > 0) )
      {
         for (String permission : permissions)
         {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
               return false;
         }
      }
      return true;
   }

   @TargetApi(Build.VERSION_CODES.M)
   @Override
   public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults)
   //-----------------------------------------------------------------------------------------------
   {
      switch (requestCode)
      {
         case 1:
            for (int i=0; i<grantResults.length; i++)
               if (grantResults[i] == PackageManager.PERMISSION_DENIED)
            {
               this.finish();
               System.exit(1);
            }
            break;

         case 2:
            isLocationAllowed = false;
            break;
      }
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
         if ( (! headerFile.exists()) || (! headerFile.canRead()) )
         {
            Toast.makeText(MainActivity.this, headerFile.getAbsolutePath() + " not found or not readable", Toast.LENGTH_LONG).show();
            return;
         }
         framesFile = new File(dir, basename + ".frames");
         StringBuilder errbuf = new StringBuilder();
         previewSurface.setPreviewFiles(headerFile, framesFile, errbuf);
         if (errbuf.length() > 0)
            Toast.makeText(this, errbuf.toString(), Toast.LENGTH_LONG).show();
         int width =-1, height = -1;
         ARCameraInterface icamera = null;
         if (! EmulationControls.USE_CAMERA2_API)
         {
            ARCamera camera = previewSurface.getLegacyCamera();
            icamera = (ARCameraInterface) camera;
            if (camera == null)
            {
               Toast.makeText(this, "Could not obtain camera", Toast.LENGTH_LONG).show();
               return;
            }

            if (EmulationControls.IS_EMULATE_CAMERA)
            {
               Camera.Parameters parameters = camera.getParameters();
               Camera.Size sz = parameters.getPreviewSize();
               width = sz.width;
               height = sz.height;
            }
            else
            {
               width = icamera.getHeaderInt("PreviewWidth", -1);
               height = icamera.getHeaderInt("PreviewHeight", -1);
            }
         }
         else
         {
            ARCameraDevice camera = previewSurface.getCamera2Camera();
            if (camera == null)
            {
               Toast.makeText(this, "Could not obtain camera", Toast.LENGTH_LONG).show();
               return;
            }
            icamera = camera;
            if (EmulationControls.IS_EMULATE_CAMERA)
            {
               ARCameraManager manager = ARCameraManager.get(this, headerFile, null, null, null, false);
               try
               {
                  ARCameraCharacteristics characteristics = manager.getCameraCharacteristics(camera.getId());
                  ARCameraCharacteristics.ARStreamConfigurationMap streamConfig =
                        characteristics.get(ARCameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                  for (Size psize : streamConfig.getOutputSizes(SurfaceTexture.class))
                  {
                     width = psize.getWidth();
                     height = psize.getHeight();
                  }
               }
               catch (Exception e)
               {
                  Log.e(TAG, "", e);
                  width = icamera.getHeaderInt("PreviewWidth", -1);
                  height = icamera.getHeaderInt("PreviewHeight", -1);
               }
            }
            else
            {
               width = icamera.getHeaderInt("PreviewWidth", -1);
               height = icamera.getHeaderInt("PreviewHeight", -1);
            }
         }

         if ((width < 0) || (height < 0))
         {
            Toast.makeText(MainActivity.this,
                           headerFile.getAbsolutePath() + " does not contain preview width or height",
                           Toast.LENGTH_LONG).show();
            return;
         }
         RecordingType type = icamera.getRecordingType();
         if (type == RecordingType.THREE60)
         {
            float increment = icamera.getHeaderFloat("Increment", -1);
            if (increment < 0)
            {
               Toast.makeText(MainActivity.this, headerFile.getAbsolutePath() + " does not contain frame increment",
                              Toast.LENGTH_LONG).show();
               return;
            }
            incrementText.setText(String.format(Locale.US, "%.1f", increment));
         }
         else
            incrementText.setVisibility(View.GONE);
         resolutionText.setText(String.format(Locale.US, "%dx%d", width, height));
         String location = icamera.getHeader("Location", "");
         locationText.setText(location.replaceAll("\\s+", ""));

         startPreview();
         drawerLayout.close();
      }
   }

   public void toast(final String msg)
   //--------------------------
   {
      runOnUiThread(new Runnable()
      {
         @Override
         public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
      });
   }

   @Override
   protected void onSaveInstanceState(Bundle B)
   //-------------------------------------------------
   {
      super.onSaveInstanceState(B);
      previewSurface.onSaveInstanceState(B);
      B.putBoolean("isReviewing", isReviewing);
      if (isReviewing)
      {
         B.putFloat("reviewCurrentBearing", previewSurface.getReviewCurrentBearing());
         B.putFloat("reviewStartBearing", reviewStartBearing);
         B.putFloat("reviewEndBearing", reviewEndBearing);
         B.putBoolean("isReviewRepeating", isReviewRepeating);
         B.putInt("reviewPause", reviewPause);
      }
      lastInstanceState = new Bundle(B);
   }

   @Override
   protected void onPause()
   //----------------------
   {
      super.onPause();
      previewSurface.onPause();
   }

   @Override
   protected void onRestoreInstanceState(Bundle B)
   //---------------------------------------------------------------
   {
      super.onRestoreInstanceState(B);
      previewSurface.onRestoreInstanceState(B);
      isReviewing = B.getBoolean("isReviewing", false);
      if (isReviewing)
      {
         reviewCurrentBearing = B.getFloat("reviewCurrentBearing", 0);
         reviewStartBearing = B.getFloat("reviewStartBearing", 0);
         reviewEndBearing = B.getFloat("reviewEndBearing", 0);
         isReviewRepeating = B.getBoolean("isReviewRepeating", false);
         reviewPause = B.getInt("reviewPause", -1);
      }
   }

   @Override
   protected void onResume()
   //-----------------------
   {
      super.onResume();
      previewSurface.onResume();
      if (isReviewing)
      {
         previewSurface.review(reviewStartBearing, reviewEndBearing, reviewPause, isReviewRepeating, new ReviewHandler());
         previewSurface.setReviewBearing(reviewCurrentBearing);
      }
   }

   class ReviewHandler implements ReviewListenable
   //================================================
   {
      @Override public void onReviewStart() { }

      @Override public void onReview(float bearing) { }

      @Override public void onReviewed(float bearing) { }

      @Override public void onReviewComplete()
      //--------------------------------------
      {
         isReviewing = false;
         reviewStartBearing = reviewEndBearing = reviewCurrentBearing = -1;
         reviewPause = -1;
         MainActivity.this.runOnUiThread(new Runnable()
         {
            @Override public void run() { reviewButton.setText("Review"); }
         });
      }
   }
}
