#ifndef AREM_AREMVIDEOSOURCE_H
#define AREM_AREMVIDEOSOURCE_H

#include <sys/system_properties.h>

#include <string>
#include <unordered_map>

#define private public  // WARNING : PG X rated
#include "ARWrapper/VideoSource.h"
#include "ARWrapper/AndroidVideoSource.h"
#undef private
#include "AR/video.h"

#include "ARemController.h"

class ARemVideoSource : public AndroidVideoSource
//===============================================
{

public:
   ARemVideoSource() : AndroidVideoSource() {}
   ARemVideoSource(const std::string& format) : AndroidVideoSource() { setPixelFormat(format); }
   virtual ~ARemVideoSource() {}
   virtual const char* getName() override {  return "AREm (Android Emulation)"; }
   virtual void acceptImage(ARUint8* ptr);
   void setPixelFormat(const std::string& format);
   virtual bool captureFrame() override;
   //ARUint8* getFrame() { return frameBuffer; }
   //int getFrameStamp() { return frameStamp; }
};


#endif //AREM_AREMVIDEOSOURCE_H
