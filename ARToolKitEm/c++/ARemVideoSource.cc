#ifdef ANDROID_LOG
#include <android/log.h>
#define  LOG_TAG    "ARemVideoSource"
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

#define private public
#define protected public
#include "ARWrapper/VideoSource.h"
#include "ARWrapper/AndroidVideoSource.h"
#undef private
#undef protected

#include "ARemVideoSource.h"

void ARemVideoSource::acceptImage(ARUint8* ptr)
//--------------------------------------------
{
   if (deviceState == DEVICE_RUNNING)
   {
      if (pixelFormat == AR_PIXEL_FORMAT_NV21 || pixelFormat == AR_PIXEL_FORMAT_420f)
         ; // Nothing more to do.
       else if (ptr && pixelFormat == AR_PIXEL_FORMAT_RGBA)
      {
         if (frameBufferSize != videoWidth * videoHeight * 4)
         {
//            if (localFrameBuffer != NULL) free(localFrameBuffer);
            frameBufferSize = (size_t) (videoWidth * videoHeight * 4);
            localFrameBuffer = (ARUint8*) calloc(frameBufferSize, sizeof(ARUint8));
            frameBuffer = localFrameBuffer;
         }
         memcpy(localFrameBuffer, ptr, frameBufferSize);
//         color_convert_common((unsigned char*)ptr, (unsigned char*)(ptr + videoWidth * videoHeight), videoWidth, videoHeight, localFrameBuffer);
     }
       else
         return;
      auto p1 = &VideoSource::frameStamp;
      auto p2 = &frameStamp;
      frameStamp++;
      newFrameArrived = true;
   }
}

bool ARemVideoSource::captureFrame()
{
   if (deviceState == DEVICE_RUNNING) {

      if (AndroidVideoSource::newFrameArrived)
      {
         AndroidVideoSource::newFrameArrived = false;
         return true;
      }
   }

   return false;
}


void ARemVideoSource::setPixelFormat(const std::string& format)
//------------------------------------------------------------
{
   std::string form = ARemController::trim(format);
   std::transform(form.begin(), form.end(), form.begin(), ::tolower);
   if (form == "rgba")
      pixelFormat = AR_PIXEL_FORMAT_RGBA;
   else //if (form == "nv21")
      pixelFormat = AR_PIXEL_FORMAT_NV21;
//   else if (form == "yuv420")
//      pixelFormat = AR_PIXEL_FORMAT_??;
}




