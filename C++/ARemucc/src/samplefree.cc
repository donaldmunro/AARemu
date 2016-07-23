#include <stdlib.h>

#include <cstdarg>
#include <cmath>
#include <iostream>
#include <iomanip>
#include <queue>
#include <condition_variable>
#include <atomic>

#include <opencv2/core/mat.hpp>
#include <opencv2/highgui/highgui.hpp>
#include <opencv2/imgproc.hpp>

#include "path.h"


#include "AREmuFreePlayer.h"
#include "samplefree.h"

std::string title;
AREmuFreePlayer *player = nullptr;
volatile bool is_running = false;
std::mutex queue_ready_mutex;
std::condition_variable queue_ready;
std::atomic<int> outstanding_frames(0);
moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::array<float, 4>>, 512> quaternion_queue;
moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::array<float, 5>>, 512> event_queue;
moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::array<double, 3>>, 512> location_queue;

void SampleFree::on_frame_available(bool is_RGBA_avail, bool is_mono_avail...)
//----------------------------------------------------------------
{
   outstanding_frames++;
   queue_ready.notify_one();
}

void SampleFree::on_preview_complete()
//------------------------------------
{
   is_running = false;
   queue_ready.notify_one();
}

void SampleFree::on_orientation_available(unsigned long timestamp, std::vector<float>& R, float x, float y, float z, float w)
//---------------------------------------------------------------------------------------------------------------------------
{
   std::array<float, 4> q{ { x, y, z, w } };
   std::pair<unsigned long, std::array<float, 4>> pp(timestamp, q);
   if (! quaternion_queue.try_enqueue(pp))
   {
      quaternion_queue.pop();
      quaternion_queue.try_enqueue(pp);
   }
}

void SampleFree::on_location_available(unsigned long timestamp, bool is_GPS, double lat, double lon, double alt, float accuracy)
//---------------------------------------------------------------------------------------------------------------------
{
   std::array<double , 3> location{ { lat, lon, alt } };
   std::pair<unsigned long, std::array<double , 3>> pp(timestamp, location);
   if (! location_queue.try_enqueue(pp))
   {
      location_queue.pop();
      location_queue.try_enqueue(pp);
   }
}

void SampleFree::on_sensor_event_available(unsigned long timestamp, int sensor_type,  std::array<float, 5> event)
//---------------------------------------------------------------------------------------------------------------
{
   std::array<float, 5> ev(event);
   std::pair<unsigned long, std::array<float, 5>> pp(timestamp, event);
   if (! event_queue.try_enqueue(pp))
   {
      event_queue.pop();
      event_queue.try_enqueue(pp);
   }
}

KeyPress process_key(long interval)
//-----------------------------
{
   KeyPress key;
   switch (cv::waitKey(static_cast<int>(interval)))
   {
      case 83:  //S
      case 115: //s
         key = SAVE;
         break;
      case 27:
         key = ESC;
         break;
      default:
         key = NONE;
         break;
   }
   return key;
}


int main(int argc, char **argv)
//-----------------------------
{
   if (argc < 2)
   {
      std::cerr << "Format: samplefree [-f framerate] {directory}" << std::endl;
      std::cerr << "-f fps = Frame rate" << std::endl;
      return 0;
   }
   int i = 1, fps = 30;
   for (; i < argc; i++)
   {
      if (argv[i][0] != '-')
         break;
      if (strcmp(argv[i], "-f") == 0)
      {
         i++;
         if (i >= argc)
            fps = -1;
         else
            try
            { fps = std::stoi(argv[i]); } catch (...)
            { fps = -1; }
         if (fps <= 0)
         {
            std::cerr << "-f requires frame rate as a positive non-zero integer eg -f 25" << std::endl;
            std::cerr.flush();
            exit(1);
         }
      }
   }

   filesystem::path dir(argv[i]);
   if (!dir.is_directory())
   {
      std::cerr << dir.str() << " not found or not a directory" << std::endl;
      return 0;
   }

   title = dir.filename();
   cv::namedWindow(title, CV_WINDOW_AUTOSIZE);
   SampleFree player(dir.str(), 5, true, true, true, true, true);
   player.preview(true, 30);
//   previewing_mutex.lock();
   moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::shared_ptr<cv::Mat>>, 512> &frame_queue = player.get_rgba_queue();
   is_running = true;
   std::stringstream ss;
   std::array<float, 4> quaternion{ { nanf(""), nanf(""), nanf(""), nanf("") } };
   std::array<float, 5> event = { { nanf(""), nanf(""), nanf(""), nanf(""), nanf("") } };
   std::array<double , 3> location = { { nanf(""), nanf(""), nanf("") } };
   long interval = player.get_fps_intervalns();
   if (interval <= 0)
      interval = 10000000L; //10ms
   long intervalms = interval / 1000000;
   while (is_running)
   {
      {
         std::unique_lock<std::mutex> lock(queue_ready_mutex);
         queue_ready.wait(lock, []()
         { return ((outstanding_frames > 0) || (!is_running)); });
         outstanding_frames--;
      }
      if (frame_queue.size_approx() > 0)
      {
         std::pair<unsigned long, std::shared_ptr<cv::Mat>> pp;
         if (frame_queue.try_dequeue(pp))
         {
            unsigned long timestamp = pp.first;
            std::shared_ptr<cv::Mat> pm = pp.second;
            cv::Mat *m = pm.get();
            if ((m != nullptr) && (!m->empty()))
            {
               if (player.notify_orientation())
               {
                  pair<unsigned long, array<float, 4>> *ppp = quaternion_queue.peek();
                  while (ppp != nullptr)
                  {
                     if ( ppp->first <= timestamp)
                     {
                        quaternion_queue.pop();
                        quaternion = ppp->second;
                     }
                     else
                        break;
                     ppp = quaternion_queue.peek();
                  }
                  ss.str("");
                  ss << "Quaternion: (" << std::fixed << std::setprecision(1) << quaternion[0] << "," << quaternion[1]
                     << "," << quaternion[2] << "," << quaternion[3] << ")";
                  cv::putText(*m, ss.str(), cvPoint(30, 30), CV_FONT_HERSHEY_COMPLEX_SMALL, 0.8, cvScalar(0, 0, 255),
                              1, CV_AA);
               }
               if (player.notify_sensor())
               {
                  pair<unsigned long, array<float, 5>> *ppp = event_queue.peek();
                  while (ppp != nullptr)
                  {
                     if ( ppp->first <= timestamp)
                     {
                        event_queue.pop();
                        event = ppp->second;
                     }
                     else
                        break;
                     ppp = event_queue.peek();
                  }
                  ss.str("");
                  ss << "Event: (" << std::fixed << std::setprecision(1) << event[0] << "," << event[1] << ","
                     << event[2] << "," << event[3] << ")";
                  cv::putText(*m, ss.str(), cvPoint(30, 50), CV_FONT_HERSHEY_COMPLEX_SMALL, 0.8, cvScalar(0, 255, 255), 1, CV_AA);
               }
               if (player.notify_location())
               {
                  pair<unsigned long, array<double, 3>> *ppp = location_queue.peek();
                  while (ppp != nullptr)
                  {
                     if ( ppp->first <= timestamp)
                     {
                        location_queue.pop();
                        location = ppp->second;
                     }
                     else
                        break;
                     ppp = location_queue.peek();
                  }
                  ss.str("");
                  ss << "Loc: (" << std::fixed << std::setprecision(1) << location[0] << "," << location[1] << ","
                     << location[2] << ")";
                  cv::putText(*m, ss.str(), cvPoint(30, 70), CV_FONT_HERSHEY_COMPLEX_SMALL, 0.8, cvScalar(0, 0, 255), 1, CV_AA);
               }
               cv::imshow(title, *m);
            }
         }
      }
      KeyPress key = process_key(intervalms);
      if (key == ESC)
      {
         player.stop_preview();
         break;
      }
   }
}
