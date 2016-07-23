#ifndef AREMUCC_PLAYER_H
#define AREMUCC_PLAYER_H

#include <cstdarg>
#include <string>
#include <vector>
#include <memory>
#include <sstream>

#include <opencv2/core/mat.hpp>

#include "KeyBuffer.h"
#include "readerwriterqueue.h"
#include "path.h"

//#define DEBUG_MAT_DELETE

class FrameListener
{
public:
   virtual void on_frame_available(bool is_RGBA_avail, bool is_mono_avail...) =0;
};

class PreviewListener
{
public:
   virtual bool on_preview_repeat(int iterno) =0;
   virtual void on_preview_complete() =0;
};


class OrientationListener
{
public:
   virtual void on_orientation_available(unsigned long timestamp, std::vector<float>& R, float x, float y, float z, float w) =0;
};

class LocationListener
{
public:
   virtual void on_location_available(unsigned long timestamp, bool is_GPS, double latitude, double longitude, double altitude,
                                      float accuracy) =0;
};


class RawSensorListener
{
public:
   /**
     * @param timestamp Timestamp of sensor event
    * @param sensor_type The Android sensor type
    * @param event The event values
    */
   virtual void on_sensor_event_available(unsigned long timestamp, int sensor_type, std::array<float, 5> event) =0;

   const int TYPE_ACCELEROMETER = 1;
   const int TYPE_MAGNETIC_FIELD = 2;
   const int TYPE_ORIENTATION = 3;
   const int TYPE_GYROSCOPE = 4;
   const int TYPE_GRAVITY = 9;
   const int TYPE_LINEAR_ACCELERATION = 10;
   const int TYPE_ROTATION_VECTOR = 11;
   const int TYPE_MAGNETIC_FIELD_UNCALIBRATED = 14;
   const int TYPE_GAME_ROTATION_VECTOR = 15;
   const int TYPE_GYROSCOPE_UNCALIBRATED = 16;
   const int TYPE_SIGNIFICANT_MOTION = 17;
   const int TYPE_STEP_DETECTOR = 18;
   const int TYPE_STEP_COUNTER = 19;
   const int TYPE_GEOMAGNETIC_ROTATION_VECTOR = 20;
};

class Player : public FrameListener, PreviewListener
//===================================================
{
public:
   Player(std::string dir) { set_dir(dir); };
   virtual void preview(bool is_monochrome_convert, int fps) =0;
   virtual moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::shared_ptr<cv::Mat>>, 512>& get_rgba_queue() =0;
   virtual moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::shared_ptr<cv::Mat>>, 512>& get_mono_queue() =0;
   virtual void stop_preview() =0;
   inline std::string messages() { return log.str(); }
   virtual long get_fps_intervalns() =0;

   virtual void on_frame_available(bool is_RGBA_avail, bool is_mono_avail...) =0;
   virtual void on_preview_complete() =0;
   virtual bool on_preview_repeat(int iterno) =0;

   virtual ~Player() {};

protected:
   void set_dir(std::string dir);

   filesystem::path directory;
   bool is_nv21 = false;
   std::stringstream log;
   int width, height, bufsize;
   volatile bool is_previewing = false, abort = false;
};

struct MatDeleter
//===============
{
   void operator()(cv::Mat* m) const
   //-------------------------------
   {
      if (m != nullptr)
      {
#ifdef DEBUG_MAT_DELETE
         std::cout << "Deleting Mat at " << static_cast<void *>(m) << std::endl;
#endif
         uchar *p = m->data;
         delete m;
         if (p != nullptr)
            delete[] p;
      }
   }
};

struct ThreadDeleter
//==================
{
   void operator()(std::thread* t) const
   //-------------------------------
   {
      if (t != nullptr)
      {
         if (t->joinable())
            t->join();
         delete t;
      }
   }
};


#endif //AREMUCC_PLAYER_H
