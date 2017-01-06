#include <jni.h>
#ifndef DESKTOP
#include <android/log.h>
#endif

#include "to_augmented_reality_android_em_recorder_CV.h"

#include <stdio.h>

#include <iostream>
#include <exception>
#include <limits>
#include <vector>

#include <opencv2/core/core.hpp>
#include <opencv2/core/mat.hpp>
#include <opencv2/core/ocl.hpp>
#include <opencv2/highgui/highgui.hpp>
#include <opencv2/features2d/features2d.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include "opencv2/stitching.hpp"

#ifndef DESKTOP
#define  LOG_TAG    "framediff.so"
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)
#endif

//#define NDEBUG
//#include <assert.h>

jint throw_jni(JNIEnv *env, const char *message,
               std::string exname ="to/augmented/reality/android/em/recorder/NativeCVException")
//--------------------------------------------------------------------------------------------
{
   jclass klass;
   bool b = false;
   klass = env->FindClass(exname.c_str());
   if (klass != NULL)
   {
      b = env->ThrowNew(klass, message);
      env->DeleteLocalRef(klass);
   }
   else
#ifdef ANDROID_LOG
      LOGE("Error finding Java exception '%s'", exname.c_str());
#endif
   return b;
}


double psnr(uchar *img1, uchar *img2, int width, int height)
//----------------------------------------------------------
{
   double ret = 0;
   cv::Mat rgba1(height, width, CV_8UC4, img1), rgba2(height, width, CV_8UC4, img2);
   cv::Mat diff;
   cv::absdiff(rgba1, rgba2, diff);
   diff.convertTo(diff, CV_32F);
   diff = diff.mul(diff);
   cv::Scalar s = cv::sum(diff);
   double sse = s.val[0] + s.val[1] + s.val[2];
   if ( sse <= 1e-10)
      ret = 0;
   else
   {
      double mse  = sse / (double)(rgba1.channels() * rgba1.total());
      ret = 10.0 * log10((255 * 255) / mse);
   }
   return ret;
}

JNIEXPORT jdouble JNICALL Java_to_augmented_reality_android_em_recorder_CV_PSNR__IILjava_nio_ByteBuffer_2Ljava_nio_ByteBuffer_2
  (JNIEnv *env, jclass klass, jint width, jint height, jobject image1, jobject image2)
//------------------------------------------------------------------------------------------------
{
   uchar* img1 = (uchar*) env->GetDirectBufferAddress(image1);
   uchar* img2 = (uchar*) env->GetDirectBufferAddress(image2);
   try
   {
     return psnr(img1, img2, width, height);
   }
   catch( cv::Exception& e )
   {
      std::stringstream ss;
      ss << "PSNR OpenCV Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      if (! throw_jni(env, errm))
         throw_jni(env, errm, "java/lang/RuntimeException");
   }
   catch (std::exception &e)
   {
      std::stringstream ss;
      ss << "PSNR Fatal Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      throw_jni(env, errm,"java/lang/RuntimeException");
   }
}

JNIEXPORT jdouble JNICALL Java_to_augmented_reality_android_em_recorder_CV_PSNR__II_3B_3B
  (JNIEnv *env, jclass klass, jint width, jint height, jbyteArray image1, jbyteArray image2)
//------------------------------------------------------------------------------------------
{
   uchar* img1 =  (uchar *) env->GetPrimitiveArrayCritical(image1, 0);
   uchar* img2 =  (uchar *) env->GetPrimitiveArrayCritical(image2, 0);
   double v = -1.0;
   try
   {
      v = psnr(img1, img2, width, height);
   }
   catch( cv::Exception& e )
   {
      std::stringstream ss;
      ss << "PSNR OpenCV Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      if (! throw_jni(env, errm))
         throw_jni(env, errm, "java/lang/RuntimeException");
   }
   catch (std::exception &e)
   {
      std::stringstream ss;
      ss << "PSNR Fatal Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      throw_jni(env, errm,"java/lang/RuntimeException");
   }
   env->ReleasePrimitiveArrayCritical(image1, img1, 0);
   env->ReleasePrimitiveArrayCritical(image2, img2, 0);
   return v;
}

double mssim(uchar *img1, uchar *img2, int width, int height)
//----------------------------------------------------------
{
   const double C1 = 6.5025, C2 = 58.5225;
   int d = CV_32F;
   cv::Mat rgba1(height, width, CV_8UC4, img1), rgba2(height, width, CV_8UC4, img2);

   rgba1.convertTo(rgba1, d);           // cannot calculate on one byte large values
   rgba2.convertTo(rgba2, d);

   cv::Mat rgba2_2 = rgba2.mul(rgba2);
   cv::Mat rgba1_2 = rgba1.mul(rgba1);
   cv::Mat rgba1_rgba2 = rgba1.mul(rgba2);

   cv::Mat mu1, mu2;
   cv::GaussianBlur(rgba1, mu1, cv::Size(11, 11), 1.5);
   cv::GaussianBlur(rgba2, mu2, cv::Size(11, 11), 1.5);

   cv::Mat mu1_2   =   mu1.mul(mu1);
   cv::Mat mu2_2   =   mu2.mul(mu2);
   cv::Mat mu1_mu2 =   mu1.mul(mu2);

   cv::Mat sigma1_2, sigma2_2, sigma12;

   cv::GaussianBlur(rgba1_2, sigma1_2, cv::Size(11, 11), 1.5);
   sigma1_2 -= mu1_2;

   cv::GaussianBlur(rgba2_2, sigma2_2, cv::Size(11, 11), 1.5);
   sigma2_2 -= mu2_2;

   cv::GaussianBlur(rgba1_rgba2, sigma12, cv::Size(11, 11), 1.5);
   sigma12 -= mu1_mu2;

   ///////////////////////////////// FORMULA ////////////////////////////////
   cv::Mat t1, t2, t3;

   t1 = 2 * mu1_mu2 + C1;
   t2 = 2 * sigma12 + C2;
   t3 = t1.mul(t2);              // t3 = ((2*mu1_mu2 + C1).*(2*sigma12 + C2))

   t1 = mu1_2 + mu2_2 + C1;
   t2 = sigma1_2 + sigma2_2 + C2;
   t1 = t1.mul(t2);               // t1 =((mu1_2 + mu2_2 + C1).*(sigma1_2 + sigma2_2 + C2))

   cv::Mat ssim_map;
   divide(t3, t1, ssim_map);      // ssim_map =  t3./t1;

   cv::Scalar mssim = mean( ssim_map ); // mssim = average of ssim map

//   env->ReleaseByteArrayElements(image1, (jbyte*) img1, JNI_ABORT);
//   env->ReleaseByteArrayElements(image2, (jbyte*) img2, JNI_ABORT);

   return (mssim.val[0] + mssim.val[1] + mssim.val[2]) / 3;
}

JNIEXPORT jdouble JNICALL Java_to_augmented_reality_android_em_recorder_CV_MSSIM__IILjava_nio_ByteBuffer_2Ljava_nio_ByteBuffer_2
  (JNIEnv *env, jclass klass, jint width, jint height, jobject image1, jobject image2)
//-----------------------------------------------------------
{
   uchar* img1 = (uchar*) env->GetDirectBufferAddress(image1);
   uchar* img2 = (uchar*) env->GetDirectBufferAddress(image2);
   try
   {
      return mssim(img1, img2, width, height);
   }
   catch( cv::Exception& e )
   {
      std::stringstream ss;
      ss << "PSNR OpenCV Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      if (! throw_jni(env, errm))
         throw_jni(env, errm, "java/lang/RuntimeException");
   }
   catch (std::exception &e)
   {
      std::stringstream ss;
      ss << "PSNR Fatal Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      throw_jni(env, errm,"java/lang/RuntimeException");
   }
}

JNIEXPORT jdouble JNICALL Java_to_augmented_reality_android_em_recorder_CV_MSSIM__II_3B_3B
  (JNIEnv *env, jclass klass, jint width, jint height, jbyteArray image1, jbyteArray image2)
//-----------------------------------------------------------------------------------------
{
   uchar* img1 =  (uchar *) env->GetPrimitiveArrayCritical(image1, 0);
   uchar* img2 =  (uchar *) env->GetPrimitiveArrayCritical(image2, 0);
   double v = -1;
   try
   {
      v = mssim(img1, img2, width, height);
   }
   catch( cv::Exception& e )
   {
      std::stringstream ss;
      ss << "PSNR OpenCV Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      if (! throw_jni(env, errm))
         throw_jni(env, errm, "java/lang/RuntimeException");
   }
   catch (std::exception &e)
   {
      std::stringstream ss;
      ss << "PSNR Fatal Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      throw_jni(env, errm,"java/lang/RuntimeException");
   }
   env->ReleasePrimitiveArrayCritical(image1, img1, 0);
   env->ReleasePrimitiveArrayCritical(image2, img2, 0);
   return v;
}

void shift(cv::Mat &grey1, cv::Mat &grey2, jint width, jint height, jint* result_arr)
//-----------------------------------------------------------------------------------
{
   int dftwidth = cv::getOptimalDFTSize(std::max(grey1.cols, grey2.cols));
   int dftheight = cv::getOptimalDFTSize(std::max(grey1.rows, grey2.rows));
   cv::Mat fft1(cv::Size(dftwidth, dftheight), CV_32F, cv::Scalar(0));
   cv::Mat fft2(cv::Size(dftwidth, dftheight), CV_32F, cv::Scalar(0));

   grey1.convertTo(fft1, CV_32F);
//   for(int j=0; j<grey1.rows; j++)
//      for(int i=0; i<grey1.cols; i++)
//         fft1.at<float>(j,i) = grey1.at<unsigned char>(j,i);

   grey2.convertTo(fft2, CV_32F);
//   for(int j=0; j<grey2.rows; j++)
//      for(int i=0; i<grey2.cols; i++)
//         fft2.at<float>(j,i) = grey2.at<unsigned char>(j,i);

   cv::dft(fft1,fft1, 0, grey1.rows);
   cv::dft(fft2,fft2, 0, grey2.rows);
   cv::mulSpectrums(fft1, fft2, fft1, 0, true);
   cv::idft(fft1, fft1);
   double maxVal;
   cv::Point maxLoc;
   cv::minMaxLoc(fft1, NULL, &maxVal, NULL, &maxLoc);
   result_arr[0] = (maxLoc.x < dftwidth/2) ? (maxLoc.x) : (maxLoc.x - dftwidth);
   result_arr[1] = (maxLoc.y < dftheight/2) ? (maxLoc.y) : (maxLoc.y - dftheight);
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_CV_SHIFT__IILjava_nio_ByteBuffer_2Ljava_nio_ByteBuffer_2_3I
  (JNIEnv *env, jclass klass, jint width, jint height, jobject image1, jobject image2, jintArray result)
//---------------------------------------------------------------------------------------------------------
{
   try
   {
      uchar* img1 = (uchar*) env->GetDirectBufferAddress(image1);
      uchar* img2 = (uchar*) env->GetDirectBufferAddress(image2);
      jint* result_arr = env->GetIntArrayElements(result, 0);
      cv::Mat grey1(height, width, CV_8UC1, img1), grey2(height, width, CV_8UC1, img2);
      shift(grey1, grey2, width, height, result_arr);
      env->ReleaseIntArrayElements(result, result_arr, 0);
   }
   catch( cv::Exception& e )
   {
      std::stringstream ss;
      ss << "SHIFT OpenCV Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      if (! throw_jni(env, errm))
         throw_jni(env, errm, "java/lang/RuntimeException");
   }
   catch (std::exception &e)
   {
      std::stringstream ss;
      ss << "SHIFT Fatal Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      throw_jni(env, errm,"java/lang/RuntimeException");
   }
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_CV_TOGREY_1SHIFT__IILjava_nio_ByteBuffer_2Ljava_nio_ByteBuffer_2_3I
  (JNIEnv *env, jclass klass, jint width, jint height, jobject image1, jobject image2, jintArray result)
//--------------------------------------------------------------------------------------------------------------------------------------
{
   try
   {
      uchar* img1 = (uchar*) env->GetDirectBufferAddress(image1);
      uchar* img2 = (uchar*) env->GetDirectBufferAddress(image2);
      jint* result_arr = env->GetIntArrayElements(result, 0);
      cv::Mat rgba(height, width, CV_8UC4, img1);
      cv::Mat grey1(height, width, CV_8UC1), grey2(height, width, CV_8UC1);
      cv::cvtColor(rgba, grey1, cv::COLOR_RGBA2GRAY);
      rgba = cv::Mat(height, width, CV_8UC4, img2);
      cv::cvtColor(rgba, grey2, cv::COLOR_RGBA2GRAY);
      shift(grey1, grey2, width, height, result_arr);
      env->ReleaseIntArrayElements(result, result_arr, 0);
   }
   catch( cv::Exception& e )
   {
      std::stringstream ss;
      ss << "SHIFT OpenCV Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      if (! throw_jni(env, errm))
         throw_jni(env, errm, "java/lang/RuntimeException");
   }
   catch (std::exception &e)
   {
      std::stringstream ss;
      ss << "SHIFT Fatal Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      throw_jni(env, errm,"java/lang/RuntimeException");
   }
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_CV_SHIFT__II_3B_3B_3I
  (JNIEnv *env, jclass klass, jint width, jint height, jbyteArray image1, jbyteArray image2, jintArray result)
//-------------------------------------------------------------------------------------------------------------
{
   uchar *img1 = nullptr, *img2 = nullptr;
   jint *result_arr = nullptr;
   try
   {
      img1 =  (uchar *) env->GetPrimitiveArrayCritical(image1, 0);
      cv::Mat grey1(height, width, CV_8UC1, img1);
      env->ReleasePrimitiveArrayCritical(image1, img1, 0);
      img1 = nullptr;

      img2 =  (uchar *) env->GetPrimitiveArrayCritical(image2, 0);
      cv::Mat grey2(height, width, CV_8UC1, img2);
      env->ReleasePrimitiveArrayCritical(image2, img2, 0);
      img2 = nullptr;

      result_arr = env->GetIntArrayElements(result, NULL);
      shift(grey1, grey2, width, height, result_arr);
   }
   catch( cv::Exception& e )
   {
      std::stringstream ss;
      ss << "SHIFT OpenCV Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      if (! throw_jni(env, errm))
         throw_jni(env, errm, "java/lang/RuntimeException");
   }
   catch (std::exception &e)
   {
      std::stringstream ss;
      ss << "SHIFT Fatal Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      throw_jni(env, errm,"java/lang/RuntimeException");
   }
   if (img1 != nullptr)
      env->ReleasePrimitiveArrayCritical(image1, img1, 0);
   if (img2 != nullptr)
      env->ReleasePrimitiveArrayCritical(image1, img2, 0);
   if (result_arr != nullptr)
      env->ReleaseIntArrayElements(result, result_arr, 0);
}

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_CV_TOGREY_1SHIFT__II_3B_3B_3I
(JNIEnv *env, jclass klass, jint width, jint height, jbyteArray image1, jbyteArray image2, jintArray result)
//-------------------------------------------------------------------------------------------------------------
{
   uchar *img1 = nullptr, *img2 = nullptr;
   jint *result_arr = nullptr;
   cv::Mat grey1(height, width, CV_8UC1), grey2(height, width, CV_8UC1);
   try
   {
      img1 =  (uchar *) env->GetPrimitiveArrayCritical(image1, 0);
      cv::Mat rgba(height, width, CV_8UC4, img1);
      env->ReleasePrimitiveArrayCritical(image1, img1, 0);
      img1 = nullptr;
      cv::cvtColor(rgba, grey1, cv::COLOR_RGBA2GRAY);

      img2 =  (uchar *) env->GetPrimitiveArrayCritical(image2, 0);
      rgba = cv::Mat(height, width, CV_8UC4, img2);
      env->ReleasePrimitiveArrayCritical(image2, img2, 0);
      img2 = nullptr;
      cv::cvtColor(rgba, grey2, cv::COLOR_RGBA2GRAY);

      result_arr = env->GetIntArrayElements(result, 0);
      shift(grey1, grey2, width, height, result_arr);
   }
   catch( cv::Exception& e )
   {
      std::stringstream ss;
      ss << "SHIFT OpenCV Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      if (! throw_jni(env, errm))
         throw_jni(env, errm, "java/lang/RuntimeException");
   }
   catch (std::exception &e)
   {
      std::stringstream ss;
      ss << "SHIFT Fatal Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      throw_jni(env, errm,"java/lang/RuntimeException");
   }
   if (img1 != nullptr)
      env->ReleasePrimitiveArrayCritical(image1, img1, 0);
   if (img2 != nullptr)
      env->ReleasePrimitiveArrayCritical(image1, img2, 0);
   if (result_arr != nullptr)
      env->ReleaseIntArrayElements(result, result_arr, 0);
}

inline bool stitch(std::vector<cv::Mat>& v, uchar* output)
//--------------------------------------------------------
{
   cv::Mat res; //(image1.rows, image1.cols*2, CV_8UC4);
   cv::Stitcher::Mode mode = cv::Stitcher::Mode::SCANS;
   cv::Ptr<cv::Stitcher> stitcher = cv::Stitcher::create(mode, false);
   // stitcher->setFeaturesFinder(cv::makePtr<cv::detail::OrbFeaturesFinder>(cv::Size(3,1), 1000));
   cv::Stitcher::Status status = stitcher->stitch(v, res);
   switch (status)
   {
      case cv::Stitcher::Status::OK:
      {
         cv::Mat result(v[0].rows, v[0].cols, CV_8UC4, output);
         cv::Mat resa(v[0].rows, v[0].cols, CV_8UC4);
//         cv::imwrite("/sdcard/stitch-result0.png", res);
         cv::cvtColor(res, resa, CV_RGB2RGBA);
         if ( (res.rows != v[0].rows) || (res.cols != v[0].cols) )
            cv::resize(resa, result, result.size(), 0, 0, cv::INTER_LANCZOS4);
         else
            resa.copyTo(result);
//         cv::imwrite("/sdcard/stitch-1.png", v[0]);
//         cv::imwrite("/sdcard/stitch-2.png", v[1]);
//         cv::imwrite("/sdcard/stitch-3.png", v[2]);
//         cv::imwrite("/sdcard/stitch-result1.png", result);
         return true;
      }
      case cv::Stitcher::Status::ERR_NEED_MORE_IMGS:
         LOGE("cv::STITCH: Stitcher returned ERR_NEED_MORE_IMGS"); break;
      case cv::Stitcher::Status::ERR_HOMOGRAPHY_EST_FAIL:
         LOGE("cv::STITCH: Stitcher returned ERR_HOMOGRAPHY_EST_FAIL"); break;
      case cv::Stitcher::Status::ERR_CAMERA_PARAMS_ADJUST_FAIL:
         LOGE("cv::STITCH: Stitcher returned ERR_CAMERA_PARAMS_ADJUST_FAIL"); break;
      default:
         LOGE("cv::STITCH: Stitcher returned unknown status %d", status); break;
   }
   return false;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_recorder_CV_STITCH3__IILjava_nio_ByteBuffer_2Ljava_nio_ByteBuffer_2Ljava_nio_ByteBuffer_2Ljava_nio_ByteBuffer_2
  (JNIEnv *env, jclass klass, jint width, jint height, jobject im1, jobject im2, jobject im3, jobject output)
//----------------------------------------------------------------------------------------------------------------------------------------------------
{
   uchar* img1 = (uchar*) env->GetDirectBufferAddress(im1);
   uchar* img2 = (uchar*) env->GetDirectBufferAddress(im2);
   uchar* img3 = (uchar*) env->GetDirectBufferAddress(im3);
   cv::Mat rgba1(height, width, CV_8UC4, img1), rgba2(height, width, CV_8UC4, img2),
           rgba3(height, width, CV_8UC4, img3);
   cv::Mat image1(height, width, CV_8UC3), image2(height, width, CV_8UC3),
           image3(height, width, CV_8UC3);
   cv::cvtColor(rgba1, image1, CV_RGBA2RGB);
   cv::cvtColor(rgba2, image2, CV_RGBA2RGB);
   cv::cvtColor(rgba3, image3, CV_RGBA2RGB);

   uchar* res = (uchar*) env->GetDirectBufferAddress(output);
   std::vector<cv::Mat> v;
   v.push_back(image1);
   v.push_back(image2);
   v.push_back(image3);
   return (stitch(v, res)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_recorder_CV_STITCH3__II_3B_3B_3B_3B
  (JNIEnv *env, jclass klass, jint width, jint height, jbyteArray im1, jbyteArray im2, jbyteArray im3,
   jbyteArray output)
//--------------------------------------------------------------------------------------------
{
   jboolean ret = JNI_FALSE;
   uchar *img1 = nullptr, *img2 = nullptr, *img3 = nullptr, *res;
   try
   {
      img1 =  (uchar *) env->GetPrimitiveArrayCritical(im1, 0);
      cv::Mat rgba1(height, width, CV_8UC4, img1), image1(height, width, CV_8UC3);
      cv::cvtColor(rgba1, image1, CV_RGBA2RGB);
      img2 =  (uchar *) env->GetPrimitiveArrayCritical(im2, 0);
      cv::Mat rgba2 = cv::Mat(height, width, CV_8UC4, img2), image2(height, width, CV_8UC3);
      cv::cvtColor(rgba2, image2, CV_RGBA2RGB);
      img3 =  (uchar *) env->GetPrimitiveArrayCritical(im3, 0);
      cv::Mat rgba3(height, width, CV_8UC4, img3), image3(height, width, CV_8UC3);
      cv::cvtColor(rgba3, image3, CV_RGBA2RGB);
      res =  (uchar *) env->GetPrimitiveArrayCritical(output, 0);
      std::vector<cv::Mat> v;
      v.push_back(image1);
      v.push_back(image2);
      v.push_back(image3);
      ret = (stitch(v, res)) ? JNI_TRUE : JNI_FALSE;
  }
  catch (std::exception &e)
  {
     std::stringstream ss;
     ss << "STITCH Fatal Exception: " << e.what();
     const char *errm = ss.str().c_str();
     LOGE("%s", errm);
     throw_jni(env, errm,"java/lang/RuntimeException");
  }
  if (img1 != nullptr)
     env->ReleasePrimitiveArrayCritical(im1, img1, 0);
  if (img2 != nullptr)
     env->ReleasePrimitiveArrayCritical(im2, img2, 0);
  if (img3 != nullptr)
     env->ReleasePrimitiveArrayCritical(im3, img3, 0);
  if (res != nullptr)
     env->ReleasePrimitiveArrayCritical(output, res, 0);
  return ret;
}

void kludge(int width, int height, cv::Mat &rgba, int shift, bool is_right,
                cv::Mat &out)
//-----------------------------------------------------------------------------
{
   rgba.copyTo(out);
   //assert(out.isContinuous());
   if (is_right)
      rgba(cv::Rect(0, 0, rgba.cols-shift, rgba.rows)).copyTo(
                           out(cv::Rect(shift, 0, rgba.cols-shift, rgba.rows)));
   else
      rgba(cv::Rect(shift, 0, rgba.cols-shift, rgba.rows)).copyTo(
                           out(cv::Rect(0, 0, rgba.cols-shift, rgba.rows)));
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_recorder_CV_KLUDGE_1NV21__IILjava_nio_ByteBuffer_2IZLjava_nio_ByteBuffer_2
  (JNIEnv *env, jclass klass, jint width, jint height, jobject image, jint shift, jboolean is_right, jobject image_out)
//------------------------------------------------------------------------------------------------------------------
{
   try
   {
      uchar* img = (uchar*) env->GetDirectBufferAddress(image);
      cv::Mat nv21(height+height/2, width, CV_8UC1, img);
      cv::Mat rgba(height, width, CV_8UC4);
      cv::cvtColor(nv21, rgba, CV_YUV2RGBA_NV21);

      jsize outlen = env->GetDirectBufferCapacity(image_out);
      int len = rgba.step * rgba.rows;
      if (outlen < len)
      {
         LOGE("KLUDGE_NV21: Output length %d less than input length %d", outlen, len);
         return JNI_FALSE;
      }

      uchar* imgout = (uchar*) env->GetDirectBufferAddress(image_out);
      cv::Mat out = cv::Mat(height, width, CV_8UC4, imgout);
      kludge(width, height, rgba, shift, (is_right != JNI_FALSE), out);
   }
   catch( cv::Exception& e )
   {
      std::stringstream ss;
      ss << "KLUDGE_NV21 OpenCV Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      if (! throw_jni(env, errm))
         throw_jni(env, errm, "java/lang/RuntimeException");
   }
   catch (std::exception &e)
   {
      std::stringstream ss;
      ss << "KLUDGE_NV21 Fatal Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      throw_jni(env, errm,"java/lang/RuntimeException");
   }
   return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_recorder_CV_KLUDGE_1RGBA__IILjava_nio_ByteBuffer_2IZLjava_nio_ByteBuffer_2
  (JNIEnv *env, jclass klass, jint width, jint height, jobject image, jint shift,
   jboolean is_right, jobject image_out)
//-------------------------------------------------------------------------------------------
{
   try
   {
      uchar* img = (uchar*) env->GetDirectBufferAddress(image);
      cv::Mat rgba(height, width, CV_8UC4, img);

      jsize outlen = env->GetDirectBufferCapacity(image_out);
      int len = rgba.step * rgba.rows;
      if (outlen < len)
      {
         LOGE("KLUDGE_NV21: Output length %d less than input length %d", outlen, len);
         return JNI_FALSE;
      }

      uchar* imgout = (uchar*) env->GetDirectBufferAddress(image_out);
      cv::Mat out = cv::Mat(height, width, CV_8UC4, imgout);
      kludge(width, height, rgba, shift, (is_right != JNI_FALSE), out);
   }
   catch( cv::Exception& e )
   {
      std::stringstream ss;
      ss << "KLUDGE_RGBA OpenCV Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      if (! throw_jni(env, errm))
         throw_jni(env, errm, "java/lang/RuntimeException");
   }
   catch (std::exception &e)
   {
      std::stringstream ss;
      ss << "KLUDGE_RGBA Fatal Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      throw_jni(env, errm,"java/lang/RuntimeException");
   }
   return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_recorder_CV_KLUDGE_1NV21__II_3BIZ_3B
  (JNIEnv *env, jclass klass, jint width, jint height, jbyteArray image,
   jint shift, jboolean is_right, jbyteArray image_out)
//---------------------------------------------------------------------------------------------------
{
   uchar *img = nullptr, *imgout = nullptr;
   try
   {
      img =  (uchar *) env->GetPrimitiveArrayCritical(image, 0);
      cv::Mat nv21(height+height/2, width, CV_8UC1, img);
      env->ReleasePrimitiveArrayCritical(image, img, 0);
      img = nullptr;
      cv::Mat rgba(height, width, CV_8UC4);
      cv::cvtColor(nv21, rgba, CV_YUV2RGBA_NV21);

      jsize outlen = env->GetArrayLength(image_out);
      int len = rgba.step * rgba.rows;
      if (outlen < len)
      {
         LOGE("KLUDGE_NV21: Output length %d less than input length %d", outlen, len);
         return JNI_FALSE;
      }

      imgout =  (uchar *) env->GetPrimitiveArrayCritical(image_out, 0);
      cv::Mat out = cv::Mat(height, width, CV_8UC4, imgout);
      kludge(width, height, rgba, shift, (is_right != JNI_FALSE), out);
   }
   catch( cv::Exception& e )
   {
      std::stringstream ss;
      ss << "KLUDGE_NV21 OpenCV Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      if (! throw_jni(env, errm))
         throw_jni(env, errm, "java/lang/RuntimeException");
   }
   catch (std::exception &e)
   {
      std::stringstream ss;
      ss << "KLUDGE_NV21 Fatal Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      throw_jni(env, errm,"java/lang/RuntimeException");
   }
   if (imgout != nullptr)
      env->ReleasePrimitiveArrayCritical(image_out, imgout, 0);
   if (img != nullptr)
      env->ReleasePrimitiveArrayCritical(image, img, 0);
   return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_recorder_CV_KLUDGE_1RGBA__II_3BIZ_3B
  (JNIEnv *env, jclass klass, jint width, jint height, jbyteArray image,
   jint shift, jboolean is_right, jbyteArray image_out)
//---------------------------------------------------------------------------------------------------
{
   uchar *img = nullptr, *imgout = nullptr;
   try
   {
      img =  (uchar *) env->GetPrimitiveArrayCritical(image, 0);
      cv::Mat rgba(height, width, CV_8UC4, img);
      env->ReleasePrimitiveArrayCritical(image, img, 0);
      img = nullptr;

      jsize outlen = env->GetArrayLength(image_out);
      int len = rgba.step * rgba.rows;
      if (outlen < len)
      {
         LOGE("KLUDGE_NV21: Output length %d less than input length %d", outlen, len);
         return JNI_FALSE;
      }

      imgout =  (uchar *) env->GetPrimitiveArrayCritical(image_out, 0);
      cv::Mat out = cv::Mat(height, width, CV_8UC4, imgout);
      kludge(width, height, rgba, shift, (is_right != JNI_FALSE), out);
   }
   catch( cv::Exception& e )
   {
      std::stringstream ss;
      ss << "KLUDGE_NV21 OpenCV Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      if (! throw_jni(env, errm))
         throw_jni(env, errm, "java/lang/RuntimeException");
   }
   catch (std::exception &e)
   {
      std::stringstream ss;
      ss << "KLUDGE_NV21 Fatal Exception: " << e.what();
      const char *errm = ss.str().c_str();
      LOGE("%s", errm);
      throw_jni(env, errm,"java/lang/RuntimeException");
   }
   if (imgout != nullptr)
      env->ReleasePrimitiveArrayCritical(image_out, imgout, 0);
   if (img != nullptr)
      env->ReleasePrimitiveArrayCritical(image, img, 0);
   return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_recorder_CV_GPUSTATUS
  (JNIEnv *env, jclass klass, jobject status_buffer)
//-----------------------------------------------------------------------------------
{
   std::stringstream ss;
   ss << "OpenCV Version " << CV_MAJOR_VERSION << "." << CV_MINOR_VERSION << std::endl;
   std::vector<cv::ocl::PlatformInfo> info;
   cv::ocl::getPlatfomsInfo(info); //sic
   cv::ocl::PlatformInfo sdk = info.at(0);
   jboolean ret = JNI_FALSE;
   if (sdk.deviceNumber()<1)
      ss << "GPU not found or not supported" << std::endl;
   else
   {
      cv::ocl::setUseOpenCL(true);
      ss << "****** GPU Support *******" << std::endl;
      ss << "Name:              " << sdk.name() << std::endl;
      ss << "Vendor:            " << sdk.vendor() << std::endl;
      ss << "Version:           " << sdk.version() << std::endl;
      ss << "Number of devices: " << sdk.deviceNumber() << std::endl;
      for (int i=0; i<sdk.deviceNumber(); i++)
      {
         cv::ocl::Device device;
         sdk.getDevice(device, i);
         ss << "Device               " << i+1 << std::endl;
         ss << "Vendor ID:           " << device.vendorID()<< std::endl;
         ss << "Vendor name:         " << device.vendorName()<< std::endl;
         ss << "Name:                " << device.name()<< std::endl;
         ss << "Driver version:      " << device.driverVersion()<< std::endl;
         ss << "Manufacturer:        "
            << ((device.isNVidia()) ? "NVidia" : ((device.isAMD()) ? "AMD" : ((device.isIntel()) ? "Intel" : "Unknown")))
            << std::endl;
         ss << "Global Memory size:  " << device.globalMemSize() << std::endl;
         ss << "Memory cache size:   " << device.globalMemCacheSize() << std::endl;
         ss << "Memory cache type:   " << device.globalMemCacheType() << std::endl;
         ss << "Local Memory size:   " << device.localMemSize() << std::endl;
         ss << "Local Memory type:   " << device.localMemType() << std::endl;
         ss << "Max Clock frequency: " << device.maxClockFrequency()<< std::endl;
         ss << std::endl;
      }
      ret = JNI_TRUE;
   }
   jclass cls = env->FindClass("java/lang/StringBuilder");
   jmethodID mid = env->GetMethodID(cls, "append","(Ljava/lang/String;)Ljava/lang/StringBuilder;");
   const char* str = ss.str().c_str();
   jstring jstr = env->NewStringUTF(str);
   env->CallObjectMethod(status_buffer, mid, jstr);
   return ret;
}
