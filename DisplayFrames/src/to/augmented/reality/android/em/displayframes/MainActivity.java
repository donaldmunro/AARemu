package to.augmented.reality.android.em.displayframes;

import android.app.ActionBar;
import android.app.Activity;
import android.app.FragmentTransaction;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import com.doubleTwist.drawerlib.ADrawerLayout;
import to.augmented.reality.android.em.ARCamera;
import to.augmented.reality.android.em.BearingListener;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Locale;

public class MainActivity extends Activity implements OpenDialog.DialogCloseable
//==============================================================================
{
   final static String TAG = MainActivity.class.getSimpleName();
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
         if (!dir.mkdirs())
         {
            dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                           "ARRecorder");
            dir.mkdirs();
         }
         try { DIR = dir.getCanonicalFile(); } catch (IOException e) { DIR = dir.getAbsoluteFile(); }
      }
   }

   private File headerFile = null;
   private File framesFile = null;
   private int width, height;
   private File recordingDir;
   private String recordingName, format;
   boolean isDrawerOpen = false, isOpeningDrawer = false;

   private View decorView = null;
   private ADrawerLayout drawerLayout;
   private ARSurfaceView previewSurface;

   private TextView bearingText;
   private EditText setBearingEdit;
   private Button buttonReview;

   private float increment = -1;
   private int bufferSize = -1;
   private boolean isPreviewing = false, isReviewing = false;
   int uiOptions;
   final Handler hideHandler = new Handler();
   private int displayFlags = 0;
   final private Runnable hideRunner = new Runnable()
         //==================================
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

   private class UpdateBearingRunner implements Runnable
   //============================================
   {
      volatile public float bearing;
      @Override public void run() { bearingText.setText(String.format("%.4f", bearing)); }
   };
   final private UpdateBearingRunner updateBearingRunner = new UpdateBearingRunner();

   @Override
   protected void onCreate(Bundle savedInstanceState)
   //------------------------------------------------
   {
      super.onCreate(savedInstanceState);
      requestWindowFeature(Window.FEATURE_NO_TITLE);
      decorView = getWindow().getDecorView();
      getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

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
      bearingText = (TextView) findViewById(R.id.bearing_text);
      setBearingEdit = (EditText) findViewById(R.id.set_bearing_text);
      buttonReview = (Button) findViewById(R.id.button_review);
   }

   public void onOpen(View view)
   //---------------------------
   {
      FragmentTransaction ft = getFragmentManager().beginTransaction();
      if (! DIR.exists())
      {
         Toast.makeText(this, DIR + " does not exist", Toast.LENGTH_LONG).show();
         return;
      }
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
            Toast.makeText(MainActivity.this, headerFile.getAbsolutePath() + " not found or not readable",
                           Toast.LENGTH_LONG).show();
            return;
         }
         framesFile = new File(dir, basename + ".frames");
         if ( (framesFile == null) || (! framesFile.exists()) || (! framesFile.canRead()) )
         {
            Toast.makeText(MainActivity.this, framesFile.getAbsolutePath() + " not found or not readable", Toast.LENGTH_LONG).show();
            return;
         }
         recordingDir = dir;
         recordingName = basename;
         initCamera();
      }
   }

   private boolean initCamera()
   //-------------------------
   {
      previewSurface.setPreviewFiles(headerFile, framesFile);
      ARCamera camera = previewSurface.getCamera();
      Camera.Parameters parameters = camera.getParameters();
      Camera.Size sz = parameters.getPreviewSize();
      width = sz.width;
      height = sz.height;
      if ( (width < 0) || (height < 0) )
      {
         Toast.makeText(MainActivity.this, headerFile.getAbsolutePath() + " does not contain preview width or height", Toast.LENGTH_LONG).show();
         return false;
      }
      increment = camera.getHeaderFloat("Increment", -1);
      if (increment < 0)
      {
         Toast.makeText(MainActivity.this, headerFile.getAbsolutePath() + " does not contain frame increment", Toast.LENGTH_LONG).show();
         return false;
      }
      bufferSize = camera.getHeaderInt("BufferSize", -1);
      format = camera.getHeader("FileFormat");
      return true;
   }

   public void onReview(View view)
   //-----------------------------
   {
      if (buttonReview.getText().toString().equals("Review"))
      {
         if (previewSurface != null)
         {
            if (! previewSurface.isPreviewing())
               startPreview();
            try { Thread.sleep(100); } catch (Exception _e) { }
            buttonReview.setText("Stop");
            previewSurface.review(0, 360, 15, false, new ARCamera.Reviewable()
            {
               @Override public void onReviewStart() { }
               @Override public void onReview(float bearing) { }
               @Override public void onReviewed(float bearing) { }
               @Override public void onReviewComplete()
               {
                  MainActivity.this.runOnUiThread(new Runnable()
                  {
                     @Override public void run() { buttonReview.setText("Review"); isReviewing = false;}
                  });
               }
            });
            buttonReview.setText("Stop");
            isReviewing = true;
         }
      }
      else
      {
         previewSurface.stopReview();
         previewSurface.stopPreview();
         isReviewing = false;
      }
   }

   public void onDisplay(View view)
   //------------------------------
   {
      if ( (framesFile == null) || (! framesFile.exists()) )
      {
         Toast.makeText(this, "Select a recording first", Toast.LENGTH_LONG).show();
         return;
      }
      float bearing = getSetBearing();
      if (bearing >= 0)
      {
         if (isReviewing)
            onReview(null);
         if (isPreviewing)
            stopPreview();
         StringBuilder errbuf = new StringBuilder();
         if (! previewSurface.display(bearing, increment, errbuf))
            Toast.makeText(this, errbuf.toString(), Toast.LENGTH_LONG).show();
      }
   }

   public void decrementBearing(View view)
   //----------------------------------
   {
      if ( (framesFile == null) || (! framesFile.exists()) )
      {
         Toast.makeText(this, "Select a recording first", Toast.LENGTH_LONG).show();
         return;
      }
      float bearing = getSetBearing();
      bearing -= increment;
      if (bearing < 0)
         bearing += 360;
      setBearingEdit.setText(String.format(Locale.US, "%.1f", bearing));
      StringBuilder errbuf = new StringBuilder();
      if (! previewSurface.display(bearing, increment, errbuf))
         Toast.makeText(this, errbuf.toString(), Toast.LENGTH_LONG).show();
   }

   public void incrementBearing(View view)
   //-------------------------------------
   {
      if ( (framesFile == null) || (! framesFile.exists()) )
      {
         Toast.makeText(this, "Select a recording first", Toast.LENGTH_LONG).show();
         return;
      }
      float bearing = getSetBearing();
      if (bearing < 0)
         bearing = 0;
      else
         bearing += increment;
      if (bearing > 360)
         bearing = 0;
      setBearingEdit.setText(String.format(Locale.US, "%.1f", bearing));
      StringBuilder errbuf = new StringBuilder();
      if (! previewSurface.display(bearing, increment, errbuf))
         Toast.makeText(this, errbuf.toString(), Toast.LENGTH_LONG).show();
   }

   private float getSetBearing()
   //---------------------------
   {

      String s = setBearingEdit.getText().toString();
      if ( (s == null) || (s.trim().length() == 0) )
         return -1;
      float bearing = -1;
      try { bearing = Float.parseFloat(s); } catch (NumberFormatException e) { return -1; }
      return bearing;
   }

   private void startPreview()
   //-------------------------
   {
      if ( (headerFile == null) || (! headerFile.exists()) || (! headerFile.canRead()) )
      {
         Toast.makeText(MainActivity.this, "Load a valid recording first.", Toast.LENGTH_LONG).show();
         return;
      }
      if ( (framesFile == null) || (! framesFile.exists()) || (! framesFile.canRead()) )
      {
         Toast.makeText(MainActivity.this, "Load a valid recording first.", Toast.LENGTH_LONG).show();
         return;
      }
      previewSurface.setPreviewFiles(headerFile, framesFile);
      ARCamera camera = previewSurface.getCamera();
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
      previewSurface.startPreview();
      isPreviewing = true;
   }

   private void stopPreview()
   //------------------------
   {
      previewSurface.stopPreview();
      isPreviewing = false;
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

   boolean hasShownResToast = false;

   public void takePhoto(View view)
   //------------------------------
   {
      if (! format.equals("RGBA"))
      {
         Toast.makeText(this, "Currently only supported for RGBA", Toast.LENGTH_LONG).show();
         return;
      }
      float bearing = getSetBearing();
      if (bearing == -1)
      {
         Toast.makeText(this, "Could not obtain current bearing", Toast.LENGTH_LONG).show();
         return;
      }
      if (! hasShownResToast)
      {
         Toast.makeText(this,
                        "Remember to set the camera to use " + width + "x" + height + " resolution. You may need to " +
                              "install a camera application which allows setting resolution eg Open Camera " +
                              "(https://play.google.com/store/apps/details?id=net.sourceforge.opencamera&hl=en)",
                        Toast.LENGTH_LONG).show();
         hasShownResToast = true;
      }
      ARCamera camera = previewSurface.getCamera();
      camera.release();
      Intent cameraIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
      Uri fileUri =  Uri.fromFile(new File(recordingDir, String.format("%.1f.photo", bearing)));
      cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, fileUri);
      cameraIntent.putExtra("bearing", bearing);
      startActivityForResult(cameraIntent, 1);
   }

   public void onActivityResult(int requestCode, int resultCode, Intent intent)
   //------------------------------------------------------------------------
   {
      if ( (resultCode == RESULT_OK) && (requestCode == 1) )
      {
         float bearing;
         try { bearing = intent.getExtras().getFloat("bearing", -1); } catch (Exception _e) { bearing = -1; }
         if (bearing < 0)
         {
            bearing = getSetBearing();
            if (bearing < 0)
            {
               Toast.makeText(this, "Could not obtain current bearing", Toast.LENGTH_LONG).show();
               return;
            }
         }
         ARCamera camera = previewSurface.getCamera();
         camera.reconnect();
         Bitmap photo = null;
         Uri uri;
         if (intent != null)
         {
            uri = intent.getData();
            if ( (uri == null) && (intent.getExtras() != null) )
               photo = (Bitmap) intent.getExtras().get("data");
         }
         else
            uri = Uri.fromFile(new File(recordingDir, String.format("%.1f.photo", bearing)));
         if (uri != null)
         {
            ParcelFileDescriptor parcelFileDescriptor = null;
            try
            {
               parcelFileDescriptor = this.getContentResolver().openFileDescriptor(uri, "r");
               if (parcelFileDescriptor != null)
               {
                  FileDescriptor fileDescriptor = parcelFileDescriptor.getFileDescriptor();
                  photo = BitmapFactory.decodeFileDescriptor(fileDescriptor);
               }
            }
            catch (Exception e)
            {
               Log.e(TAG, "", e);
               Toast.makeText(this, "Exception processing image " + uri + " " + e.getMessage(),
                              Toast.LENGTH_LONG).show();
            }
            finally
            {
               if (parcelFileDescriptor != null)
                  try { parcelFileDescriptor.close(); } catch (Exception _e) {}
               if (uri != null)
                  try { new File(uri.toString()).delete(); } catch (Exception _e) {}
            }
         }

         if (photo == null)
         {
            Toast.makeText(this, "Error obtaining image from camera", Toast.LENGTH_LONG).show();
            setBearingEdit.setText(Float.toString(bearing));
            return;
         }
         if ( (photo.getWidth() != width) || (photo.getHeight() != height) )
         {
            Toast.makeText(this, "Please set the camera to use " + width + "x" + height + " resolution",
                           Toast.LENGTH_LONG).show();
            setBearingEdit.setText(Float.toString(bearing));
            return;
         }
         ByteBuffer rgba = ByteBuffer.allocate(bufferSize);
//         boolean isRGB = format.equals("RGB");
         rgba.rewind();
         for (int i=0; i<height; i++)
         {
            for (int j=0; j<width; j++)
            {
               int pixel = photo.getPixel(j, i);
               rgba.put((byte) Color.red(pixel));
               rgba.put((byte) Color.green(pixel));
               rgba.put((byte) Color.blue(pixel));
               rgba.put((byte) Color.alpha(pixel));
            }
         }
         RandomAccessFile framesRAF = null;
         long offset = (long) (Math.floor(bearing / increment));
         try
         {
            framesRAF = new RandomAccessFile(framesFile, "rws");
            framesRAF.seek(offset * bufferSize);
            FileChannel channel = framesRAF.getChannel();
            rgba.rewind();
            channel.write(rgba);
         }
         catch (Exception e)
         {
            Log.e(TAG, "Error writing updated frame at " + bearing + " (" + offset + ")", e);
         }
         finally
         {
            if (framesRAF != null)
               try { framesRAF.close(); } catch (Exception _e) {}
         }
         bearing = bearing - increment;
         if (bearing < 0)
            bearing += 360;
         setBearingEdit.setText(Float.toString(bearing));
      }
   }
}
