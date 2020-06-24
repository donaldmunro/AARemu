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

import android.Manifest;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.hardware.Sensor;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.os.Environment;
import android.os.Handler;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.Html;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.doubleTwist.drawerlib.ADrawerLayout;
import com.thomashaertel.widget.MultiSpinner;

import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import to.augmented.reality.android.em.recorder.fullscreen.SystemUiHider;

import static to.augmented.reality.android.common.sensor.orientation.OrientationProvider.ORIENTATION_PROVIDER;

public class RecorderActivity extends Activity
//============================================
{
   private static final String TAG = RecorderActivity.class.getSimpleName();

   // Don't check if recording directory with the specified name exists. If false then a prompt to
   // overwrite the files occurs.
   // See also Three60RecordingThread.TEST_POST_PROCESS
   static final boolean IS_DEBUG = false;
   static final boolean NO_OVERWRITE_CHECK = (Three60RecordingThread.TEST_POST_PROCESS);
   static final String[] EXTRA_SENSORS_NAMES = {"Rotation Vector", "Gyroscope", "Gravity", "Magnetic",
                                                "Acceleration", "Linear Acceleration"};
   static final private Collection<String> EXTRA_RECORD_SENSORS_NAMES = Arrays.asList(EXTRA_SENSORS_NAMES);
   static final private int[] EXTRA_RECORD_SENSORS = { Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GYROSCOPE,
                                                       Sensor.TYPE_GRAVITY, Sensor.TYPE_MAGNETIC_FIELD,
                                                       Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_LINEAR_ACCELERATION };
   static private enum EXTRA_RECORD_SENSORS_INDICES
   { ROTATION_VECTOR, GYROSCOPE, GRAVITY, MAGNETIC, ACCELERATION,
      LINEAR_ACCELERATION
   };

   static File DIR;
   static
   {
      File dir = null;
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
      {
         dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                        "ARRecorder");
         if (! dir.mkdirs())
         {
            dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                           "ARRecorder");
            dir.mkdirs();
         }
      }
      else
      {
         dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                        "../Documents/ARRecorder");
         if (! dir.mkdirs())
         {
            dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                           "ARRecorder");
            dir.mkdirs();
         }
      }
      try { DIR = dir.getCanonicalFile(); } catch (IOException e) { DIR = dir.getAbsoluteFile(); }
   }
   private static final int AUTO_HIDE_DELAY_MILLIS = 2000;

   ADrawerLayout drawerLayout;
   ARSurfaceView previewSurface;
   private ImageButton record360Button = null, recordFreeButton = null, pauseButton = null, exitButton;
   List<ImageButton> recordingButtons = new ArrayList<>();
   //   private ImageView gpsOnOffImage;

   private String currentResolution = "";
   int currentResolutionIndex = -1;
   private TextView locationText, statusText, locationLabel; //,bearingText, bearingDestLabel, bearingDestText
   private ProgressBar statusProgress;
   private boolean isResolutionSelected = false;
   View decorView = null;
   int uiOptions;

// if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
   private SystemUiHider systemUiHider;
   Handler hideHandler;
   Runnable hideRunnable;
   View.OnTouchListener delayHideTouchListener;
   AtomicBoolean isSurfaceInitialised = new AtomicBoolean(false);
   private boolean isLocationRecorded = true;

   final static public String[] ESSENTIAL_PERMISSIONS = { Manifest.permission.CAMERA,
                                                          Manifest.permission.WRITE_EXTERNAL_STORAGE };
   final static public String[] OPTIONAL_PERMISSIONS = { Manifest.permission.ACCESS_COARSE_LOCATION,
                                                         Manifest.permission.ACCESS_FINE_LOCATION
                                                       };
   private int permissionCount = 0, locationsDenied = 0;

   boolean isLocationRecorded() { return isLocationRecorded; }

   public void selectResult(final File recordingDir, final float[] recordingIncrements, final Pair<Integer, Integer>[] incrementResults)
   //-----------------------------------------------------------------------------------------------------------------
   {
      runOnUiThread(new Runnable()
      {
         @Override public void run()
         //-------------------------
         {
            final Intent intent = new Intent(RecorderActivity.this, Three60RecordingResult.class);
            intent.putExtra("recordingDir", recordingDir.getAbsolutePath());
            intent.putExtra("recordingIncrements", recordingIncrements);
            int count = 0;
            for (Pair<Integer, Integer> pp : incrementResults)
            {
               if (pp.first < Integer.MAX_VALUE)
                  count++;
            }
            int[] indices = new int[count];
            int[] kludges = new int[count];
            int i = 0;
            for (Pair<Integer, Integer> pp : incrementResults)
            {
               if (pp.first < Integer.MAX_VALUE)
               {
                  indices[i] = pp.first;
                  kludges[i++] = pp.second;
               }
            }
            intent.putExtra("indices", indices);
            intent.putExtra("kludges", kludges);
            startActivityForResult(intent, 1);
         }
      });
   }

//   @Override
//   protected void onActivityResult(final int requestCode, final int resultCode, final Intent data)
//   // ---------------------------------------------------------------------------------------------
//   {
//      if (requestCode == 1)
//      {
//
//      }
//   }


// end if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)

   private class UpdateLocationRunner implements Runnable
   //===================================================
   {
      volatile public Location location;

      @Override public void run()
      //-------------------------
      {
         String locationText = formatLocation(location);
         int provider = (location.getProvider().equals(LocationManager.NETWORK_PROVIDER)) ? 1 : 0;
         Spannable locationSpan = new SpannableString(locationText);
         ForegroundColorSpan spanColor = (provider == 1) ? new ForegroundColorSpan(Color.YELLOW)
                                                     : new ForegroundColorSpan(Color.GREEN);
         locationSpan.setSpan(spanColor, 0, locationSpan.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
         RecorderActivity.this.locationText.setText(locationSpan);
      }
   };

   final private UpdateLocationRunner updateLocationRunner = new UpdateLocationRunner();

   private class UpdateStatusRunner implements Runnable
   //===================================================
   {
      volatile public ProgressParam params;

      @Override public void run() { onStatusUpdate(params); }
   }

   final private UpdateStatusRunner updateStatusRunner = new UpdateStatusRunner();
   Toast lastStatusToast = null;

   private int displayFlags = 0;
   boolean isOpeningDrawer = false, isDrawerOpen = false;
   private String[] resolutions = null;
   private boolean isRecording = false, isStartRecording = false, isRecordingPaused = false;
   private String versionName = "";
   public String getVersionName() { return versionName; }
   private int versionCode =-1;
   public int getVersionCode() { return versionCode; }

   @Override
   protected void onCreate(Bundle B)
   //--------------------------------
   {
      super.onCreate(B);
      try { versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception e) { versionName = "N/A";}
      try { versionCode = getPackageManager().getPackageInfo(getPackageName(), 0).versionCode; } catch (Exception e) { versionCode = -1; }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
      {
         requestWindowFeature(Window.FEATURE_NO_TITLE);
         decorView = getWindow().getDecorView().findViewById(android.R.id.content);
         uiOptions = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
               View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_FULLSCREEN |
               View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
         decorView.setSystemUiVisibility(uiOptions);
      }
      else
      {
         hideHandler = new Handler();
         hideRunnable = new Runnable()
         {
            @Override public void run()
            {
               systemUiHider.hide();
//               if (isDrawerOpen) drawerLayout.invalidate();
            }
         };
         delayHideTouchListener = new View.OnTouchListener()
         {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent)
            //------------------------------------------------------
            {
               delayedHide(AUTO_HIDE_DELAY_MILLIS);
               return false;
            }
         };
      }
      setContentView(R.layout.activity_recorder);

//      int degrees = 0;
//      switch (getDeviceRotation())
//      {
//         case Surface.ROTATION_0:   degrees = 0; break;
//         case Surface.ROTATION_90:  degrees = 90; break;//6P
//         case Surface.ROTATION_180: degrees = 180; break;
//         case Surface.ROTATION_270: degrees = 270; break;
//      }

      drawerLayout = (ADrawerLayout) findViewById(R.id.drawer);
      int contentPadding = getResources().getDimensionPixelSize(R.dimen.drawer_padding);
      drawerLayout.setRestrictTouchesToArea(ADrawerLayout.LEFT_DRAWER, contentPadding);
      drawerLayout.setParalaxFactorX(0.5f);
      drawerLayout.setDimContent(false);
      drawerLayout.setAnimateScrolling(false);
//      drawerLayout.setInnerIsGlobal(true);
      drawerLayout.setListener(new ADrawerLayout.DrawerLayoutListener()
      {
         // @formatter:off
         @Override public void onBeginScroll(ADrawerLayout dl, ADrawerLayout.DrawerState state) { }

         @Override public void onOffsetChanged(ADrawerLayout dl, ADrawerLayout.DrawerState state, float offsetXNorm,
                                     float offsetYNorm, int offsetX, int offsetY)
         {
         }

         @Override public void onPreClose(ADrawerLayout dl, ADrawerLayout.DrawerState state) { }

         @Override public void onPreOpen(ADrawerLayout dl, ADrawerLayout.DrawerState state) { isOpeningDrawer = true; }

         @Override
         public void onClose(ADrawerLayout dl, ADrawerLayout.DrawerState state, int closedDrawerId)
         //--------------------------------------------------------------------------------------------------
         {
            isDrawerOpen = isOpeningDrawer = false;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
               delayedHide(500);
//            {
//               hideHandler.removeCallbacks(hideRunner);
//               hideHandler.postDelayed(hideRunner, 1500);
//            }
         }

         @Override
         public void onOpen(ADrawerLayout dl, ADrawerLayout.DrawerState state)
         //-------------------------------------------------------------------
         {
            isDrawerOpen = true;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
            {
//               systemUiHider.show();
               if (isRecording)
                  delayedHide(500);
               else
                  delayedHide(3000);
            }
//            {
//               hideHandler.removeCallbacks(hideRunner);
//               hideHandler.postDelayed(hideRunner, 1500);
//            }
         }
         // @formatter:on
      });
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
         drawerLayout.open();

      previewSurface = (ARSurfaceView) findViewById(R.id.camera_preview_surface);
      previewSurface.getHolder().addCallback(new SurfaceHolder.Callback()
      {
         @Override public void surfaceCreated(SurfaceHolder holder)  { isSurfaceInitialised.set(true); }

         @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height)
         //-------------------------------------------------------------------------------------------
         {
            runOnUiThread(new Runnable()
            {
               @Override
               public void run()
               //--------------
               {
                  GLRecorderRenderer renderer = RecorderActivity.this.previewSurface.getRenderer();
                  while (! renderer.isInitialised())
                     try { Thread.sleep(50); } catch (InterruptedException e) { break; }
                  boolean isCamera2API = false;
                  if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP)
                  {
                     CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                     String camList[];
                     try { camList =  manager.getCameraIdList(); } catch (Exception _e) { camList = new String[0]; }
                     String cameraID = null;
                     for (String id : camList)
                     {
                        CameraCharacteristics characteristics;
                        try { characteristics = manager.getCameraCharacteristics(id); } catch (Exception _e) { characteristics = null; }
                        if (characteristics == null) continue;
                        Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                        if (facing == null) continue;
                        if (facing == CameraCharacteristics.LENS_FACING_BACK)
                        {
                           cameraID = id;
                           break;
                        }
                     }
                     if (cameraID != null)
                     {
                        CameraCharacteristics characteristics;
                        try {  characteristics = manager.getCameraCharacteristics(cameraID); } catch (Exception _e) { characteristics = null; }
                        if (characteristics != null)
                        {
                           Integer level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                           isCamera2API = (level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);
                        }
                     }
                     else
                        isCamera2API = false;
                  }
                  previewSurface.startPreview(640, 480, false, isCamera2API);
               }
            });
         }

         @Override public void surfaceDestroyed(SurfaceHolder holder) { }
      });

      locationLabel = (TextView) findViewById(R.id.location_label);
      locationText = (TextView) findViewById(R.id.location_text);
      statusText = (TextView) findViewById(R.id.status_text);
      statusProgress = (ProgressBar) findViewById(R.id.status_progress);
      statusProgress.setVisibility(View.INVISIBLE);
      statusText.setVisibility(View.INVISIBLE);
      locationLabel.setVisibility(View.INVISIBLE);
      locationText.setVisibility(View.INVISIBLE);
      TextView privacyText = (TextView) findViewById(R.id.privacy);
//      TextView webText = (TextView) findViewById(R.id.website);
      String privacyLink = "<a href=https://donaldmunro.github.io/ARemRecorder-Privacy.html>Privacy Policy</a>";
//      String webLink = "<a href=https://github.com/donaldmunro/AARemu/>Web Site</a>";
//      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
//      {
//         privacyText.setMovementMethod(LinkMovementMethod.getInstance());
//         privacyText.setText(Html.fromHtml(privacyLink, Html.FROM_HTML_MODE_LEGACY));
//         webText.setMovementMethod(LinkMovementMethod.getInstance());
//         webText.setText(Html.fromHtml(webLink, Html.FROM_HTML_MODE_LEGACY));
//      }
//      else
      {
         privacyText.setMovementMethod(LinkMovementMethod.getInstance());
         privacyText.setText(Html.fromHtml(privacyLink));
//         webText.setMovementMethod(LinkMovementMethod.getInstance());
//         webText.setText(Html.fromHtml(webLink));
      }

      pauseButton = (ImageButton) findViewById(R.id.button_pause);
      recordFreeButton = (ImageButton) findViewById(R.id.button_start_free);
      recordingButtons.add(recordFreeButton);
      record360Button = (ImageButton) findViewById(R.id.button_start_360);
      recordingButtons.add(record360Button);
      record360Button.setOnClickListener(new RecordingClickedHandler(RecordingThread.RecordingType.THREE60));
      recordFreeButton.setOnClickListener(new RecordingClickedHandler(RecordingThread.RecordingType.FREE));
      pauseButton.setOnClickListener(new View.OnClickListener()
      {
         @Override
         public void onClick(View v)
         //-------------------------
         {
            if (! isRecording)
               return;
            if (isRecordingPaused)
            {
               pauseButton.setImageResource(R.drawable.pause);
               previewSurface.resumeRecording();
               isRecordingPaused = false;
            }
            else
            {
               pauseButton.setImageResource(R.drawable.play);
               previewSurface.pauseRecording();
               isRecordingPaused = true;
            }
         }
      });
      exitButton = (ImageButton) findViewById(R.id.button_exit);
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
      recordingButtons.add(exitButton);

      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
      {
         setupFullScreen(record360Button);
         for (ImageButton button : recordingButtons)
            button.setOnTouchListener(delayHideTouchListener);
         pauseButton.setOnTouchListener(delayHideTouchListener);
         drawerLayout.setOnTouchListener(delayHideTouchListener);
      }
      if (B != null)
         previewSurface.onRestoreInstanceState(B);

//      float[] recordingIncrements = { 1f, 1.5f, 2.0f, 2.5f, 3.0f };
//      Pair<Integer, Integer>[] incrementResults = new Pair[recordingIncrements.length];
//      incrementResults[0] = new Pair<>(3, 0);
//      incrementResults[1] = new Pair<>(2, 1);
//      incrementResults[2] = new Pair<>(1, 3);
//      incrementResults[3] = new Pair<>(0, 5);
//      incrementResults[4] = new Pair<>(Integer.MAX_VALUE, Integer.MAX_VALUE);
//      selectResult(new File(DIR, "test"), recordingIncrements, incrementResults);
   }

   AlertDialog recordDialog = null, helpDialog = null;

   private class RecordingClickedHandler implements View.OnClickListener
   //-------------------------------------------------------------------
   {
      final RecordingThread.RecordingType recordingType;

      public RecordingClickedHandler(RecordingThread.RecordingType type) { this.recordingType = type; }

      @Override
      public void onClick(View v)
      //------------------------
      {
         if (! isRecording)
            startRecording(recordingType);
         else
         {  // stop recording
            switch (recordingType)
            {
               case THREE60:
                  for (ImageButton button : recordingButtons)
                     button.setVisibility(View.VISIBLE);
                  pauseButton.setVisibility(View.GONE);
                  final AlertDialog partialSaveDialog =
                        new AlertDialog.Builder(RecorderActivity.this).setTitle("Partial Save").
                              setMessage("Save incomplete recording").create();
                  partialSaveDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Yes", new DialogInterface.OnClickListener()
                  {
                     @Override
                     public void onClick(DialogInterface dialog, int which) { previewSurface.stopRecordingFlag(); }
                  });
                  partialSaveDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "No", new DialogInterface.OnClickListener()
                  {
                     @Override
                     public void onClick(DialogInterface dialog, int which)
                     {
                        previewSurface.stopRecording(true);
                        record360Button.setImageResource(R.drawable.three60);
                     }
                  });
                  partialSaveDialog.show();
                  break;
               case FREE: // Cancel == stop for free recording so don't call stopRecording yet
                  previewSurface.stopRecordingFlag();
                  record360Button.setVisibility(View.GONE);
                  recordFreeButton.setVisibility(View.GONE);
                  pauseButton.setVisibility(View.GONE);
                  statusProgress.setProgress(0);
                  break;
            }
         }
      }
   }

   private void startRecording(RecordingThread.RecordingType recordingType)
   //----------------------------------------------------------------------
   {
      if (isStartRecording)
      {
         if (recordDialog != null)
         {
            recordDialog.dismiss();
            recordDialog = null;
         }
         else
            return;
      }
      isStartRecording = true;
      switch (recordingType)
      {
         case THREE60:
            start360Recording();
            break;
         case FREE:
            startFreeRecording();
            break;
      }
   }

   private void start360Recording()
   //------------------------------
   {
      final LayoutInflater inflater = LayoutInflater.from(this);
      final ViewGroup dialogLayout = (ViewGroup) inflater.inflate(R.layout.start_360recording, null);
      final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
      final EditText nameText = (EditText) dialogLayout.findViewById(R.id.text_recording_name);
      nameText.setOnEditorActionListener(new TextView.OnEditorActionListener()
      {
         @Override
         public boolean onEditorAction(final TextView v, final int actionId, final KeyEvent event)
         //----------------------------------------------------------------------------------------
         {
            if ( (event != null) && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) )
            {
               imm.hideSoftInputFromWindow(nameText.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
               return true;
            }
            return false;
         }
      });

      final Spinner resolutionsSpinner = (Spinner) dialogLayout.findViewById(R.id.spinner_camera_resolutions);
      final ResolutionAdapter resolutionSpinnerAdapter = new ResolutionAdapter(RecorderActivity.this,
                                                                               android.R.layout.simple_list_item_1,
                                                                               resolutions);
      resolutionsSpinner.setAdapter(resolutionSpinnerAdapter);
      resolutionsSpinner.setOnItemSelectedListener(
      new AdapterView.OnItemSelectedListener()
      //======================================
      {
         @Override
         public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
         //---------------------------------------------------------------------------------
         {
            String resolution = resolutionSpinnerAdapter.getItem(position);
            if ( (resolution != null) && (position != currentResolutionIndex) )
            {
               currentResolution = resolution;
               currentResolutionIndex = position;
            }
         }

         @Override public void onNothingSelected(AdapterView<?> parent) { }
      });
      resolutionsSpinner.setSelection(currentResolutionIndex);
//      final Spinner incrementSpinner = (Spinner) dialogLayout.findViewById(R.id.spinner_increments);
//      incrementSpinner.setSelection(0);
      final Spinner orientationSpinner = (Spinner) dialogLayout.findViewById(R.id.spinner_sensors);

      ORIENTATION_PROVIDER[] orientationProviders = ORIENTATION_PROVIDER.values();
      String[] orientationProviderNames = new String[orientationProviders.length];
      for (int i=0; i<orientationProviders.length; i++)
         orientationProviderNames[i] = human(orientationProviders[i].name());
      SpinnerAdapter orientationSpinnerAdapter =
            new ArrayAdapter<String>(RecorderActivity.this, android.R.layout.simple_list_item_1, orientationProviderNames);
      orientationSpinner.setAdapter(orientationSpinnerAdapter);

      ArrayAdapter xtraSensorAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item);
      xtraSensorAdapter.addAll(EXTRA_RECORD_SENSORS_NAMES);
      final MultiSpinner xtraSensorsSpinner = (MultiSpinner) dialogLayout.findViewById(R.id.spinner_xsensors);
      xtraSensorsSpinner.setAdapter(xtraSensorAdapter, false,
                                    new MultiSpinner.MultiSpinnerListener()
                                    {
                                       @Override public void onItemsSelected(boolean[] selected) { }
                                    });

      CheckBox postProcessCheckbox = (CheckBox) dialogLayout.findViewById(R.id.checkbox_postprocess);
      CheckBox flashCheckbox = (CheckBox) dialogLayout.findViewById(R.id.checkbox_flash);
      TextView textFlash = (TextView) dialogLayout.findViewById(R.id.label_flash);
      RelativeLayout layoutFlash = (RelativeLayout) dialogLayout.findViewById(R.id.layout_flash);
      CheckBox stitchCheckbox = (CheckBox) dialogLayout.findViewById(R.id.checkbox_stitching);
      boolean hasFlash = previewSurface.hasFlash();
      if (hasFlash)
      {
         layoutFlash.setVisibility(View.VISIBLE);
         flashCheckbox.setVisibility(View.VISIBLE);
         textFlash.setVisibility(View.VISIBLE);
      }
      else
      {
         layoutFlash.setVisibility(View.GONE);
         flashCheckbox.setVisibility(View.GONE);
         textFlash.setVisibility(View.GONE);
         flashCheckbox = null;
      }
      TextView cameraApiText = (TextView) dialogLayout.findViewById(R.id.label_api);
      CheckBox cameraApiCheckbox = (CheckBox) dialogLayout.findViewById(R.id.checkbox_api);
      RelativeLayout layoutcameraAPI = (RelativeLayout) dialogLayout.findViewById(R.id.layout_api);
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP)
      {
         layoutcameraAPI.setVisibility(View.VISIBLE);
         cameraApiText.setVisibility(View.VISIBLE);
         cameraApiCheckbox.setVisibility(View.VISIBLE);
      }
      else
      {
         layoutcameraAPI.setVisibility(View.GONE);
         cameraApiText.setVisibility(View.GONE);
         cameraApiCheckbox.setVisibility(View.GONE);
         cameraApiCheckbox = null;
      }
      final TextView sizeText = (TextView) dialogLayout.findViewById(R.id.text_maxsize);
      StringBuilder size = new StringBuilder();
      if (freeSpaceGb(DIR, size) <= 0)
      {
         Toast.makeText(this, "No disk space", Toast.LENGTH_LONG).show();
         return;
      }
      sizeText.setText(size.toString());

      recordDialog = createRecordDialog(dialogLayout, nameText, resolutionsSpinner, orientationSpinner, sizeText,
                                        record360Button, xtraSensorsSpinner, postProcessCheckbox, flashCheckbox, cameraApiCheckbox,
                                        stitchCheckbox, RecordingThread.RecordingType.THREE60);
      recordDialog.show();
   }

   private void startFreeRecording()
   //------------------------------
   {
      final LayoutInflater inflater = LayoutInflater.from(this);
      final ViewGroup dialogLayout = (ViewGroup) inflater.inflate(R.layout.start_freerecording, null);
      final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
      final EditText nameText = (EditText) dialogLayout.findViewById(R.id.text_free_recording_name);
      nameText.setOnEditorActionListener(new TextView.OnEditorActionListener()
      {
         @Override
         public boolean onEditorAction(final TextView v, final int actionId, final KeyEvent event)
         //----------------------------------------------------------------------------------------
         {
            if ( (event != null) && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) )
            {
               imm.hideSoftInputFromWindow(nameText.getWindowToken(), InputMethodManager.HIDE_NOT_ALWAYS);
               return true;
            }
            return false;
         }
      });

      final Spinner resolutionsSpinner = (Spinner) dialogLayout.findViewById(R.id.spinner_free_camera_resolutions);
      final ResolutionAdapter resolutionSpinnerAdapter = new ResolutionAdapter(RecorderActivity.this,
                                                                               android.R.layout.simple_list_item_1,
                                                                               resolutions);
      resolutionsSpinner.setAdapter(resolutionSpinnerAdapter);
      resolutionsSpinner.setOnItemSelectedListener(
            new AdapterView.OnItemSelectedListener()
            //======================================
            {
               @Override
               public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
               //---------------------------------------------------------------------------------
               {
                  String resolution = resolutionSpinnerAdapter.getItem(position);
                  if ( (resolution != null) && (position != currentResolutionIndex) )
                  {
                     currentResolution = resolution;
                     currentResolutionIndex = position;
                  }
               }

               @Override public void onNothingSelected(AdapterView<?> parent) { }
            });
      resolutionsSpinner.setSelection(currentResolutionIndex);
      final Spinner orientationSpinner = (Spinner) dialogLayout.findViewById(R.id.spinner_free_sensors);

      ORIENTATION_PROVIDER[] orientationProviders = ORIENTATION_PROVIDER.values();
      String[] orientationProviderNames = new String[orientationProviders.length];
      for (int i=0; i<orientationProviders.length; i++)
         orientationProviderNames[i] = human(orientationProviders[i].name());
      SpinnerAdapter orientationSpinnerAdapter =
            new ArrayAdapter<String>(RecorderActivity.this, android.R.layout.simple_list_item_1, orientationProviderNames);
      orientationSpinner.setAdapter(orientationSpinnerAdapter);

      ArrayAdapter xtraSensorAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item);
      xtraSensorAdapter.addAll(EXTRA_RECORD_SENSORS_NAMES);
      final MultiSpinner xtraSensorsSpinner = (MultiSpinner) dialogLayout.findViewById(R.id.spinner_free_xsensors);
      xtraSensorsSpinner.setAdapter(xtraSensorAdapter, false,
                                    new MultiSpinner.MultiSpinnerListener()
                                    {
                                       @Override public void onItemsSelected(boolean[] selected) { }
                                    });

      CheckBox postProcessCheckbox = (CheckBox) dialogLayout.findViewById(R.id.checkbox_free_postprocess);
      TextView textFlash = (TextView) dialogLayout.findViewById(R.id.label_free_flash);
      CheckBox flashCheckbox = (CheckBox) dialogLayout.findViewById(R.id.checkbox_free_flash);
      RelativeLayout flashLayout = (RelativeLayout) dialogLayout.findViewById(R.id.layout_free_flash);
//      CheckBox stitchCheckbox = (CheckBox) dialogLayout.findViewById(R.id.checkbox_free_stitching);
      boolean hasFlash = previewSurface.hasFlash();
      if (hasFlash)
      {
         flashLayout.setVisibility(View.VISIBLE);
         flashCheckbox.setVisibility(View.VISIBLE);
         textFlash.setVisibility(View.VISIBLE);
      }
      else
      {
         flashLayout.setVisibility(View.GONE);
         flashCheckbox.setVisibility(View.GONE);
         textFlash.setVisibility(View.GONE);
         flashCheckbox = null;
      }
      TextView cameraApiText = dialogLayout.findViewById(R.id.label_free_api);
      CheckBox cameraApiCheckbox = dialogLayout.findViewById(R.id.checkbox_free_api);
      RelativeLayout cameraApiLayout = dialogLayout.findViewById(R.id.layout_free_api);
      if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP)
      {
         cameraApiLayout.setVisibility(View.VISIBLE);
         cameraApiText.setVisibility(View.VISIBLE);
         cameraApiCheckbox.setVisibility(View.VISIBLE);
      }
      else
      {
         cameraApiLayout.setVisibility(View.GONE);
         cameraApiText.setVisibility(View.GONE);
         cameraApiCheckbox.setVisibility(View.GONE);
         cameraApiCheckbox = null;
      }
      final TextView sizeText = dialogLayout.findViewById(R.id.text_free_maxsize);
      StringBuilder size = new StringBuilder();
      if (freeSpaceGb(DIR, size) <= 0)
         return;
      sizeText.setText(size.toString());

      recordDialog = createRecordDialog(dialogLayout, nameText, resolutionsSpinner, orientationSpinner, sizeText,
                                        recordFreeButton, xtraSensorsSpinner, postProcessCheckbox, flashCheckbox,
                                        cameraApiCheckbox, null, RecordingThread.RecordingType.FREE);
      recordDialog.show();
   }

   boolean isPostProcess = false;

   private AlertDialog createRecordDialog(final ViewGroup dialogLayout, final EditText nameText,
                                          final Spinner resolutionsSpinner,
                                          final Spinner orientationSpinner, final TextView sizeText,
                                          final ImageButton button, final MultiSpinner xtraSensorsSpinner,
                                          final CheckBox postProcessCheckbox,
                                          final CheckBox flashCheckbox, final CheckBox cameraApiCheckbox,
                                          final CheckBox stitchCheckbox, final RecordingThread.RecordingType recordingType)
   //-----------------------------------------------------------------------------------------------------------------
   {
      recordDialog = new AlertDialog.Builder(this).create();
      recordDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
//      recordDialog.setTitle("Recording");
      recordDialog.setView(dialogLayout);
      final ResolutionAdapter resolutionSpinnerAdapter = (ResolutionAdapter) resolutionsSpinner.getAdapter();
      recordDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Ok", new DialogInterface.OnClickListener()
      //=============================================================================================
      {
         public void onClick(DialogInterface dialog, int whichButton)
         //-----------------------------------------------------------
         {
            final StringBuilder errbuf = new StringBuilder();
            final String name = nameText.getText().toString();
            final float increment = -1f;;
//            if (incrementSpinner != null)
//            {
//               String s = (String) incrementSpinner.getSelectedItem();
//               increment = Float.parseFloat(s.trim());
//            }
//            else
//               increment = -1f;
            final String orientationSensorType = inhuman((String) orientationSpinner.getSelectedItem());
            final long maxStorageBytes = freeSpaceGb(sizeText.getText().toString(), errbuf);
            if (maxStorageBytes < 0)
            {
               Toast.makeText(RecorderActivity.this, errbuf.toString(), Toast.LENGTH_LONG).show();
               return;
            }
            isPostProcess = postProcessCheckbox.isChecked();
            final boolean isStitch = ( (stitchCheckbox != null) && (stitchCheckbox.isChecked()) );
            final boolean isFlashOn = ( (flashCheckbox != null) && (flashCheckbox.isChecked()) );
            final boolean useCamera2Api = ( (cameraApiCheckbox != null) && (cameraApiCheckbox.isChecked()) );
            final File headerFile, framesFile, dir = new File(DIR, name);
            if (dir.isDirectory())
            {
               headerFile = new File(dir, name + ".head");
               framesFile = new File(dir, name + ".frames");
            }
            else
            {
               headerFile = new File(DIR, name + ".head");
               framesFile = new File(DIR, name + ".frames");
            }
            boolean[] selectedXtraSensors = xtraSensorsSpinner.getSelected();
            final List<Integer> xtraSensorList = new ArrayList<Integer>();
            for (int i=0; i<selectedXtraSensors.length; i++)
            {
               if (selectedXtraSensors[i])
                  xtraSensorList.add(EXTRA_RECORD_SENSORS[i]);
            }
            if ( ((headerFile.exists()) || (dir.isDirectory())) && (! NO_OVERWRITE_CHECK) )
            {
               final AlertDialog overwriteDialog = new AlertDialog.Builder(RecorderActivity.this).
                                                                                                       setTitle("Confirm Overwrite").setMessage(
                     "Overwrite \"" + headerFile.getAbsolutePath() + "\" ?").create();
               overwriteDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Ok", new DialogInterface.OnClickListener()
                     //================================================================================================
               {
                  @Override
                  public void onClick(DialogInterface dialog, int which)
                  //----------------------------------------------------
                  {
                     if (dir.exists())
                     {
                        RecordingThread.delDirContents(dir);
                        if (dir.exists())
                        {
                           Toast.makeText(RecorderActivity.this,
                                          String.format("Error deleting directory %s", dir.getAbsolutePath()),
                                          Toast.LENGTH_LONG).show();
                           return;
                        }
                     }
                     else
                     {
                        headerFile.delete();
                        framesFile.delete();
                        if (headerFile.exists())
                        {
                           Toast.makeText(RecorderActivity.this,
                                          String.format("Error deleting %s", headerFile.getAbsolutePath()),
                                          Toast.LENGTH_LONG).show();
                           return;
                        }
                        if (framesFile.exists())
                        {
                           Toast.makeText(RecorderActivity.this,
                                          String.format("Error deleting %s", framesFile.getAbsolutePath()),
                                          Toast.LENGTH_LONG).show();
                           return;
                        }
                     }
                     overwriteDialog.dismiss();
                     int[] wh = resolutionSpinnerAdapter.getWidthHeight(currentResolutionIndex);
                     if ( (wh == null) || (wh.length < 2) )
                     {
                        Toast.makeText(RecorderActivity.this, "Could not ascertain available resolutions",
                                       Toast.LENGTH_LONG).show();
                        return;
                     }
                     startRecording(name, wh[0], wh[1], increment, maxStorageBytes, recordingType, orientationSensorType,
                                    button, xtraSensorList, isPostProcess, isFlashOn, useCamera2Api, isStitch);
                  }
               });
               overwriteDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener()
               {
                  @Override public void onClick(DialogInterface dialog, int which)
                  {
                     isStartRecording = false;
                     overwriteDialog.dismiss();
                  }
               });
               overwriteDialog.show();
            }
            else
            {
               recordDialog.dismiss();
               int[] wh = resolutionSpinnerAdapter.getWidthHeight(currentResolutionIndex);
               if ( (wh == null) || (wh.length < 2) )
               {
                  Toast.makeText(RecorderActivity.this, "Could not ascertain available resolutions",
                                 Toast.LENGTH_LONG).show();
                  return;
               }
//               previewSurface.startPreview(wh[0], wh[1], isFlashOn);
               startRecording(name, wh[0], wh[1], increment, maxStorageBytes, recordingType, orientationSensorType,
                              button, xtraSensorList, isPostProcess, isFlashOn, useCamera2Api, isStitch);
            }
         }
      });
      recordDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "Cancel", new DialogInterface.OnClickListener()
      {
         public void onClick(DialogInterface dialog, int whichButton)
         //----------------------------------------------------------
         {
            isStartRecording = false;
            recordDialog.dismiss(); recordDialog = null;
         }
      });
      recordDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Help", new DialogInterface.OnClickListener()
      {
         public void onClick(DialogInterface dialog, int whichButton)
         //----------------------------------------------------------
         {
            if (helpDialog != null)
               helpDialog.dismiss();
            helpDialog = new AlertDialog.Builder(RecorderActivity.this).create();
            helpDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            final LayoutInflater inflater = LayoutInflater.from(RecorderActivity.this);
            final ViewGroup helpLayout = (ViewGroup) inflater.inflate(R.layout.record_help_dialog, null);
            helpDialog.setView(helpLayout);
            WebView helpView = (WebView) helpLayout.findViewById(R.id.help_html);
            final String helpText = readAssetTextFile("record_help.html", null);
            if (helpText == null)
               return;
            helpView.loadData(helpText, "text/html", null);
            helpDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Ok", new DialogInterface.OnClickListener()
            //=============================================================================================
            {
               public void onClick(DialogInterface dialog, int whichButton) { helpDialog.dismiss(); helpDialog = null; }
            });
            helpDialog.show();

         }
      });
      return recordDialog;
   }

   void hideExit()
   //-------------
   {
      runOnUiThread(new Runnable()
      {
         @Override
         public void run()
         {
            if (exitButton != null)
               exitButton.setVisibility(View.GONE);
         }
      });
   }

   void showExit()
   //-------------
   {
      runOnUiThread(new Runnable()
      {
         @Override
         public void run()
         {
            if (exitButton != null)
               exitButton.setVisibility(View.VISIBLE);
         }
      });
   }

   public long freeSpaceGb(File dir, StringBuilder display)
   //------------------------------------------------------
   {
      long gbFree = freeSpaceGb(dir);
      if ( (gbFree < 3) && (! NO_OVERWRITE_CHECK) )
      {
         Toast.makeText(this, "Not enough free space in directory " + dir.getAbsolutePath() + " for recording",
                        Toast.LENGTH_LONG).show();
         return -gbFree;
      }
      String space = null;
      BigDecimal bd = new BigDecimal(gbFree).multiply(new BigDecimal(0.9));
      long gb = bd.longValue();
      if ( (display != null) && (gb > 0) )
         display.append(bd.divide(new BigDecimal(1073741824L), new MathContext(2, RoundingMode.HALF_DOWN)).toPlainString()).
                 append('G');
      return gb;
   }

   public long freeSpaceGb(String s, StringBuilder errbuf)
   //-----------------------------------------------------
   {
      if (s == null) return -1;
      s = s.trim().toUpperCase();
      long gb = -1;
      int i = s.lastIndexOf('G');
      if (i < 0)
      {
         i = s.lastIndexOf('M');
         if (i >= 0)
         {
            try { gb = parseFreeSpace(s.substring(0, i), 1048576L); } catch (Exception e) { gb = -1; }
         }
      }
      else
      {
         try { gb = parseFreeSpace(s.substring(0, i), 1073741824L); } catch (Exception e) { gb = -1; }
      }
      if (i < 0)
         try { gb = Long.parseLong(s); } catch (Exception e) { gb = -1; }
      if (gb == -1)
      {
         if (errbuf != null)
            errbuf.append("Could not parse number: ").append(s);
         return -1L;
      }
      if ( (gb < 3*1073741824L) && (! NO_OVERWRITE_CHECK) )
      {
         if (errbuf != null)
         {
            double v = gb / 1073741824.0;
            errbuf.append(String.format(Locale.US, "%.1fGb not enough space for recording", v));
         }
         return -gb;
      }
      return gb;
   }

   private long parseFreeSpace(String s, long mult)
   //----------------------------------------------
   {
      long gb = -1L;
      try
      {
         gb = Long.parseLong(s) * mult;
      }
      catch (Exception e)
      {
         double v;
         try
         {
            v = Double.parseDouble(s);
            gb = (long) (v * mult);
         }
         catch (Exception ee)
         {
            gb = -1;
            Log.e(TAG, "", e);
         }
      }
      return gb;
   }

   public static long freeSpaceGb(File dir)
   //--------------------------------------
   {
      try
      {
         StatFs stat = new StatFs(dir.getAbsolutePath());
         return stat.getBlockSizeLong() * stat.getBlockCountLong();
      }
      catch (Exception e)
      {
         Log.e(TAG, "", e);
         return 0;
      }
   }

   private String human(String name) { return (Character.toUpperCase(name.charAt(0)) + name.substring(1)).replace("_",
                                                                                                                  " "); }

   private String inhuman(String s) { return s.toUpperCase().replace(" ", "_"); }

   private void startRecording(String name, int width, int height, float increment, long maxsize,
                               RecordingThread.RecordingType recordingType, String orientationType, ImageButton button,
                               List<Integer> xtraSensorList, boolean isPostProcess, boolean isFlashOn,
                               boolean useCamera2Api, boolean isStitch)
   //-------------------------------------------------------------------------------------------------------------
   {
      recordDialog = null;
      StringBuilder errbuf = new StringBuilder();
      if ( (isRecording) || (previewSurface.isRecording()) ) return;
//      if (! previewSurface.initOrientationSensor(orientationType, errbuf))
//      {
//         Toast.makeText(this, "Initialise Orientation Sensor: " + errbuf.toString(), Toast.LENGTH_LONG).show();
//         Log.e(TAG, "Initialise Orientation Sensor: " + errbuf.toString());
//         statusText.setVisibility(View.VISIBLE);
//         statusText.setText("Initialise Orientation Sensor: " + errbuf.toString());
//         return;
//      }

      statusProgress.setVisibility(View.VISIBLE);
      statusText.setVisibility(View.VISIBLE);
      locationLabel.setVisibility(View.VISIBLE);
      locationText.setVisibility(View.VISIBLE);

      ORIENTATION_PROVIDER orientationProviderType = ORIENTATION_PROVIDER.valueOf(orientationType);
      isRecording = previewSurface.startRecording(DIR, width, height, name, increment, maxsize, recordingType,
                                                  orientationProviderType, xtraSensorList, isPostProcess, isFlashOn,
                                                  useCamera2Api, isStitch);
      isStartRecording = false;
      if (isRecording)
      {
         button.setImageResource(R.drawable.stop);
         for (ImageButton b : recordingButtons)
            if (b != button)
               b.setVisibility(View.GONE);
         pauseButton.setVisibility(View.VISIBLE);
         statusText.setText("Recording");
      }
      else
      {
         previewSurface.stopRecording(true);
         button.setImageResource(R.drawable.start);
         for (ImageButton b : recordingButtons)
            b.setVisibility(View.VISIBLE);
         pauseButton.setVisibility(View.GONE);
         statusText.setText("");
      }
//      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
//      {
//         hideHandler.removeCallbacks(hideRunner);
//         hideHandler.postDelayed(hideRunner, 1500);
//      }
   }

   public void stoppingRecording(File recordHeaderFile, File recordFramesFile, boolean isCancelled) { }

   protected void stoppedRecording(RecordingThread.RecordingType recordingType, final File recordHeaderFile,
                                   final File recordFramesFile)
   //--------------------------------------------------------------------------------------
   {
      isRecording = false;
      runOnUiThread(new Runnable()
      //==========================
      {
         @Override
         public void run()
         //-------------------------
         {
            record360Button.setImageResource(R.drawable.three60);
            recordFreeButton.setImageResource(R.drawable.frame);
            recordFreeButton.setVisibility(View.VISIBLE);
            for (ImageButton b : recordingButtons)
               b.setVisibility(View.VISIBLE);
            pauseButton.setVisibility(View.INVISIBLE);
            statusProgress.setProgress(0);

//             bearingDestLabel.setVisibility(View.INVISIBLE);
//             bearingDestText.setVisibility(View.INVISIBLE);
            statusProgress.setVisibility(View.INVISIBLE);
            statusText.setVisibility(View.INVISIBLE);
            locationLabel.setVisibility(View.INVISIBLE);
            locationText.setVisibility(View.INVISIBLE);

            if ((recordHeaderFile != null) && (recordHeaderFile.exists()) && (recordHeaderFile.length() > 0) &&
                  (recordFramesFile != null) && (recordFramesFile.exists()) && (recordFramesFile.length() > 0))
            {
               Toast.makeText(RecorderActivity.this, String.format(Locale.US, "Saved recording to %s, %s",
                                                                   recordHeaderFile.getAbsolutePath(),
                                                                   recordFramesFile.getAbsolutePath()),
                              Toast.LENGTH_LONG).show();
               statusText.setText(
                     String.format("Saved %s, %s in %s", recordHeaderFile.getName(), recordFramesFile.getName(),
                                   recordFramesFile.getParentFile().getAbsolutePath()));
            }
            else if (isPostProcess)
            {
               statusText.setText(String.format("Saved raw recording files%s. Use desktop postprocessor to process",
                                                (recordHeaderFile == null) ? "" : (" in " + recordHeaderFile.getParent())));
               isPostProcess = false;
            }
         }
      });
   }

   public void userRecalibrate(final ConditionVariable cond, final String message)
   //-----------------------------------------------------------------------------
   {
      runOnUiThread(new Runnable()
      {
         @Override
         public void run()
         //--------------
         {
            final AlertDialog calibrateDialog = new AlertDialog.Builder(RecorderActivity.this).setTitle("Calibrate").
                  setMessage(message).create();
            calibrateDialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK", new DialogInterface.OnClickListener()
            {
               @Override
               public void onClick(DialogInterface dialog, int which) { cond.open(); }
            });
            calibrateDialog.show();
         }
      });

   }

   private void setupFullScreen(final View controlsView)
   //---------------------------------------------------
   {
      ActionBar actionBar = getActionBar();
      if (actionBar != null)
         actionBar.hide();
      systemUiHider = SystemUiHider.getInstance(this, previewSurface, SystemUiHider.HIDER_FLAGS);
      systemUiHider.setup();
      systemUiHider.setOnVisibilityChangeListener(new SystemUiHider.OnVisibilityChangeListener()
            //========================================================================================
      {
         @Override
         public void onVisibilityChange(boolean visible)
         //--------------------------------------------
         {
            if (isDrawerOpen)
               controlsView.setVisibility(visible ? View.VISIBLE : View.GONE);
            if (visible && SystemUiHider.AUTO_HIDE)
               delayedHide(AUTO_HIDE_DELAY_MILLIS);
         }
      });

      previewSurface.setOnClickListener(new View.OnClickListener()
            //==========================================================
      {
         @Override
         public void onClick(View view)
         //---------------------------
         {
            if (SystemUiHider.TOGGLE_ON_CLICK)
               systemUiHider.toggle();
            else
               systemUiHider.show();
         }
      });
   }

   private void delayedHide(int delayMillis)
   {
      hideHandler.removeCallbacks(hideRunnable);
      hideHandler.postDelayed(hideRunnable, delayMillis);
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

   @Override
   protected void onSaveInstanceState(Bundle B)
   //-------------------------------------------------
   {
      super.onSaveInstanceState(B);
      previewSurface.onSaveInstanceState(B);
   }

   @Override
   protected void onPause()
   //----------------------
   {
      super.onPause();
      previewSurface.onPause();
   }

   @Override
   protected void onRestoreInstanceState(Bundle savedInstanceState)
  //---------------------------------------------------------------
   {
      super.onRestoreInstanceState(savedInstanceState);
      previewSurface.onRestoreInstanceState(savedInstanceState);
   }

   private BaseLoaderCallback loaderCallback = new BaseLoaderCallback(this)
   //======================================================================
   {
      @Override
      public void onManagerConnected(int status)
      //-----------------------------------------
      {
         switch (status)
         {
            case LoaderCallbackInterface.SUCCESS:
               Log.i(TAG, "OpenCV loaded successfully");
               isOpenCVJava = true;

//               StringBuilder gpuStatus = new StringBuilder();
//               boolean isGPU = CV.GPUSTATUS(gpuStatus);
//               Log.i(TAG, "OpenCV GPU status:\n" + gpuStatus);
               break;

            case LoaderCallbackInterface.INIT_FAILED:
               try
               {
                  if (OpenCVLoader.initDebug())
                     isOpenCVJava = true;
               }
               catch (Exception ee)
               {
                  isOpenCVJava = false;
               }
               break;

            default:
               super.onManagerConnected(status);
               break;
         }
      }
   };

   private boolean isOpenCVJava = false;
   public boolean isOpenCVJava() { return isOpenCVJava; }

   @Override
   protected void onResume()
   //-----------------------
   {
      super.onResume();
//      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
//      {
//         if (Build.SUPPORTED_64_BIT_ABIS.length > 0)
//         {
//            Log.i("", Build.SUPPORTED_64_BIT_ABIS[0]);
//            System.loadLibrary("opencv_java3");
//         }
//      }

      // Replace with OpenCVLoader.initAsync if/when 64 bit works with OpenCV Manager
//      OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_1_0, this, loaderCallback);

      isOpenCVJava = false;
      if (OpenCVLoader.initDebug())
         isOpenCVJava = true;
//      if ( (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) &&
//           (Build.SUPPORTED_64_BIT_ABIS.length > 0) )
//      {
//         Throwable ex = null;
//         try
//         {
//            if (OpenCVLoader.initDebug())
//               isOpenCVJava = true;
//         }
//         catch (Exception ee)
//         {
//            ex = ee;
//            isOpenCVJava = false;
//         }
//         if (! isOpenCVJava)
//         {
//            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_1_0, this, loaderCallback);
//            Log.e(TAG, "Error loading internal OpenCV Java binding. Trying OpenCV Manager...", ex);
//         }
//      }
      else
         OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION_3_1_0, this, loaderCallback);

      previewSurface.onResume();
      if ( (previewSurface.isRecording()) || (previewSurface.isStoppingRecording()) )
      {
//         bearingDestLabel.setVisibility(View.VISIBLE);
//         bearingDestText.setVisibility(View.VISIBLE);
         statusProgress.setVisibility(View.VISIBLE);
         statusText.setVisibility(View.VISIBLE);
         locationLabel.setVisibility(View.VISIBLE);
         locationText.setVisibility(View.VISIBLE);
         isRecording = true;
         isStartRecording = false;
         record360Button.setImageResource(R.drawable.record_red);
         if (! previewSurface.isStoppingRecording())
            statusText.setText("Recording");
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
            {
               permissionCount++;
               if (grantResults[i] == PackageManager.PERMISSION_DENIED)
               {
                  final AlertDialog deathDialog =
                        new AlertDialog.Builder(RecorderActivity.this).setTitle("Last Words").
                            setMessage("Without " + permissions[i] + " I have no raison d'être.").setCancelable(false).
                            create();
                  deathDialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK", new DialogInterface.OnClickListener()
                  {
                     @Override public void onClick(DialogInterface dialog, int which)
                     //--------------------------------------------------------------
                     {
                        RecorderActivity.this.finish();
                        System.exit(1);
                     }
                  });
                  deathDialog.show();
                  return;
               }
            }
            break;
         case 2:
            for (int i=0; i<grantResults.length; i++)
            {
               permissionCount++;
               if (grantResults[i] == PackageManager.PERMISSION_DENIED)
               {
                  if (shouldShowRequestPermissionRationale(permissions[i]))
                  {
                     if ( (permissions[i].equalsIgnoreCase(Manifest.permission.ACCESS_COARSE_LOCATION)) ||
                          (permissions[i].equalsIgnoreCase(Manifest.permission.ACCESS_FINE_LOCATION)) )
                        locationsDenied++;
                  }
               }
            }

            break;
      }
      if ( (permissionCount == ESSENTIAL_PERMISSIONS.length) && (! hasPermissions(RecorderActivity.OPTIONAL_PERMISSIONS)) )
         requestPermissions(RecorderActivity.OPTIONAL_PERMISSIONS, 2);
      else if (permissionCount == (ESSENTIAL_PERMISSIONS.length + OPTIONAL_PERMISSIONS.length))
      {
         if (locationsDenied == 2)
         {
            Toast.makeText(this, "GPS/Network Location Services required for recording location. If denied then location will not be recorded.",
                           Toast.LENGTH_LONG).show();
            isLocationRecorded = false;
         }
//         CoordinatorLayout layoutSnack = (CoordinatorLayout) findViewById(R.id.snack_layout);
//         if (layoutSnack == null)
//         {
//            final LayoutInflater inflater = LayoutInflater.from(this);
//            final ViewGroup snackLayout = (ViewGroup) inflater.inflate(R.layout.snack, null);
//            layoutSnack = (CoordinatorLayout) snackLayout.findViewById(R.id.snack_layout);
//         }
//         final Snackbar snackbar = Snackbar.make(layoutSnack,
//                                                 "Permissions updated. Please restart application to continue",
//                                                  Snackbar.LENGTH_INDEFINITE);
//         snackbar.setAction("Dismiss", new View.OnClickListener()
//         {
//            @Override public void onClick(View v)
//            //-----------------------------------
//            {
//               snackbar.dismiss();
//               RecorderActivity.this.finish();
//               System.exit(1);
//            }
//         });
//         snackbar.show();
         final AlertDialog permissionsDialog =
               new AlertDialog.Builder(RecorderActivity.this).setTitle("Permissions Saved").
                     setMessage("Permissions updated. Please restart application to continue").setCancelable(false).create();
         permissionsDialog.setButton(AlertDialog.BUTTON_POSITIVE, "OK", new DialogInterface.OnClickListener()
         {
            @Override
            public void onClick(DialogInterface dialog, int which)
            {
               RecorderActivity.this.finish();
               System.exit(1);
            }
         });
         permissionsDialog.show();
      }
   }

   public void onGpsConnected(final boolean isOn)
   //--------------------------------------
   {
//      runOnUiThread(new Runnable()
//      {
//         @Override
//         public void run()
//         {
//            if (isOn)
//             gpsOnOffImage.setImageResource(R.drawable.gps_on);
//            else
//             gpsOnOffImage.setImageResource(R.drawable.gps_off);
//         }
//      });
   }

   public void onNetLocationConnected(final boolean isOn)
   //----------------------------------------------
   {
//      runOnUiThread(new Runnable()
//      {
//         @Override
//         public void run()
//         {
//            if (isOn)
////               gpsOnOffImage.setImageResource(R.drawable.gps_net);
//         }
//      });
   }

   public void onResolutionChange(String[] resolutions)
   //-------------------------------------------------------
   {
      if (resolutions.length > 0)
      {
         int i = 0;
         currentResolutionIndex = -1;
         for (String resolution : resolutions)
         {
            int[] wh = ResolutionAdapter.getWidthHeight(resolution);
            if (wh[0] == 640)
            {
               currentResolutionIndex = i;
               break;
            }
            i++;
         }
         this.resolutions = resolutions;
         if (currentResolutionIndex < 0)
            currentResolutionIndex = this.resolutions.length >> 1;
//         int[] wh = ResolutionAdapter.getWidthHeight(this.resolutions[currentResolutionIndex]);
//         previewSurface.startPreview(wh[0], wh[1]);
      }
   }

   public boolean onPreviewTouch(MotionEvent event)
   //---------------------------------------------
   {
      if (isOpeningDrawer)
         return false;
//      if ((displayFlags & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0)
//      {
//         if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
//         {
//            hideHandler.removeCallbacks(hideRunner);
//            hideHandler.postDelayed(hideRunner, 1500);
//         }
//         return true;
//      }
      return false;
   }

   public void onLocationChanged(final Location location)
   //----------------------------------------------
   {
      updateLocationRunner.location = location;
      runOnUiThread(updateLocationRunner);
   }

   public void onBackgroundStatusUpdate(ProgressParam params)
   //----------------------------------------------
   {
      if (! isDrawerOpen)
         return;
      updateStatusRunner.params = params;
      runOnUiThread(updateStatusRunner);
   }

   public void onStatusUpdate(ProgressParam params)
   //----------------------------------------------------------------
   {
      if (! isDrawerOpen)
      {
         if ( (params.status != null) && (params.isToast) )
         {
            if (lastStatusToast != null)
               lastStatusToast.cancel();
            lastStatusToast = Toast.makeText(RecorderActivity.this, params.status, params.toastDuration);
            lastStatusToast.show();
         }
         return;
      }
//      if (params.bearing >= 0)
//      {
//         bearingText.setText(String.format("%.4f", params.bearing));
//         if (params.targetBearing >= 0)
//         {
//            Spannable destSpan = new SpannableString(String.format("%.4f", params.targetBearing));
//            final ForegroundColorSpan spanColor;
//            if ((params.color != null) && (params.color[0] != Float.MIN_VALUE))
//               spanColor = new ForegroundColorSpan(
//                     Color.argb(255, (int) (params.color[0] * 255.0), (int) (params.color[1] * 255.0),
//                                (int) (params.color[2] * 255.0)));
//            else
//               spanColor = new ForegroundColorSpan(Color.GREEN);
//            destSpan.setSpan(spanColor, 0, destSpan.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//            bearingDestText.setText(destSpan);
//         } else
//            bearingDestText.setText("");
//      }
      if (params.status != null)
         statusText.setText(params.status);
      if (params.progress >= 0)
         statusProgress.setProgress(params.progress);
      params.status = null;
      params.isToast = false;
      params.progress = -1;

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

   static private class ResolutionAdapter extends ArrayAdapter<String>
   //=================================================================
   {
      static final Pattern RESOLUTION_PATTERN = Pattern.compile("(\\d+)x(\\d+)");

      private ResolutionAdapter(Context context, int resource, String[] resolutions)
      {
         super(context, resource, (resolutions != null) ? resolutions : new String[0]);
      }

      public int[] getWidthHeight(int position)
      //---------------------------------------
      {
         if (position < 0)
            return null;
         return getWidthHeight(getItem(position));
      }

      static public int[] getWidthHeight(String previewResolution)
      //----------------------------------------------------------
      {
         if (previewResolution == null)
            return null;
         Matcher matcher = RESOLUTION_PATTERN.matcher(previewResolution);
         if (!matcher.matches())
         {
            Log.e(TAG, "Invalid resolution pattern: " + previewResolution);
            throw new RuntimeException("Invalid resolution pattern: " + previewResolution);
         }
         int width = -1, height = -1;
         if (matcher.groupCount() > 0)
            width = Integer.parseInt(matcher.group(1));
         if (matcher.groupCount() > 1)
            height = Integer.parseInt(matcher.group(2));
         return new int[] { width, height };
      }
   }

   public String readAssetTextFile(String name, String def)
   //---------------------------------------------------
   {
      AssetManager am = getAssets();
      InputStream is = null;
      BufferedReader br = null;
      try
      {
         is = am.open(name);
         br = new BufferedReader(new InputStreamReader(is));
         String line = null;
         StringBuilder sb = new StringBuilder();
         while ((line = br.readLine()) != null)
            sb.append(line).append("\n");
         return sb.toString();
      }
      catch (Exception e)
      {
         Log.e(TAG, "Reading asset file " + name, e);
         return def;
      }
      finally
      {
         if (br != null)
            try { br.close(); } catch (Exception _e) { }
         if (is != null)
            try { is.close(); } catch (Exception _e) { }
      }
   }

   public int getDeviceRotation() { return getWindowManager().getDefaultDisplay().getRotation(); }

   static Location createLocation(double latitude, double longitude)
   //-------------------------------------------------------------
   {
      return createLocation(true, latitude, longitude, 0.0f, 0.0f);
   }

   static Location createLocation(double latitude, double longitude, double altitude)
   //---------------------------------------------------------------------------------
   {
      return createLocation(true, latitude, longitude, altitude, 0.0f);
   }

   static Location createLocation(boolean isGps, double latitude, double longitude, double altitude)
   //-----------------------------------------------------------------------------------------------
   {
      return createLocation(isGps, latitude, longitude, altitude, 0.0f);
   }

   static Location createLocation(boolean isGps, double latitude, double longitude, double altitude, float accuracy)
   //---------------------------------------------------------------------------------------------------------------
   {
      Location location;
      if (isGps)
         location = new Location(LocationManager.GPS_PROVIDER);
      else
         location = new Location(LocationManager.NETWORK_PROVIDER);
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
      location.setAccuracy(accuracy);
      return location;
   }

   public static String imageFormatString(int imageFormat)
   //-----------------------------------------------------
   {
      switch (imageFormat)
      {
         case ImageFormat.NV21: return "NV21";
         case 0x23: return "YUV_420_888";
         case ImageFormat.NV16: return "NV16";
         case 0x27: return "YUV_422_888";
         case 0x28: return "YUV_444_888";
         case ImageFormat.JPEG: return "JPEG";
         case ImageFormat.RGB_565: return "RGB_565";
         case 0x29: return "FLEX_RGB_888";
         case 0x2A: return "FLEX_RGBA_8888";
         case ImageFormat.YUY2: return "YUY2";
         case ImageFormat.YV12: return "YV12";
         case 0x44363159: return "DEPTH16";
         case 0x101: return "DEPTH_POINT_CLOUD";
         case 0x25: return "RAW10";
         case 0x26: return "RAW12";
         case ImageFormat.UNKNOWN:
         default: return "UNKNOWN";
      }
   }

//   public void save()
//   //----------------
//   {
//      if (previewSurface == null) return;
//      byte[] bearing = previewSurface.getLastBuffer();
//      if (bearing == null) return;
//      int width = previewSurface.getPreviewWidth();
//      int height = previewSurface.getPreviewHeight();
//      BufferedOutputStream bos = null;
//      try
//      {
//         File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "data.rgb");
//         bos = new BufferedOutputStream(new FileOutputStream(file));
//         bos.write(bearing);
//
//         int[] colors = new int[bearing.length/4];
//         int i=0, j = 0;
//         while (i<bearing.length)
//         {
//            int r = (int) bearing[i++];
//            if (r < 0) r = 256 + r;
//            int g = (int) bearing[i++];
//            if (g < 0) g = 256 + g;
//            int b = (int) bearing[i++];
//            if (b < 0) b = 256 + b;
//            int a = bearing[i++];
//            if (a < 0) a = 256 + a;
//            colors[j++] = Color.argb(a, r, g, b);
//         }
//         Bitmap bmp = Bitmap.createBitmap(colors, width, height, Bitmap.Config.ARGB_8888);
//         file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "data.png");
//         BufferedOutputStream out = null;
//         try
//         {
//            out = new BufferedOutputStream(new FileOutputStream(file));
//            if (! bmp.compress(Bitmap.CompressFormat.PNG, 0, out))
//               Toast.makeText(this, "png save failed", Toast.LENGTH_LONG).show();
//         }
//         catch (Exception _e)
//         {
//            Toast.makeText(this, _e.getMessage(), Toast.LENGTH_LONG).show();
//            _e.printStackTrace();
//         }
//         finally
//         {
//            if (out != null)
//               try { out.close(); } catch (Throwable _e) { }
//         }
//      }
//      catch (Exception e)
//      {
//         Log.e(TAG, "", e);
//         Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
//      }
//      finally
//      {
//         if (bos != null)
//            try { bos.close(); } catch (Exception _e) {}
//      }
//   }
}
