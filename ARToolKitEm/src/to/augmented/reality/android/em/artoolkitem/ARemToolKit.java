package to.augmented.reality.android.em.artoolkitem;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.Matrix;
import android.util.Log;

public class ARemToolKit
//=======================
{
   final static private String TAG = ARemToolKit.class.getSimpleName();
   private static boolean loadedNative = false;
   private static boolean initedNative = false;

   private static final class SingletonHolder
   {
      static final ARemToolKit singleton = new ARemToolKit();
   }

   static public ARemToolKit getInstance() { return SingletonHolder.singleton; }

   static
   {
      loadedNative = NativeInterfaceEmu.loadNativeLibrary();
      if (!loadedNative)
      {
         Log.e("ARToolKit", "Loading native library failed!");
      }
      else
      {
         Log.i("ARToolKit", "Loaded native library.");
      }

   }

   private int frameWidth;
   private int frameHeight;
   private int cameraIndex;
   private boolean cameraIsFrontFacing;
   private byte[] debugImageData;
   private int[] debugImageColors;
   private Bitmap debugBitmap = null;

   public boolean initialiseNative(String resourcesDirectoryPath)
   //------------------------------------------------------------
   {
      if (!loadedNative)
         return false;
      else if (! NativeInterfaceEmu.arwInitialiseAR())
      {
         Log.e("ARToolKit", "initialiseNative(): Error initialising native library!");
         return false;
      }
      else
      {
         Log.i("ARToolKit", "initialiseNative(): ARToolKit version: " + NativeInterfaceEmu.arwGetARToolKitVersion());
         if (!NativeInterfaceEmu.arwChangeToResourcesDir(resourcesDirectoryPath))
         {
            Log.i("ARToolKit",
                  "initialiseNative(): Error while attempting to change working directory to resources directory.");
         }

         initedNative = true;
         return true;
      }
   }

   public boolean nativeInitialised() { return initedNative; }

   public boolean initialiseARCam(int videoWidth, int videoHeight, String cameraParaPath, int cameraIndex,
                                 boolean cameraIsFrontFacing)
   //-------------------------------------------------------------------------------------------------
   {
      return initialiseAR(videoWidth, videoHeight, cameraParaPath, cameraIndex, cameraIsFrontFacing,
                          "-format=NV21");
   }

   public boolean initialiseARem(int videoWidth, int videoHeight, String cameraParaPath, int cameraIndex,
                               boolean cameraIsFrontFacing)
   //-------------------------------------------------------------------------------------------------
   {
      return initialiseAR(videoWidth, videoHeight, cameraParaPath, cameraIndex, cameraIsFrontFacing,
                          "-device=arem -format=RGBA");
   }

   public boolean initialiseAR(int videoWidth, int videoHeight, String cameraParaPath, int cameraIndex,
                               boolean cameraIsFrontFacing, String cameraParams)
   //-------------------------------------------------------------------------------------------------
   {
      if (! initedNative)
      {
         Log.e("ARToolKit", "initialiseAR(): Cannot initialise camera because native interface not inited.");
         return false;
      }
      else
      {
         this.frameWidth = videoWidth;
         this.frameHeight = videoHeight;
         this.cameraIndex = cameraIndex;
         this.cameraIsFrontFacing = cameraIsFrontFacing;
         if (!NativeInterfaceEmu.arwStartRunning(cameraParams, cameraParaPath, 10.0F, 10000.0F))
         {
            Log.e("ARToolKit", "initialiseAR(): Error starting video");
            return false;
         }
         else
         {
            this.debugImageData = new byte[this.frameWidth * this.frameHeight * 4];
            this.debugImageColors = new int[this.frameWidth * this.frameHeight];
            this.debugBitmap = Bitmap.createBitmap(this.frameWidth, this.frameHeight, Bitmap.Config.ARGB_8888);
            return true;
         }
      }
   }

   public Bitmap updateDebugBitmap()
   //------------------------------
   {
      if (!initedNative)
         return null;
      else if (! NativeInterfaceEmu.arwUpdateDebugTexture32(this.debugImageData))
         return null;
      else
      {
         int w = this.debugBitmap.getWidth();
         int h = this.debugBitmap.getHeight();

         for (int y = 0; y < h; ++y)
         {
            for (int x = 0; x < w; ++x)
            {
               int idx1 = (y * w + x) * 4;
               int idx2 = y * w + x;
               this.debugImageColors[idx2] = Color.argb(255, this.debugImageData[idx1], this.debugImageData[idx1 + 1],
                                                        this.debugImageData[idx1 + 2]);
            }
         }

         this.debugBitmap.setPixels(this.debugImageColors, 0, w, 0, 0, w, h);
         return this.debugBitmap;
      }
   }

   public Bitmap getDebugBitmap() { return this.debugBitmap; }

   public boolean getDebugMode() { return !initedNative ? false : NativeInterfaceEmu.arwGetVideoDebugMode(); }

   public void setDebugMode(boolean debug)
   //------------------------------------
   {
      if (initedNative)
         NativeInterfaceEmu.arwSetVideoDebugMode(debug);
   }

   public int getThreshold() { return !initedNative ? -1 : NativeInterfaceEmu.arwGetVideoThreshold(); }

   public void setThreshold(int threshold)
   //-------------------------------------
   {
      if (initedNative)
         NativeInterfaceEmu.arwSetVideoThreshold(threshold);
   }

   public float[] getProjectionMatrix() { return !initedNative ? null : NativeInterfaceEmu.arwGetProjectionMatrix(); }

   public int addMarker(String cfg) { return !initedNative ? -1 : NativeInterfaceEmu.arwAddMarker(cfg); }

   public boolean queryMarkerVisible(int markerUID) { return !initedNative ? false : NativeInterfaceEmu.arwQueryMarkerVisibility(markerUID); }

   public float[] queryMarkerTransformation(int markerUID) { return !initedNative ? null : NativeInterfaceEmu.arwQueryMarkerTransformation(markerUID); }

   public boolean isRunning() { return !initedNative ? false : NativeInterfaceEmu.arwIsRunning(); }

   public boolean convertAndDetect(byte[] frame)
   //------------------------------------------
   {
      if ( (! initedNative) || (frame == null) )
         return false;
      if (! NativeInterfaceEmu.arwAcceptVideoImage(frame, this.frameWidth, this.frameHeight, this.cameraIndex,
                                                   this.cameraIsFrontFacing))
         return false;
      return NativeInterfaceEmu.arwCaptureUpdate();
//      if (! NativeInterfaceEmu.arwCapture())
//         return false;
//      return NativeInterfaceEmu.arwUpdateAR();
   }

   public void cleanup()
   //-------------------
   {
      if (initedNative)
      {
         NativeInterfaceEmu.arwStopRunning();
         NativeInterfaceEmu.arwShutdownAR();
         this.debugBitmap.recycle();
         this.debugBitmap = null;
         initedNative = false;
      }
   }

   public float getBorderSize() { return NativeInterfaceEmu.arwGetBorderSize(); }

   public void setBorderSize(float size) { NativeInterfaceEmu.arwSetBorderSize(size); }

   public float[] calculateReferenceMatrix(int idMarkerBase, int idMarker2)
   //----------------------------------------------------------------------
   {
      float[] referenceMarkerTranslationMatrix = this.queryMarkerTransformation(idMarkerBase);
      float[] secondMarkerTranslationMatrix = this.queryMarkerTransformation(idMarker2);
      if (referenceMarkerTranslationMatrix != null && secondMarkerTranslationMatrix != null)
      {
         float[] invertedMatrixOfReferenceMarker = new float[16];
         Matrix.invertM(invertedMatrixOfReferenceMarker, 0, referenceMarkerTranslationMatrix, 0);
         float[] transformationFromMarker1ToMarker2 = new float[16];
         Matrix.multiplyMM(transformationFromMarker1ToMarker2, 0, invertedMatrixOfReferenceMarker, 0,
                           secondMarkerTranslationMatrix, 0);
         return transformationFromMarker1ToMarker2;
      }
      else
      {
         Log.e("ARToolKit", "calculateReferenceMatrix(): Currently there are no two markers visible at the same time");
         return null;
      }
   }

   public float distance(int referenceMarker, int markerId2)
   //-------------------------------------------------------
   {
      float[] referenceMatrix = this.calculateReferenceMatrix(referenceMarker, markerId2);
      if (referenceMatrix != null)
      {
         float distanceX = referenceMatrix[12];
         float distanceY = referenceMatrix[13];
         float distanceZ = referenceMatrix[14];
         Log.d("ARToolKit", "distance(): Marker distance: x: " + distanceX + " y: " + distanceY + " z: " + distanceZ);
         float length = Matrix.length(distanceX, distanceY, distanceZ);
         Log.d("ARToolKit", "distance(): Absolute distance: " + length);
         return length;
      }
      else
      {
         return 0.0F;
      }
   }

   public float[] retrievePosition(int referenceMarkerId, int markerIdToGetThePositionFor)
   //-------------------------------------------------------------------------------------
   {
      float[] initialVector = new float[] {1.0F, 1.0F, 1.0F, 1.0F};
      float[] positionVector = new float[4];
      float[] transformationMatrix = this.calculateReferenceMatrix(referenceMarkerId, markerIdToGetThePositionFor);
      if (transformationMatrix != null)
      {
         Matrix.multiplyMV(positionVector, 0, transformationMatrix, 0, initialVector, 0);
         return positionVector;
      }
      else
         return null;
   }
}
