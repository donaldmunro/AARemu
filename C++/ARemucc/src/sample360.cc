#include <stdlib.h>

#include <cstdarg>
#include <iostream>
#include <iomanip>
#include <queue>
#include <condition_variable>
#include <atomic>

#include <opencv2/core/mat.hpp>
#include <opencv2/highgui/highgui.hpp>
#include <opencv2/imgproc.hpp>

#include "path.h"
#include "sample360.h"

std::string title;
AREmu360Player *player = nullptr;
float last_bearing = 0;
volatile bool is_running = false;
KeyBuffer keyboard_buffer(500);
std::mutex queue_ready_mutex;
std::condition_variable queue_ready;
std::atomic<int> outstanding_frames(0);

void process_key(long interval)
//-----------------------------
{
   KeyPress key;
   switch (cv::waitKey(static_cast<int>(interval)))
   {
      case 65363: // right arrow
      case 46:    // >
         key = RIGHT;
         break;
      case 65361: //left arrow
      case 44:    // <
         key = LEFT;
         break;
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
   if (key != NONE)
      keyboard_buffer.push(key);
}

void Sample360::on_frame_available(bool isRGBA_avail, bool ismono_avail...)
//----------------------------------------------------------------------------
{
   va_list args;
   va_start(args, ismono_avail);
   last_bearing = static_cast<float>(va_arg(args, double));
   outstanding_frames++;
   queue_ready.notify_one();
   va_end(args);
}

void Sample360::on_preview_complete()
//-----------------------------------
{
   is_running = false;
   queue_ready.notify_one();
}

int main(int argc, char **argv)
//-----------------------------
{
   if (argc < 2)
   {
      std::cerr << "Format: sample360 [-d] [-f framerate] {directory}" << std::endl;
      std::cerr << "-d = Dirty mode (Only do callback if frame changed)" << std::endl;
      std::cerr << "-f fps = Frame rate" << std::endl;
      return 0;
   }
   bool is_dirty = false;
   int i = 1, fps = 30;
   for (; i < argc; i++ )
   {
      if (argv[i][0] != '-')
         break;
      if (strcmp(argv[i], "-d") == 0)
         is_dirty = true;
      if (strcmp(argv[i], "-f") == 0)
      {
         i++;
         if (i >= argc)
            fps = -1;
         else
            try { fps = std::stoi(argv[i]); } catch (...) { fps = -1; }
         if (fps <= 0)
         {
            std::cerr << "-f requires frame rate as a positive non-zero integer eg -f 25" << std::endl;
            std::cerr.flush();
            exit(1);
         }
      }
   }

   filesystem::path dir(argv[i]);
   if (! dir.is_directory())
   {
      std::cerr << dir.str() << " not found or not a directory" << std::endl;
      return 0;
   }

   title = dir.filename();
   cv::namedWindow(title, CV_WINDOW_AUTOSIZE);
   Sample360 player(dir.str(), keyboard_buffer, 5, is_dirty);
   player.preview(true, 30);
//   previewing_mutex.lock();
   moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::shared_ptr<cv::Mat>>, 512>& frame_queue = player.get_rgba_queue();
   is_running = true;
   long interval = player.get_fps_intervalns() >> 1;
   if (interval <= 0)
      interval = 10000000L; //10ms
   long intervalms = interval / 1000000;
   while (is_running)
   {
      {
         std::unique_lock<std::mutex> lock(queue_ready_mutex);
         if (queue_ready.wait_for(lock,  std::chrono::nanoseconds(interval),
                                  []() { return ( (outstanding_frames > 0) || (! is_running) ); }))
            outstanding_frames--;
         else
         {
            process_key(intervalms);
            continue;
         }
      }
      if (frame_queue.size_approx() > 0)
      {
         std::pair<unsigned long, std::shared_ptr<cv::Mat>> pp;
         if (frame_queue.try_dequeue(pp))
         {
            std::shared_ptr<cv::Mat> pm = pp.second;
//            auto del_p = std::get_deleter<void(*)(cv::Mat*)>(pm);
//            if (del_p) std::cout << "shared_ptr owns a deleter\n";
            cv::Mat *m = pm.get();
            if ( (m != nullptr) && (! m->empty()) )
            {
//               std::cout << "Displaying Mat at " << static_cast<void *>(m) << std::endl;
               std::stringstream ss;
               ss << std::fixed << std::setprecision(1) << last_bearing;
               cv::putText(*m, ss.str(), cvPoint(30, 30), CV_FONT_HERSHEY_COMPLEX_SMALL, 0.8, cvScalar(0, 0, 255), 1, CV_AA);
               cv::imshow(title, *m);
               //pm.reset(); Don't reset otherwise it loses deleter
            }
         }
      }
      process_key(intervalms);
   }
   std::cout << "Done" << std::endl;
   exit(0);
}
