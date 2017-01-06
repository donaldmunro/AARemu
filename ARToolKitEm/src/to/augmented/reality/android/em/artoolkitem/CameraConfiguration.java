package to.augmented.reality.android.em.artoolkitem;


import android.opengl.GLSurfaceView;

public class CameraConfiguration
//============================
{
   public int cameraNo = 0;

   public String recordingDirectory = null;

   public boolean isRepeat = false;

   public int fps = 30;

   public int renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY;
}
