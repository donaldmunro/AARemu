#ifndef VIDEOSOURCEFACTORY_H
#define VIDEOSOURCEFACTORY_H

#include <cstdarg>

class VideoSourceFactory
//======================
{
public:
   VideoSource *newVideoSource(const char *param, ...);
};
#endif
