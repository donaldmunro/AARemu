#include <string>

#define private public
#include "ARWrapper/VideoSource.h"
#undef private
#include "ARemVideoSource.h"

#include "VideoSourceFactory.h"

VideoSource *VideoSourceFactory::newVideoSource(const char *device, ...)
//---------------------------------------------------------------
{
   va_list args;
   va_start(args, device);
   std::string s(device);

   if ( (s == "") || (s == "android") )
   {
      va_end(args);
      return VideoSource::newVideoSource();
   }
   else if ( (s == "arem") || (s == "aremu") )
   {
      const char * psz = va_arg(args, const char *);
      if (psz == nullptr)
         s = "nv21";
      else
         s = std::string(psz);
      return new ARemVideoSource(s);
   }
   return nullptr;

}

