package to.augmented.reality.android.em.recorder;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import static org.junit.Assert.*;

//@Config(constants = BuildConfig.class, sdk = 22, manifest = "/ssd/Android/ARem2/ARemRecorder/AndroidManifest.xml")
//@RunWith(RobolectricGradleTestRunner.class)
public class TraverseRecordingThreadTest
{
//   static final File DIR = new File("/sdcard/Documents/ARRecorder/traverse");
   static final File DIR = new File("/sdcard/Documents/ARRecorder/t");
   static final String name = DIR.getName();


   @org.junit.Test
   public void testKludge() throws Exception
   {
      GLRecorderRenderer renderer = new GLRecorderRenderer();
      renderer.recordDir = DIR;
      String name = DIR.getName();
      File debugDir = new File(renderer.recordDir, "debug");
      assertTrue(debugDir.isDirectory());
      assertTrue(new File(debugDir, "complete.ser").exists());
      File headFile = new File(DIR, name + ".head");
      BufferedReader br = new BufferedReader(new FileReader(headFile));
      float startBearing = 0;
      int previewWidth = 640, previewHeight = 480;
      String line;
      while ( (line = br.readLine()) != null)
      {
         String[] as = line.split("=");
         if (as.length < 2)
            continue;
         String v = as[1].trim();
         if (as[0].trim().equals("StartBearing"))
            startBearing = Float.parseFloat(v);
         else if (as[0].trim().equals("PreviewWidth"))
            previewWidth = Integer.parseInt(v);
         if (as[0].trim().equals("PreviewHeight"))
            previewHeight = Integer.parseInt(v);
      }
      renderer.recordFileName = name;
      renderer.recordFramesFile = new File(DIR, name + ".frames.part");
      TraverseRecordingThread instance = new TraverseRecordingThread(renderer, null);
      assertTrue(instance.DESKTOP_UNIT_TEST);
      assertTrue(instance.DESKTOP_SERIALIZE);
      instance.recordingIncrement = 1.0f;
      instance.no = (int) (Math.floor(360.0 / instance.recordingIncrement));
      renderer.previewWidth = previewWidth; renderer.previewHeight = previewHeight;
//      renderer.nv21BufferSize = 460800;
//      renderer.nv21BufferSize = previewWidth * previewHeight * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
      renderer.nv21BufferSize = previewWidth * previewHeight * 12 / 8;
      renderer.rgbaBufferSize = previewWidth * previewHeight * 4;
      instance.serialize();
      instance.kludge(startBearing, null);
   }
}
