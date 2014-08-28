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

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import com.doubleTwist.drawerlib.ADrawerLayout;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static to.augmented.reality.android.common.sensor.orientation.OrientationProvider.ORIENTATION_PROVIDER;

public class RecorderActivity extends Activity
//============================================
{
   private static final String TAG = RecorderActivity.class.getSimpleName();
   static File DIR;
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
   final static private int MSG_BEARING = 1;
   final static private int MSG_LOCATION = 2;

   ADrawerLayout drawerLayout;
   ARSurfaceView previewSurface;
   private ImageButton recordButton = null;
   //   private ImageView gpsOnOffImage;

   private String currentResolution = "";
   int currentResolutionIndex = -1;
   private TextView locationText, bearingText, bearingDestLabel, bearingDestText, statusText, locationLabel;
   private ProgressBar statusProgress;
   private boolean isResolutionSelected = false;
   View decorView = null;
   int uiOptions;

   final Handler hideHandler = new Handler();
   Runnable hideRunner = new Runnable()
   //==================================
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

   Toast lastStatusToast = null;

   private int displayFlags = 0;
   boolean isOpeningDrawer = false, isDrawerOpen = false;
   private String[] resolutions = null;
   private boolean isRecording = false, isStartRecording = false;

   @Override
   protected void onCreate(Bundle B)
   //--------------------------------
   {
      super.onCreate(B);
      requestWindowFeature(Window.FEATURE_NO_TITLE);
      setupFullScreen();

      setContentView(R.layout.activity_recorder);
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
               hideHandler.postDelayed(hideRunner, 1500);
            }
         }

         @Override
         public void onOpen(ADrawerLayout dl, ADrawerLayout.DrawerState state)
         //-------------------------------------------------------------------
         {
            isDrawerOpen = true;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
            {
               hideHandler.removeCallbacks(hideRunner);
               hideHandler.postDelayed(hideRunner, 1500);
            }
         }
         // @formatter:on
      });
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN)
         drawerLayout.open();

      previewSurface = (ARSurfaceView) findViewById(R.id.camera_preview_surface);
      locationLabel = (TextView) findViewById(R.id.location_label);
      locationText = (TextView) findViewById(R.id.location_text);
      bearingText = (TextView) findViewById(R.id.bearing_text);
      bearingDestLabel = (TextView) findViewById(R.id.dest_bearing_label);
      bearingDestText = (TextView) findViewById(R.id.dest_bearing_text);
      statusText = (TextView) findViewById(R.id.status_text);
      statusProgress = (ProgressBar) findViewById(R.id.status_progress);
      bearingDestLabel.setVisibility(View.INVISIBLE);
      bearingDestText.setVisibility(View.INVISIBLE);
      statusProgress.setVisibility(View.INVISIBLE);
      statusText.setVisibility(View.INVISIBLE);
      locationLabel.setVisibility(View.INVISIBLE);
      locationText.setVisibility(View.INVISIBLE);

//      gpsOnOffImage = (ImageView) findViewById(R.id.gps_on_off);
//      final ImageButton pinButton = (ImageButton) findViewById(R.id.button_pin);
      recordButton = (ImageButton) findViewById(R.id.button_start);
      recordButton.setOnClickListener(new View.OnClickListener()
      {
         @Override
         public void onClick(View v)
         //------------------------
         {
            if (! isRecording)
               startRecording();
            else
            {
               final AlertDialog partialSaveDialog = new AlertDialog.Builder(RecorderActivity.this).setTitle("Partial Save").
                                                     setMessage("Save incomplete recording").create();
               partialSaveDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Yes", new DialogInterface.OnClickListener()
               {
                  @Override public void onClick(DialogInterface dialog, int which) { previewSurface.stopRecording(false); }
               });
               partialSaveDialog.setButton(AlertDialog.BUTTON_NEGATIVE, "No", new DialogInterface.OnClickListener()
               {
                  @Override
                  public void onClick(DialogInterface dialog, int which) { previewSurface.stopRecording(true); }
               });
               partialSaveDialog.show();
            }
         }
      });
      if (B != null)
         previewSurface.onRestoreInstanceState(B);
   }

   AlertDialog recordDialog = null;

   private void startRecording()
   //---------------------------
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
      LayoutInflater inflater = LayoutInflater.from(this);
      final ViewGroup dialogLayout = (ViewGroup) inflater.inflate(R.layout.start_recording, null);
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
      final Spinner modeSpinner = (Spinner) dialogLayout.findViewById(R.id.spinner_mode);
      final Spinner incrementSpinner = (Spinner) dialogLayout.findViewById(R.id.spinner_increments);
      incrementSpinner.setSelection(2);
      final Spinner orientationSpinner = (Spinner) dialogLayout.findViewById(R.id.spinner_sensors);
      ORIENTATION_PROVIDER[] orientationProviders = ORIENTATION_PROVIDER.values();
      String[] orientationProviderNames = new String[orientationProviders.length];
      for (int i=0; i<orientationProviders.length; i++)
         orientationProviderNames[i] = human(orientationProviders[i].name());
      SpinnerAdapter orientationSpinnerAdapter =
            new ArrayAdapter<String>(RecorderActivity.this, android.R.layout.simple_list_item_1, orientationProviderNames);
      orientationSpinner.setAdapter(orientationSpinnerAdapter);
      GLRecorderRenderer.RecordFileFormat[] formats = GLRecorderRenderer.RecordFileFormat.values();
      String[] formatNames = new String[formats.length];
      for (int i=0; i<formats.length; i++)
         formatNames[i] = formats[i].name();
      SpinnerAdapter formatAdapter = new ArrayAdapter<String>(RecorderActivity.this, android.R.layout.simple_list_item_1,
                                                              formatNames);
      final Spinner formatSpinner = (Spinner) dialogLayout.findViewById(R.id.spinner_formats);
      formatSpinner.setAdapter(formatAdapter);

      recordDialog = new AlertDialog.Builder(this).create();
      recordDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
//      recordDialog.setTitle("Recording");
      recordDialog.setView(dialogLayout);
      recordDialog.setButton(AlertDialog.BUTTON_POSITIVE, "Ok", new DialogInterface.OnClickListener()
      //=============================================================================================
      {
         public void onClick(DialogInterface dialog, int whichButton)
         //-----------------------------------------------------------
         {
            final String name = nameText.getText().toString();
            String s = (String) incrementSpinner.getSelectedItem();
            final float increment = Float.parseFloat(s.trim());
            final String orientationSensorType = inhuman((String) orientationSpinner.getSelectedItem());
            final String fileFormat = (String) formatSpinner.getSelectedItem();
            final File headerFile = new File(DIR, name + ".head");
            final File framesFile = new File(DIR, name + ".frames");
            final GLRecorderRenderer.RecordMode mode;
            s = (String) modeSpinner.getSelectedItem();
            String[] modes = getResources().getStringArray(R.array.recording_modes);
            if (s.equalsIgnoreCase(modes[0]))
               mode = GLRecorderRenderer.RecordMode.TRAVERSE;
            else
               mode = GLRecorderRenderer.RecordMode.RETRY;

            if (headerFile.exists())
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
                     overwriteDialog.dismiss();
                     int[] wh = resolutionSpinnerAdapter.getWidthHeight(currentResolutionIndex);
                     previewSurface.startPreview(wh[0], wh[1]);
                     startRecording(name, increment, orientationSensorType, fileFormat, mode);
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
            } else
            {
               recordDialog.dismiss();
               int[] wh = resolutionSpinnerAdapter.getWidthHeight(currentResolutionIndex);
               previewSurface.startPreview(wh[0], wh[1]);
               startRecording(name, increment, orientationSensorType, fileFormat, mode);
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
      recordDialog.show();
   }

   private String human(String name) { return (Character.toUpperCase(name.charAt(0)) + name.substring(1)).replace("_", " "); }

   private String inhuman(String s) { return s.toUpperCase().replace(" ", "_"); }

   private void startRecording(String name, float increment, String orientationType, String fileFormat,
                               GLRecorderRenderer.RecordMode mode)
   //--------------------------------------------------------------------------------------------------
   {
      recordDialog = null;
      if ( (isRecording) || (previewSurface.isRecording()) ) return;
      if (! previewSurface.initOrientationSensor(orientationType))
      {
         Toast.makeText(this, "ERROR: Device does not appear to have required orientation sensors", Toast.LENGTH_LONG).show();
         return;
      }
      previewSurface.setRecordFileFormat(fileFormat);
      bearingDestLabel.setVisibility(View.VISIBLE);
      bearingDestText.setVisibility(View.VISIBLE);
      statusProgress.setVisibility(View.VISIBLE);
      statusText.setVisibility(View.VISIBLE);
      locationLabel.setVisibility(View.VISIBLE);
      locationText.setVisibility(View.VISIBLE);
      isRecording = previewSurface.startRecording(DIR, name, increment, mode);
      isStartRecording = false;
      if (isRecording)
      {
         recordButton.setImageResource(R.drawable.record_red);
         statusText.setText("Recording");
      }
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
      {
         hideHandler.removeCallbacks(hideRunner);
         hideHandler.postDelayed(hideRunner, 1500);
      }
   }

   public void stoppingRecording(File recordHeaderFile, File recordFramesFile, boolean isCancelled) { }

   protected void stoppedRecording(final File recordHeaderFile, final File recordFramesFile)
   //--------------------------------------------------------------------------------------
   {
      isRecording = false;
      runOnUiThread(new Runnable()
      //==========================
      {
         @Override public void run()
         //-------------------------
         {
            recordButton.setImageResource(R.drawable.record_green);
            statusProgress.setProgress(0);

             bearingDestLabel.setVisibility(View.INVISIBLE);
             bearingDestText.setVisibility(View.INVISIBLE);
             statusProgress.setVisibility(View.INVISIBLE);
             statusText.setVisibility(View.INVISIBLE);
             locationLabel.setVisibility(View.INVISIBLE);
             locationText.setVisibility(View.INVISIBLE);

               if ( (recordHeaderFile != null) && (recordHeaderFile.exists()) && (recordHeaderFile.length() > 0) &&
                     (recordFramesFile != null) && (recordFramesFile.exists()) && (recordFramesFile.length() > 0) )
               {
                  Toast.makeText(RecorderActivity.this, String.format(Locale.US, "Saved recording to %s, %s",
                                                                      recordHeaderFile.getAbsolutePath(),
                                                                      recordFramesFile.getAbsolutePath()),
                                 Toast.LENGTH_LONG).show();
                  statusText.setText(
                        "Saved " + recordHeaderFile.getName() + ", " + recordFramesFile.getName() + " in " +
                              recordFramesFile.getParentFile().getAbsolutePath());
               }
         }
      });
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
      {
         hideHandler.removeCallbacks(hideRunner);
         hideHandler.postDelayed(hideRunner, 1500);
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
            RecorderActivity.this.displayFlags = visibility;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN)
            {
               ActionBar bar = RecorderActivity.this.getActionBar();
               if (bar != null)
                  bar.hide();
               RecorderActivity.this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                                                          WindowManager.LayoutParams.FLAG_FULLSCREEN);
            }
            else// if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
            {
               hideHandler.removeCallbacks(hideRunner);
               hideHandler.postDelayed(hideRunner, 1500);
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

   @Override
   protected void onResume()
   //-----------------------
   {
      super.onResume();
      previewSurface.onResume();
      if ( (previewSurface.isRecording()) || (previewSurface.isStoppingRecording()) )
      {
         bearingDestLabel.setVisibility(View.VISIBLE);
         bearingDestText.setVisibility(View.VISIBLE);
         statusProgress.setVisibility(View.VISIBLE);
         statusText.setVisibility(View.VISIBLE);
         locationLabel.setVisibility(View.VISIBLE);
         locationText.setVisibility(View.VISIBLE);
         isRecording = true;
         isStartRecording = false;
         recordButton.setImageResource(R.drawable.record_red);
         if (! previewSurface.isStoppingRecording())
            statusText.setText("Recording");
      }
      setupFullScreen();
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

   public void onResolutionChange(final String[] resolutions)
   //-------------------------------------------------------
   {
      this.resolutions = resolutions;
      if (resolutions.length > 0)
      {
         currentResolutionIndex = this.resolutions.length >> 1;
         int[] wh = ResolutionAdapter.getWidthHeight(this.resolutions[currentResolutionIndex]);
         previewSurface.startPreview(wh[0], wh[1]);
      }
   }

   public boolean onPreviewTouch(MotionEvent event)
   //---------------------------------------------
   {
      if (isOpeningDrawer)
         return false;
      if ((displayFlags & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) == 0)
      {
         if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
         {
            hideHandler.removeCallbacks(hideRunner);
            hideHandler.postDelayed(hideRunner, 1500);
         }
         return true;
      }
      return false;
   }

   public void onLocationChanged(final Location location)
   //----------------------------------------------
   {
      updateLocationRunner.location = location;
      runOnUiThread(updateLocationRunner);
   }

   public void onStatusUpdate(ProgressParam params)
   //----------------------------------------------------------------
   {
      if (! isDrawerOpen)
         return;
      if (params.bearing >= 0)
      {
         bearingText.setText(String.format("%.4f", params.bearing));
         if (params.targetBearing >= 0)
         {
            Spannable destSpan = new SpannableString(String.format("%.4f", params.targetBearing));
            final ForegroundColorSpan spanColor;
            if ((params.color != null) && (params.color[0] != Float.MIN_VALUE))
               spanColor = new ForegroundColorSpan(
                     Color.argb(255, (int) (params.color[0] * 255.0), (int) (params.color[1] * 255.0),
                                (int) (params.color[2] * 255.0)));
            else
               spanColor = new ForegroundColorSpan(Color.GREEN);
            destSpan.setSpan(spanColor, 0, destSpan.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            bearingDestText.setText(destSpan);
         } else
            bearingDestText.setText("");
      }
      if (params.status != null)
      {
         statusText.setText(params.status);
         if (params.isToast)
         {
            if (lastStatusToast != null)
               lastStatusToast.cancel();
            lastStatusToast = Toast.makeText(RecorderActivity.this, params.status, params.toastDuration);
            lastStatusToast.show();
         }
      }
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

      private ResolutionAdapter(Context context, int resource, String[] resolutions) { super(context, resource, resolutions); }

      public int[] getWidthHeight(int position) { return getWidthHeight(getItem(position)); }

      static public int[] getWidthHeight(String previewResolution)
      //----------------------------------------------------------
      {
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
