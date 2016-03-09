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
#include <opencv2/imgproc/imgproc.hpp>

#ifndef DESKTOP
#define  LOG_TAG    "framediff.so"
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)
#endif

//#define NDEBUG
//#include <assert.h>

JNIEXPORT jdouble JNICALL Java_to_augmented_reality_android_em_recorder_CV_PSNR
      (JNIEnv *env, jclass klass, jint width, jint height, jobject image1, jobject image2)
//------------------------------------------------------------------------------------------------
{
   double ret = 0;
//   jboolean is_copy;
//   uchar* img1 = (uchar*) env->GetByteArrayElements(image1, &is_copy);
//   #ifndef DESKTOP
//   LOGI("Image1 copy %d", is_copy);
//   #endif   
//   uchar* img2 = (uchar*) env->GetByteArrayElements(image2, &is_copy);
//   LOGI("Image2 copy %d", is_copy);
//   jsize len1 = env->GetArrayLength(image1);
//   jsize len2 = env->GetArrayLength(image2);

   uchar* img1 = (uchar*) env->GetDirectBufferAddress(image1);
   uchar* img2 = (uchar*) env->GetDirectBufferAddress(image2);
//   jsize len1 = env->GetDirectBufferCapacity(image1);
//   jsize len2 = env->GetDirectBufferCapacity(image2);

   cv::Mat nv21(height+height/2, width, CV_8UC1, img1);
   cv::Mat rgba1(height, width, CV_8UC4), rgba2(height, width, CV_8UC4);
   cv::cvtColor(nv21, rgba1, CV_YUV2RGBA_NV21);
   nv21 = cv::Mat(height+height/2, width, CV_8UC1, img2);
   cv::cvtColor(nv21, rgba2, CV_YUV2RGBA_NV21);

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
//   env->ReleaseByteArrayElements(image1, (jbyte*) img1, JNI_ABORT);
//   env->ReleaseByteArrayElements(image2, (jbyte*) img2, JNI_ABORT);
   return ret;
}

JNIEXPORT jdouble JNICALL Java_to_augmented_reality_android_em_recorder_CV_MSSIM
      (JNIEnv *env, jclass klass, jint width, jint height, jobject image1, jobject image2)
//-----------------------------------------------------------
{
//   jboolean is_copy;
//   uchar* img1 = (uchar*) env->GetByteArrayElements(image1, &is_copy);
//   LOGI("Image1 copy %d", is_copy);
//   uchar* img2 = (uchar*) env->GetByteArrayElements(image2, &is_copy);
//   LOGI("Image2 copy %d", is_copy);
//   jsize len1 = env->GetArrayLength(image1);
//   jsize len2 = env->GetArrayLength(image2);

   uchar* img1 = (uchar*) env->GetDirectBufferAddress(image1);
   uchar* img2 = (uchar*) env->GetDirectBufferAddress(image2);
//   jsize len1 = env->GetDirectBufferCapacity(image1);
//   jsize len2 = env->GetDirectBufferCapacity(image2);

   const double C1 = 6.5025, C2 = 58.5225;
   int d     = CV_32F;
   cv::Mat nv21(height+height/2, width, CV_8UC1, img1);
   cv::Mat rgba1(height, width, CV_8UC4), rgba2(height, width, CV_8UC4);
   cv::cvtColor(nv21, rgba1, CV_YUV2RGBA_NV21);
   nv21 = cv::Mat(height+height/2, width, CV_8UC1, img2);
   cv::cvtColor(nv21, rgba2, CV_YUV2RGBA_NV21);

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

JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_CV_SHIFT
  (JNIEnv *env, jclass klass, jint width, jint height, jobject image1, jobject image2, jintArray result)
//---------------------------------------------------------------------------------------------------------  
{   
   uchar* img1 = (uchar*) env->GetDirectBufferAddress(image1);
   uchar* img2 = (uchar*) env->GetDirectBufferAddress(image2);
//   jsize len1 = env->GetDirectBufferCapacity(image1);
//   jsize len2 = env->GetDirectBufferCapacity(image2);
   jint* result_arr = env->GetIntArrayElements(result, NULL);
   try
   {   
      cv::Mat nv21(height+height/2, width, CV_8UC1, img1);
      cv::Mat grey1(height, width, CV_8UC1), grey2(height, width, CV_8UC1);
      cv::cvtColor(nv21, grey1, cv::COLOR_YUV2GRAY_420);
      nv21 = cv::Mat(height+height/2, width, CV_8UC1, img2);
      cv::cvtColor(nv21, grey2, cv::COLOR_YUV2GRAY_420);
      
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
   catch (std::exception &e)
   {
      std::cerr << e.what() << std::endl;
      result_arr[0] = result_arr[1] = std::numeric_limits<int>::min(); 
   }
   
   env->ReleaseIntArrayElements(result, result_arr, 0);
}

/*
JNIEXPORT void JNICALL Java_to_augmented_reality_android_em_recorder_CV_SHIFT
  (JNIEnv *env, jclass klass, jint width, jint height, jobject image1, jobject image2, jintArray result)
//---------------------------------------------------------------------------------------------------------  
{
   uchar* img1 = (uchar*) env->GetDirectBufferAddress(image1);
   uchar* img2 = (uchar*) env->GetDirectBufferAddress(image2);
//   jsize len1 = env->GetDirectBufferCapacity(image1);
//   jsize len2 = env->GetDirectBufferCapacity(image2);
   jint* result_arr = env->GetIntArrayElements(result, NULL);
   
   cv::Mat nv21(height+height/2, width, CV_8UC1, img1);
   cv::UMat grey1(height, width, CV_8UC1), grey2(height, width, CV_8UC1);
   cv::cvtColor(nv21, grey1, cv::COLOR_YUV2GRAY_420);
   nv21 = cv::Mat(height+height/2, width, CV_8UC1, img2);
   cv::cvtColor(nv21, grey2, cv::COLOR_YUV2GRAY_420);
   
   int dftwidth = cv::getOptimalDFTSize(std::max(grey1.cols, grey2.cols));
   int dftheight = cv::getOptimalDFTSize(std::max(grey1.rows, grey2.rows));
   cv::UMat fft1(cv::Size(dftwidth, dftheight), CV_32F, cv::Scalar(0));
   cv::UMat fft2(cv::Size(dftwidth, dftheight), CV_32F, cv::Scalar(0));
  
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
   
   env->ReleaseIntArrayElements(result, result_arr, 0);
}
*/

JNIEXPORT jboolean JNICALL Java_to_augmented_reality_android_em_recorder_CV_KLUDGE
  (JNIEnv *env, jclass klass, jint width, jint height, jobject image, jint shift, jboolean is_right, jobject image_out)
//------------------------------------------------------------------------------------------------------------------
{
   try
   {
      uchar* img = (uchar*) env->GetDirectBufferAddress(image);
      cv::Mat nv21(height+height/2, width, CV_8UC1, img);
      cv::Mat rgba(height, width, CV_8UC4);
      cv::cvtColor(nv21, rgba, CV_YUV2RGBA_NV21);
      
      uchar* imgout = (uchar*) env->GetDirectBufferAddress(image_out);
      jsize outlen = env->GetDirectBufferCapacity(image_out);
      int len = rgba.step * rgba.rows;   
      //fprintf(stdout, "%d %d\n", outlen, len); fflush(stdout);
      if (outlen < len)
         return JNI_FALSE;
      //cv::Mat out = cv::Mat::zeros(rgba.size(), rgba.type());
      cv::Mat out = cv::Mat(height, width, CV_8UC4, (void *) imgout);
      rgba.copyTo(out);
      //assert(out.isContinuous());
      if (is_right)
         rgba(cv::Rect(0, 0, rgba.cols-shift, rgba.rows)).copyTo(out(cv::Rect(shift, 0, rgba.cols-shift, rgba.rows)));
      else
         rgba(cv::Rect(shift, 0, rgba.cols-shift, rgba.rows)).copyTo(out(cv::Rect(0, 0, rgba.cols-shift, rgba.rows)));
   //   cv::imwrite("/tmp/rgba.png", rgba);
   //   cv::imwrite("/tmp/out.png",  out);                  
         
   //   memcpy((void *) imgout, (void *) out.data, len);
      return JNI_TRUE;
   }
   catch (std::exception &e)
   {
      std::cerr << e.what() << std::endl;
      return JNI_FALSE;
   }
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

/*
void cvtRGBAtoNV21(int *rgba, int width, int height, uchar *yuv420sp)
//---------------------------------------------------------------------
{
   const int frameSize = width * height;
   int R, G, B, Y, U, V, index = 0, yIndex = 0, uvIndex = frameSize;
   for (int j = 0; j < height; j++) 
   {
      for (int i = 0; i < width; i++) 
      {
         R = (rgba[index] & 0xff000000) >> 24;
         G = (rgba[index] & 0xff0000) >> 16;
         B = (rgba[index] & 0xff00) >> 8;

         Y = ( (  66 * R + 129 * G +  25 * B + 128) >> 8) +  16;
         U = ( ( -38 * R -  74 * G + 112 * B + 128) >> 8) + 128;
         V = ( ( 112 * R -  94 * G -  18 * B + 128) >> 8) + 128;

         yuv420sp[yIndex++] = (uchar) ((Y < 0) ? 0 : ((Y > 255) ? 255 : Y));
         if ( ((j % 2) == 0) && ((index % 2) == 0) )
         { 
            yuv420sp[uvIndex++] = (uchar)((V<0) ? 0 : ((V > 255) ? 255 : V));
            yuv420sp[uvIndex++] = (uchar)((U<0) ? 0 : ((U > 255) ? 255 : U));
         }
         index++;
      }
   }
}
*/
