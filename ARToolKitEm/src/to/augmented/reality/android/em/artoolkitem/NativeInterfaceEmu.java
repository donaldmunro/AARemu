package to.augmented.reality.android.em.artoolkitem;

import android.util.Log;

public class NativeInterfaceEmu
//==============================
{
   private static final String TAG = NativeInterfaceEmu.class.getSimpleName();
   private static final String LIBRARY_NAME = "ARemuWrapper";
   private static final String LIBS[] = { "c++_shared", "ARWrapper", LIBRARY_NAME };

   public static final int ARW_MARKER_OPTION_FILTERED = 1;
   public static final int ARW_MARKER_OPTION_FILTER_SAMPLE_RATE = 2;
   public static final int ARW_MARKER_OPTION_FILTER_CUTOFF_FREQ = 3;
   public static final int ARW_MARKER_OPTION_SQUARE_USE_CONT_POSE_ESTIMATION = 4;
   public static final int ARW_MARKER_OPTION_SQUARE_CONFIDENCE = 5;
   public static final int ARW_MARKER_OPTION_SQUARE_CONFIDENCE_CUTOFF = 6;
   public static final int AR_LABELING_WHITE_REGION = 0;
   public static final int AR_LABELING_BLACK_REGION = 1;
   public static final int AR_TEMPLATE_MATCHING_COLOR = 0;
   public static final int AR_TEMPLATE_MATCHING_MONO = 1;
   public static final int AR_MATRIX_CODE_DETECTION = 2;
   public static final int AR_TEMPLATE_MATCHING_COLOR_AND_MATRIX = 3;
   public static final int AR_TEMPLATE_MATCHING_MONO_AND_MATRIX = 4;
   public static final int AR_MATRIX_CODE_3x3 = 3;
   public static final int AR_MATRIX_CODE_3x3_PARITY65 = 259;
   public static final int AR_MATRIX_CODE_3x3_HAMMING63 = 515;
   public static final int AR_MATRIX_CODE_4x4 = 4;
   public static final int AR_MATRIX_CODE_4x4_BCH_13_9_3 = 772;
   public static final int AR_MATRIX_CODE_4x4_BCH_13_5_5 = 1028;
   public static final int AR_MATRIX_CODE_5x5_BCH_22_12_5 = 1029;
   public static final int AR_MATRIX_CODE_5x5_BCH_22_7_7 = 1285;
   public static final int AR_MATRIX_CODE_5x5 = 5;
   public static final int AR_MATRIX_CODE_6x6 = 6;
   public static final int AR_MATRIX_CODE_GLOBAL_ID = 2830;
   public static final int AR_IMAGE_PROC_FRAME_IMAGE = 0;
   public static final int AR_IMAGE_PROC_FIELD_IMAGE = 1;

   public NativeInterfaceEmu() { }

   public static boolean loadNativeLibrary()
   //---------------------------------------
   {
      String lib = null;
      try
      {
         for (int i=0; i<LIBS.length; i++)
         {
            lib = LIBS[i];
            System.loadLibrary(lib);
         }
         return true;
      }
      catch (Exception e)
      {
         Log.e(TAG, "Exception loading native library " + lib, e);
         return false;
      }
   }

   public static native String arwGetARToolKitVersion();

   public static native boolean arwInitialiseAR();

   public static native boolean arwInitialiseARWithOptions(int pattSize, int pattCountMax);

   public static native boolean arwChangeToResourcesDir(String resourcesDirectoryPath);

   public static native boolean arwStartRunning(String vconf, String cparaName, float nearPlane, float farPlane);

   public static native boolean arwStartRunningStereo(String vconfL, String cparaNameL, String vconfR, String cparaNameR, String transL2RName, float nearPlane, float farPlane);

   public static native boolean arwIsRunning();

   public static native boolean arwStopRunning();

   public static native boolean arwShutdownAR();

   public static native float[] arwGetProjectionMatrix();

   public static native boolean arwGetProjectionMatrixStereo(float[] projL, float[] projR);

   public static native boolean arwGetVideoParams(int[] width, int[] height, int[] pixelSize, String[] pixelFormatStringBuffer);

   public static native boolean arwGetVideoParamsStereo(int[] widthL, int[] heightL, int[] pixelSizeL, String[] pixelFormatStringL, int[] widthR, int[] heightR, int[] pixelSizeR, String[] pixelFormatString);

   public static native boolean arwCapture();

   public static native boolean arwUpdateAR();

   public static native boolean arwCaptureUpdate();

   public static native int arwAddMarker(String cfg);

   public static native boolean arwRemoveMarker(int markerUID);

   public static native int arwRemoveAllMarkers();

   public static native boolean arwQueryMarkerVisibility(int markerUID);

   public static native float[] arwQueryMarkerTransformation(int markerUID);

   public static native boolean arwQueryMarkerTransformationStereo(int markerUID, float[] matrixL, float[] matrixR);

   public static native void arwSetMarkerOptionBool(int markerUID, int option, boolean value);

   public static native void arwSetMarkerOptionInt(int markerUID, int option, int value);

   public static native void arwSetMarkerOptionFloat(int markerUID, int option, float value);

   public static native boolean arwGetMarkerOptionBool(int markerUID, int option);

   public static native int arwGetMarkerOptionInt(int markerUID, int option);

   public static native float arwGetMarkerOptionFloat(int markerUID, int option);

   public static native void arwSetVideoDebugMode(boolean debug);

   public static native boolean arwGetVideoDebugMode();

   public static native void arwSetVideoThreshold(int threshold);

   public static native int arwGetVideoThreshold();

   public static native void arwSetVideoThresholdMode(int mode);

   public static native int arwGetVideoThresholdMode();

   public static native boolean arwAcceptVideoImage(byte[] image, int width, int height, int cameraIndex, boolean cameraIsFrontFacing);

   public static native boolean arwAcceptVideoImageStereo(byte[] imageL, int widthL, int heightL, int cameraIndexL, boolean cameraIsFrontFacingL, byte[] imageR, int widthR, int heightR, int cameraIndexR, boolean cameraIsFrontFacingR);

   public static native boolean arwUpdateDebugTexture32(byte[] image);

   public static native void arwSetLabelingMode(int mode);

   public static native int arwGetLabelingMode();

   public static native void arwSetPatternDetectionMode(int mode);

   public static native int arwGetPatternDetectionMode();

   public static native void arwSetBorderSize(float size);

   public static native float arwGetBorderSize();

   public static native void arwSetMatrixCodeType(int type);

   public static native int arwGetMatrixCodeType();

   public static native void arwSetImageProcMode(int mode);

   public static native int arwGetImageProcMode();
}
