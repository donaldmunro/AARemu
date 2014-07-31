package to.augmented.reality.android.em.displayframes;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ConfigurationInfo;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.util.AttributeSet;
import to.augmented.reality.android.em.ARCamera;

import java.io.File;

public class ARSurfaceView extends GLSurfaceView
//==============================================
{
   private static final String TAG = ARSurfaceView.class.getSimpleName();

   private GLRenderer renderer = null;
   Activity activity = null;

   boolean isES2 = false, isES3 = false;

   public ARSurfaceView(final Context activity) { this(activity, null, 0); }

   public ARSurfaceView(final Context activity, AttributeSet attrs) { this(activity, attrs, 0); }

   public ARSurfaceView(final Context activity, AttributeSet attrs, int defStyle)
   //-----------------------------------------------------------------------
   {
      super(activity, attrs);
      if (! isInEditMode())
      {
         this.activity = (Activity) activity;
         final ActivityManager activityManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
         final ConfigurationInfo configurationInfo = activityManager.getDeviceConfigurationInfo();
         isES2 = configurationInfo.reqGlEsVersion >= 0x20000
               || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1
               && (Build.FINGERPRINT.startsWith("generic")
               || Build.FINGERPRINT.startsWith("unknown")
               || Build.MODEL.contains("google_sdk")
               || Build.MODEL.contains("Emulator")
               || Build.MODEL.contains("Android SDK built for x86")));
         isES3 = configurationInfo.reqGlEsVersion >= 0x30000
               || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2
               && (Build.FINGERPRINT.startsWith("generic")
               || Build.FINGERPRINT.startsWith("unknown")
               || Build.MODEL.contains("google_sdk")
               || Build.MODEL.contains("Emulator")
               || Build.MODEL.contains("Android SDK built for x86")));
         isES3 = false;
         if (isES3)
         {
            setEGLContextClientVersion(3);
            isES2 = false;
         } else if (isES2)
            setEGLContextClientVersion(2);
         else
            throw new RuntimeException("Outdated hardware. OpenGL ES2 or 3 is required");

         setEGLConfigChooser(8, 8, 8, 8, 16, 0);
         setDebugFlags(GLSurfaceView.DEBUG_CHECK_GL_ERROR | GLSurfaceView.DEBUG_LOG_GL_CALLS);
         renderer = new GLRenderer(this.activity, this);
         setRenderer(renderer);
         setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
      }
   }

   public void setPreviewFiles(File headerFile, File framesFile) { renderer.setPreviewFiles(headerFile, framesFile); }

   public ARCamera getCamera() { return renderer.getCamera(); }

   @Override
   public void onPause()
   //--------------------
   {
      super.onPause();
      if (renderer != null)
         renderer.pause();
   }

   @Override
   public void onResume()
   //-------------------
   {
      super.onResume();
      if (renderer != null)
         renderer.resume();
   }


   public int getPreviewWidth() { return renderer.getPreviewWidth(); }

   public int getPreviewHeight() { return renderer.getPreviewHeight(); }

   public Renderer getRenderer() { return renderer; }

   public void startPreview()
   //------------------------------------------
   {
      if (renderer != null)
         renderer.startPreview();
   }

   public boolean isPreviewing() { return (renderer == null) ? false : renderer.isPreviewing; }

   public void stopPreview()
   //------------------------------------------
   {
      if (renderer != null)
         renderer.stopPreview();
   }

   public void review(int pauseMs, boolean isRepeat) { review(0, 360, pauseMs, isRepeat, null);}

   public void review(float startBearing, float endBearing, int pauseMs, boolean isRepeat, ARCamera.Reviewable reviewable)
   //-------------------------------------------------------------------------------------
   {
      if (renderer == null) return;
         renderer.review(startBearing, endBearing, pauseMs, isRepeat, reviewable);
   }

   public void stopReview()
   {
      if (renderer != null)
         renderer.stopReview();
   }

   public boolean display(float bearing, float increment, StringBuilder errbuf)
   {
      if (renderer != null)
         return renderer.display(bearing, increment, errbuf);
      else
         return false;
   }
}
