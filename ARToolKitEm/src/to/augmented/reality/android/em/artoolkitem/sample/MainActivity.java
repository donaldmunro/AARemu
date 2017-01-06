package to.augmented.reality.android.em.artoolkitem.sample;

import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.widget.FrameLayout;
import org.artoolkit.ar.base.rendering.ARRenderer;
import to.augmented.reality.android.em.artoolkitem.ARemuActivity;
import to.augmented.reality.android.em.artoolkitem.CameraConfiguration;
import to.augmented.reality.android.em.artoolkitem.R;

public class MainActivity extends ARemuActivity
//=============================================
{
   @Override
   public void onCreate(Bundle savedInstanceState)
   //---------------------------------------------
   {
      super.onCreate(savedInstanceState);
      setContentView(R.layout.main);
   }

   @Override
   protected ARRenderer supplyRenderer() { return new SimpleGLES20Renderer(); }

   @Override
   protected FrameLayout supplyFrameLayout() { return (FrameLayout) this.findViewById(R.id.mainLayout); }

   protected CameraConfiguration supplyConfiguration() { return new SampleCameraConfigurion(); }

   static class SampleCameraConfigurion extends CameraConfiguration
   //============================================================
   {
      public SampleCameraConfigurion()
      //-----------------------------
      {
         cameraNo = 0;
//         recordingDirectory = "/sdcard/Documents/ARRecorder/free";
         recordingDirectory = "/sdcard/Documents/ARRecorder/hiro";
         isRepeat = true;
         renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY;
      }
   }
}
