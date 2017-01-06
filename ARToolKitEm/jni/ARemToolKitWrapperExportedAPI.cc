//#include <AR/param.h>
//#include <AR/ar.h>
#include "ARWrapper/ARMarker.h"

#ifdef ANDROID_LOG
#include <android/log.h>
#define  LOG_TAG    "ARemuToolkitWrapper"
#ifndef LOGI
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#endif
#ifndef LOGD
#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#endif
#ifndef LOGE
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)
#endif
#endif

#include "ARemController.h"
#include "to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu.h"

static ARemController *gARTK = nullptr;

void log(const char *msg) { LOGI("%s", msg); }

enum {
   ARW_MARKER_OPTION_FILTERED = 1,                         ///< bool, true for filtering enabled.
   ARW_MARKER_OPTION_FILTER_SAMPLE_RATE = 2,               ///< float, sample rate for filter calculations.
   ARW_MARKER_OPTION_FILTER_CUTOFF_FREQ = 3,               ///< float, cutoff frequency of filter.
   ARW_MARKER_OPTION_SQUARE_USE_CONT_POSE_ESTIMATION = 4,  ///< bool, true to use continuous pose estimate.
   ARW_MARKER_OPTION_SQUARE_CONFIDENCE = 5,                ///< float, confidence value of most recent marker match
   ARW_MARKER_OPTION_SQUARE_CONFIDENCE_CUTOFF = 6,         ///< float, minimum allowable confidence value used in marker matching.
   ARW_MARKER_OPTION_NFT_SCALE = 7,                        ///< float, scale factor applied to NFT marker size.
   ARW_MARKER_OPTION_MULTI_MIN_SUBMARKERS = 8,             ///< int, minimum number of submarkers for tracking to be valid.
   ARW_MARKER_OPTION_MULTI_MIN_CONF_MATRIX = 9,            ///< float, minimum confidence value for submarker matrix tracking to be valid.
   ARW_MARKER_OPTION_MULTI_MIN_CONF_PATTERN = 10,          ///< float, minimum confidence value for submarker pattern tracking to be valid.
};

inline void releaseJavaString(JNIEnv *env, jstring& javaString, const char *cString)
//----------------------------------------------------------------------------------
{
   if (cString)
      env->ReleaseStringUTFChars(javaString, cString);
}

JNIEXPORT jstring JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetARToolKitVersion
      (JNIEnv *env, jclass klass)
//------------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return JNI_FALSE;

   if (const char *version = gARTK->getARToolKitVersion())
      return env->NewStringUTF(version);
   return NULL;//((void*)0);
}

//void initConstants(JNIEnv *env, jclass klass)
////------------------------------------------
//{
//   ARW_MARKER_OPTION_FILTERED = env->GetStaticIntField(klass, env->GetStaticFieldID(klass, "ARW_MARKER_OPTION_FILTERED", "I"));
//   ARW_MARKER_OPTION_SQUARE_USE_CONT_POSE_ESTIMATION =
//         env->GetStaticIntField(klass, env->GetStaticFieldID(klass, "ARW_MARKER_OPTION_SQUARE_USE_CONT_POSE_ESTIMATION", "I"));
//}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwInitialiseAR
      (JNIEnv *env, jclass klass)
//------------------------------------------------------------------------------------------------------------
{
   if (!gARTK)
      gARTK = new ARemController;
   gARTK->logCallback = log;
//   initConstants(env, klass);
   return (gARTK->initialiseBase()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwInitialiseARWithOptions
      (JNIEnv *env, jclass klass, jint pattSize, jint pattCountMax)
//-----------------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) gARTK = new ARemController;
   gARTK->logCallback = log;
//   initConstants(env, klass);
   return (gARTK->initialiseBase(pattSize, pattCountMax)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwChangeToResourcesDir
      (JNIEnv *env, jclass klass, jstring resPath)
//--------------------------------------------------------------------------------------------------------------------
{
   bool ok;
   if (env->IsSameObject(resPath, nullptr))
      ok = (arUtilChangeToResourcesDirectory(AR_UTIL_RESOURCES_DIRECTORY_BEHAVIOR_BEST, NULL, NULL) == 0);
   else
   {
      const char *path = env->GetStringUTFChars(resPath, nullptr);
      if (path != nullptr)
         ok = (arUtilChangeToResourcesDirectory(AR_UTIL_RESOURCES_DIRECTORY_BEHAVIOR_USE_SUPPLIED_PATH, path, NULL) == 0);
      else
         ok = false;
      releaseJavaString(env, resPath, path);
   }
   return (ok) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwStartRunning
      (JNIEnv *env, jclass klass, jstring videoParams, jstring paramsName, jfloat nearPlane, jfloat farPlane)
//------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return false;
   gARTK->setProjectionNearPlane(nearPlane);
   gARTK->setProjectionFarPlane(farPlane);
   const char *vconf = env->GetStringUTFChars(videoParams, nullptr);
   const char *cparaName = env->GetStringUTFChars(paramsName, nullptr);
   bool ok;
   if ( (vconf != nullptr) && (cparaName != nullptr) )
      ok = gARTK->startRunning(vconf, cparaName, nullptr, 0);
   else
      ok = false;
   releaseJavaString(env, videoParams, vconf);
   releaseJavaString(env, paramsName, cparaName);
   return (ok) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwStartRunningStereo
      (JNIEnv *env, jclass klass, jstring videoParamsLeft, jstring paramsNameLeft,
       jstring videoParamsRight, jstring paramsNameRight, jstring transName, jfloat nearPlane, jfloat farPlane)
//------------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return false;
   gARTK->setProjectionNearPlane(nearPlane);
   gARTK->setProjectionFarPlane(farPlane);
   const char *vconfL = env->GetStringUTFChars(videoParamsLeft, nullptr);
   const char *cparaNameL = env->GetStringUTFChars(paramsNameLeft, nullptr);
   const char *vconfR = env->GetStringUTFChars(videoParamsRight, nullptr);
   const char *cparaNameR = env->GetStringUTFChars(paramsNameRight, nullptr);
   const char *translate = env->GetStringUTFChars(transName, nullptr);
   bool ok = ( (vconfL != nullptr) && (cparaNameL != nullptr) && (vconfR != nullptr) && (cparaNameR != nullptr) &&
               (translate != nullptr) );
   if (ok)
      ok = gARTK->startRunningStereo(vconfL, cparaNameL, nullptr, 0, vconfR, cparaNameR, nullptr, 0, translate, nullptr, 0);
   releaseJavaString(env, videoParamsLeft, vconfL);
   releaseJavaString(env, videoParamsRight, vconfR);
   releaseJavaString(env, paramsNameLeft, cparaNameL);
   releaseJavaString(env, paramsNameRight, cparaNameR);
   releaseJavaString(env, transName, translate);
   return (ok) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwIsRunning
      (JNIEnv *env, jclass klass)
//----------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return JNI_FALSE;
   return (gARTK->isRunning()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwStopRunning
      (JNIEnv *env, jclass klass)
//--------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return JNI_FALSE;
   return (gARTK->stopRunning()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwShutdownAR
      (JNIEnv *env, jclass klass)
//----------------------------------------------------------------------------------------------------------
{
   if (gARTK)
      delete gARTK;
   gARTK = NULL;
   return JNI_TRUE;
}

jfloatArray getProjectionMatrix(JNIEnv *env, int source, jfloatArray result)
//--------------------------------------------------------------------------
{
   jfloat* presult = NULL;
   bool isNew = false;
//   result = env->NewFloatArray(16);
//   env->SetFloatArrayRegion()
#ifdef ARDOUBLE_IS_FLOAT
   float p[16];
   if (! gARTK->getProjectionMatrix(source, p))
      return NULL;
   if (result == NULL)
   {
      result = env->NewFloatArray(16);
      isNew = (result != nullptr);
   }
   if (result)
      presult = env->GetFloatArrayElements(result, NULL);
   if (! presult)
   {
      if (isNew)
         env->DeleteLocalRef(result);
      return NULL;
   }
   for (int i = 0; i < 16; i++) presult[i] = p[i];
#else
   ARdouble p[16];
	if (! gARTK->getProjectionMatrix(source, p))
        return NULL;
   if (result == NULL)
   {
      result = env->NewFloatArray(16);
      isNew = (result != nullptr);
   }
   if (result)
      presult = env->GetFloatArrayElements(result, NULL);
   if (! presult)
   {
      if (isNew)
         env->DeleteLocalRef(result);
      return NULL;
   }
   for (int i = 0; i < 16; i++) presult[i] = static_cast<float>(p[i]);
#endif
   if (presult != NULL)
      env->ReleaseFloatArrayElements(result, presult, 0);
//   if (isNew) env->DeleteLocalRef(result);
   return result;
}

JNIEXPORT jfloatArray JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetProjectionMatrix
      (JNIEnv *env, jclass klass)
//------------------------------------------------------------------------------------------------------------------
{
   if (! gARTK) return NULL;
   return getProjectionMatrix(env, 0, NULL);

}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetProjectionMatrixStereo
      (JNIEnv *env, jclass klass, jfloatArray left, jfloatArray right)
//--------------------------------------------------------------------------------------------------------------------------
{
   if (! getProjectionMatrix(env, 0, left))
      return JNI_FALSE;
   if (! getProjectionMatrix(env, 0, right))
      return JNI_FALSE;
   return JNI_TRUE;
}

bool getVideoParams(JNIEnv *env, const int source, jintArray width, jintArray height, jintArray pixelSize,
                    jobjectArray pixelFormatName)
//---------------------------------------------------------------------------------------------------------------
{
   AR_PIXEL_FORMAT pf;
   int w, h;
   if (!gARTK->videoParameters(0, &w, &h, &pf))
      return false;
   int pxs = arUtilGetPixelSize(pf);
   if (width != NULL)
   {
      jint *pw = env->GetIntArrayElements(width, NULL);
      if (pw != nullptr)
         *pw = w;
      env->ReleaseIntArrayElements(width, pw, 0);
   }
   if (height != NULL)
   {
      jint *ph = env->GetIntArrayElements(height, NULL);
      if (ph != nullptr)
         *ph = h;
      env->ReleaseIntArrayElements(height, ph, 0);
   }
   if (pixelSize != NULL)
   {
      jint *ppxs = env->GetIntArrayElements(pixelSize, NULL);
      if (ppxs != nullptr)
         *ppxs = pxs;
      env->ReleaseIntArrayElements(pixelSize, ppxs, 0);
   }
   if (pixelFormatName != nullptr)
   {
      const char *pixelFormat = arUtilGetPixelFormatName(pf);
      if (pixelFormat != nullptr)
      {
         jstring pixelFormatStr = env->NewStringUTF(pixelFormat);
         if (pixelFormatStr != nullptr)
         {
            env->SetObjectArrayElement(pixelFormatName, 0, pixelFormatStr);
//            env->DeleteLocalRef(pixelFormatStr);
         }
      }
   }
   return true;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetVideoParams
      (JNIEnv *env, jclass klass, jintArray width, jintArray height, jintArray pixelSize, jobjectArray pixelFormatStr)
//----------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return JNI_FALSE;
   return (getVideoParams(env, 0, width, height, pixelSize, pixelFormatStr)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetVideoParamsStereo
      (JNIEnv *env, jclass klass,
       jintArray widthL, jintArray heightL, jintArray pixelSizeL, jobjectArray pixelFormatStrL,
       jintArray widthR, jintArray heightR, jintArray pixelSizeR, jobjectArray pixelFormatStrR)
//------------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return JNI_FALSE;
   bool ok = getVideoParams(env, 0, widthL, heightL, pixelSizeL, pixelFormatStrL);
   return (ok && getVideoParams(env, 0, widthR, heightR, pixelSizeR, pixelFormatStrR))
      ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwCapture
      (JNIEnv *env, jclass klass)
//-------------------------------------------------------------------------------------------------------
{
   if (!gARTK)
      return JNI_FALSE;
   return (gARTK->capture()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwUpdateAR
      (JNIEnv *env, jclass klass)
//---------------------------------------------------------------------------------------------------------
{
   if (!gARTK)
      return JNI_FALSE;
   return (gARTK->update()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwCaptureUpdate
      (JNIEnv *env, jclass klass)
//-------------------------------------------------------------------------------------------------------------
{
   if (!gARTK)
      return JNI_FALSE;
   if (gARTK->capture())
      return (gARTK->update()) ? JNI_TRUE : JNI_FALSE;
   else
      return JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwAddMarker
      (JNIEnv *env, jclass klass, jstring cfg)
//------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return -1;
   int ret = -1;
   const char *pszcfg = env->GetStringUTFChars(cfg, NULL);
   if (pszcfg != NULL)
   {
      ret = gARTK->addMarker(pszcfg);
      env->ReleaseStringUTFChars(cfg, pszcfg);
   }
   return ret;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwRemoveMarker
      (JNIEnv *env, jclass klass, jint markerID)
//-----------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return JNI_FALSE;
   return gARTK->removeMarker(markerID);
}

JNIEXPORT jint JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwRemoveAllMarkers
      (JNIEnv *env, jclass klass)
//------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return 0;
   return gARTK->removeAllMarkers();
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwQueryMarkerVisibility
      (JNIEnv *env, jclass klass, jint markerId)
//--------------------------------------------------------------------------------
{
   if (!gARTK) return JNI_FALSE;
   const ARMarker *marker = gARTK->findMarker(markerId);
   if (! marker)
   {
      LOGE("arwQueryMarkerVisibility(): Couldn't locate marker with UID %d.", markerId);
      return JNI_FALSE;
   }
   return (marker->visible) ? JNI_TRUE : JNI_FALSE;
}

jfloatArray extractMarkerTransformation(JNIEnv *env, const ARMarker *marker, bool isRight, jfloatArray result)
//-----------------------------------------------------------------------------------------------------
{
   if (result == nullptr)
      result = env->NewFloatArray(16);
   jfloat *presult;
   if (result)
   {
      presult = env->GetFloatArrayElements(result, NULL);
      const ARdouble *transformationMatrix = (isRight) ? &marker->transformationMatrixR[0] : &marker->transformationMatrix[0];
      for (int i = 0; i < 16; i++)
         presult[i] = static_cast<float>(transformationMatrix[i]);
      env->ReleaseFloatArrayElements(result, presult, 0);
//      env->DeleteLocalRef(result);
   }
   return result;
}

JNIEXPORT jfloatArray JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwQueryMarkerTransformation
      (JNIEnv *env, jclass klass, jint markerId)
//----------------------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return JNI_FALSE;
   const ARMarker *marker = gARTK->findMarker(markerId);
   if (!marker)
   {
      LOGE("arwQueryMarkerTransformation(): Couldn't locate marker with UID %d.", markerId);
      return NULL;
   }
   return extractMarkerTransformation(env, marker, false, NULL);
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwQueryMarkerTransformationStereo
      (JNIEnv *env, jclass klass, jint markerId, jfloatArray resultL, jfloatArray resultR)
//-------------------------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return JNI_FALSE;
   const ARMarker *marker = gARTK->findMarker(markerId);
   if (!marker)
   {
      LOGE("arwQueryMarkerTransformationStereo(): Couldn't locate marker with UID %d.", markerId);
      return JNI_FALSE;
   }
   bool ok = (extractMarkerTransformation(env, marker, false, resultL) != NULL);
   if (ok)
      ok = (extractMarkerTransformation(env, marker, true, resultR) != NULL);
   return (ok) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwSetMarkerOptionBool
      (JNIEnv *env, jclass klass, jint markerId, jint option, jboolean value)
//----------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return;
   ARMarker *marker = gARTK->findMarker(markerId);
   if (! marker)
   {
      LOGE("arwSetMarkerOptionBool(): Couldn't locate marker with UID %d.", markerId);
      return;
   }

   switch (option)
   {
      case ARW_MARKER_OPTION_FILTERED:
         marker->setFiltered(value);
         break;
      case ARW_MARKER_OPTION_SQUARE_USE_CONT_POSE_ESTIMATION:
         if (marker->type == ARMarker::SINGLE) ((ARMarkerSquare *)marker)->useContPoseEstimation = value;
         break;
      default:
         LOGE("arwSetMarkerOptionBool(): Unrecognised option %d.", option);
         break;
   }
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwSetMarkerOptionInt
      (JNIEnv *env, jclass klass, jint markerId, jint option, jint value)
//---------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return;
   ARMarker *marker = gARTK->findMarker(markerId);
   if (! marker)
   {
      LOGE("arwSetMarkerOptionBool(): Couldn't locate marker with UID %d.", markerId);
      return;
   }

   switch (option)
   {
      case ARW_MARKER_OPTION_MULTI_MIN_SUBMARKERS:
         if (marker->type == ARMarker::MULTI) ((ARMarkerMulti *)marker)->config->min_submarker = value;
         break;
      default:
         LOGE("arwSetMarkerOptionInt(): Unrecognised option %d.", option);
         break;
   }
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwSetMarkerOptionFloat
      (JNIEnv *env, jclass klass, jint markerId, jint option, jfloat value)
//------------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return;
   ARMarker *marker = gARTK->findMarker(markerId);
   if (! marker)
   {
      LOGE("arwSetMarkerOptionBool(): Couldn't locate marker with UID %d.", markerId);
      return;
   }
   switch (option)
   {
      case ARW_MARKER_OPTION_FILTER_SAMPLE_RATE:
         marker->setFilterSampleRate(value);
         break;
      case ARW_MARKER_OPTION_FILTER_CUTOFF_FREQ:
         marker->setFilterCutoffFrequency(value);
         break;
      case ARW_MARKER_OPTION_SQUARE_CONFIDENCE_CUTOFF:
         if (marker->type == ARMarker::SINGLE) ((ARMarkerSquare *)marker)->setConfidenceCutoff(value);
         break;
      case ARW_MARKER_OPTION_NFT_SCALE:
#if HAVE_NFT
         if (marker->type == ARMarker::NFT) ((ARMarkerNFT *)marker)->setNFTScale(value);
#endif
         break;
      case ARW_MARKER_OPTION_MULTI_MIN_CONF_MATRIX:
         if (marker->type == ARMarker::MULTI) ((ARMarkerMulti *)marker)->config->cfMatrixCutoff = value;
         break;
      case ARW_MARKER_OPTION_MULTI_MIN_CONF_PATTERN:
         if (marker->type == ARMarker::MULTI) ((ARMarkerMulti *)marker)->config->cfPattCutoff = value;
         break;
      default:
         LOGE("arwSetMarkerOptionFloat(): Unrecognised option %d.", option);
         break;
   }
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetMarkerOptionBool
      (JNIEnv *env, jclass klass, jint markerId, jint option)
//-------------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return JNI_FALSE;
   ARMarker *marker = gARTK->findMarker(markerId);
   if (! marker)
   {
      LOGE("arwSetMarkerOptionBool(): Couldn't locate marker with UID %d.", markerId);
      return JNI_FALSE;
   }
   switch (option) {
      case ARW_MARKER_OPTION_FILTERED:
         return(marker->isFiltered());
         break;
      case ARW_MARKER_OPTION_SQUARE_USE_CONT_POSE_ESTIMATION:
         if (marker->type == ARMarker::SINGLE) return (((ARMarkerSquare *)marker)->useContPoseEstimation);
         break;
      default:
         LOGE("arwGetMarkerOptionBool(): Unrecognised option %d.", option);
         break;
   }
   return JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetMarkerOptionInt
      (JNIEnv *env, jclass klass, jint markerId, jint option)
//----------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return INT_MIN;
   ARMarker *marker = gARTK->findMarker(markerId);
   if (! marker)
   {
      LOGE("arwSetMarkerOptionBool(): Couldn't locate marker with UID %d.", markerId);
      return INT_MIN;
   }
   switch (option) {
      case ARW_MARKER_OPTION_MULTI_MIN_SUBMARKERS:
         if (marker->type == ARMarker::MULTI) return ((ARMarkerMulti *)marker)->config->min_submarker;
         break;
      default:
         LOGE("arwGetMarkerOptionInt(): Unrecognised option %d.", option);
         break;
   }
   return (INT_MIN);
}

JNIEXPORT jfloat JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetMarkerOptionFloat
      (JNIEnv *env, jclass klass, jint markerId, jint option)
//------------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return NAN;
   ARMarker *marker = gARTK->findMarker(markerId);
   if (! marker)
   {
      LOGE("arwSetMarkerOptionBool(): Couldn't locate marker with UID %d.", markerId);
      return NAN;
   }
   switch (option)
   {
      case ARW_MARKER_OPTION_FILTER_SAMPLE_RATE:
         return ((float)marker->filterSampleRate());
         break;
      case ARW_MARKER_OPTION_FILTER_CUTOFF_FREQ:
         return ((float)marker->filterCutoffFrequency());
         break;
      case ARW_MARKER_OPTION_SQUARE_CONFIDENCE:
         if (marker->type == ARMarker::SINGLE) return ((float)((ARMarkerSquare *)marker)->getConfidence());
         else return NAN;
         break;
      case ARW_MARKER_OPTION_SQUARE_CONFIDENCE_CUTOFF:
         if (marker->type == ARMarker::SINGLE) return ((float)((ARMarkerSquare *)marker)->getConfidenceCutoff());
         else return (NAN);
         break;
      case ARW_MARKER_OPTION_NFT_SCALE:
#if HAVE_NFT
         if (marker->type == ARMarker::NFT) return ((float)((ARMarkerNFT *)marker)->getNFTScale());
            else return (NAN);
#else
         return (NAN);
#endif
         break;
      case ARW_MARKER_OPTION_MULTI_MIN_CONF_MATRIX:
         if (marker->type == ARMarker::MULTI) return (float)((ARMarkerMulti *)marker)->config->cfMatrixCutoff;
         else return (NAN);
         break;
      case ARW_MARKER_OPTION_MULTI_MIN_CONF_PATTERN:
         if (marker->type == ARMarker::MULTI) return (float)((ARMarkerMulti *)marker)->config->cfPattCutoff;
         else return (NAN);
         break;
      default:
         gARTK->logv(AR_LOG_LEVEL_ERROR, "arwGetMarkerOptionFloat(): Unrecognised option %d.", option);
         break;
   }
   return NAN;
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwSetVideoDebugMode
      (JNIEnv *env, jclass klass, jboolean on)
//-------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return;
   gARTK->setDebugMode(on);
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetVideoDebugMode
      (JNIEnv *env, jclass klass)
//-----------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return JNI_FALSE;
   return (gARTK->getDebugMode()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwSetVideoThreshold
      (JNIEnv *env, jclass klass, jint threshold)
//-------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return;
   gARTK->setThreshold(threshold);
}

JNIEXPORT jint JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetVideoThreshold
      (JNIEnv *env, jclass klass)
//-------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return 0;
   return gARTK->getThreshold();
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwSetVideoThresholdMode
      (JNIEnv *env, jclass klass, jint mode)
//----------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return;
   gARTK->setThresholdMode(mode);
}

JNIEXPORT jint JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetVideoThresholdMode
      (JNIEnv *env, jclass klass)
//-----------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return 0;
   return gARTK->getThresholdMode();
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwAcceptVideoImage
      (JNIEnv *env, jclass klass, jbyteArray pinArray, jint width, jint height, jint cameraIndex,
       jboolean cameraIsFrontFacing)
//---------------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return JNI_FALSE;
   return gARTK->videoAcceptImage(env, klass, 0, pinArray, width, height, cameraIndex, cameraIsFrontFacing);
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwAcceptVideoImageStereo
      (JNIEnv *env, jclass klass,
       jbyteArray pinArrayL, jint widthL, jint heightL, jint cameraIndexL, jboolean cameraIsFrontFacingL,
       jbyteArray pinArrayR, jint widthR, jint heightR, jint cameraIndexR, jboolean cameraIsFrontFacingR)
//----------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return JNI_FALSE;
   bool ok = ((gARTK->videoAcceptImage(env, klass, 0, pinArrayL, widthL, heightL, cameraIndexL, cameraIsFrontFacingL)) &&
              (gARTK->videoAcceptImage(env, klass, 1, pinArrayR, widthR, heightR, cameraIndexR, cameraIsFrontFacingR)) );
   return (ok) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwUpdateDebugTexture32
      (JNIEnv *env, jclass klass, jbyteArray buffer)
//--------------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return JNI_FALSE;
   bool ok = false;
   jbyte *pbuffer = env->GetByteArrayElements(buffer, NULL);
   if (pbuffer)
   {
      ok = gARTK->updateDebugTexture32(0, (uint32_t *) pbuffer);
      env->ReleaseByteArrayElements(buffer, pbuffer, 0);
   }
   return (ok) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwSetLabelingMode
      (JNIEnv *env, jclass klass, jint mode)
//-----------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return;
   gARTK->setLabelingMode(mode);
}

JNIEXPORT jint JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetLabelingMode
      (JNIEnv *env, jclass klass)
//------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return 0;
   return gARTK->getLabelingMode();
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwSetPatternDetectionMode
      (JNIEnv *env, jclass klass, jint mode)
//-------------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return;
   gARTK->setPatternDetectionMode(mode);
}

JNIEXPORT jint JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetPatternDetectionMode
      (JNIEnv *env, jclass klass)
//------------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return 0;
   return gARTK->getPatternDetectionMode();
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwSetBorderSize
      (JNIEnv *env, jclass klass, jfloat size)
//---------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return;
   gARTK->setPattRatio(1.0f - 2.0f*size);
}

JNIEXPORT jfloat JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetBorderSize
      (JNIEnv *env, jclass klass)
//-----------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return 0.0f;
   return ((1.0f - gARTK->getPattRatio()) * 0.5f);
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwSetMatrixCodeType
      (JNIEnv *env, jclass klass, jint type)
//--------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return;
   gARTK->setMatrixCodeType(type);
}

JNIEXPORT jint JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetMatrixCodeType
      (JNIEnv *env, jclass klass)
//--------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return 0;
   return gARTK->getMatrixCodeType();
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwSetImageProcMode
      (JNIEnv *env, jclass klass, jint mode)
//------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return;
   gARTK->setImageProcMode(mode);
}

JNIEXPORT jint JNICALL Java_to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu_arwGetImageProcMode
      (JNIEnv *env, jclass klass)
//------------------------------------------------------------------------------------------------------------
{
   if (!gARTK) return 0;
   return gARTK->getImageProcMode();
}
