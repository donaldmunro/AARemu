#include <errno.h>
#include <string.h>

#include <iostream>
#include <fstream>
#include <vector>
#include <utility>
#include <math.h>
#include <cstdarg>
#include <queue>
#include <string>
#include <memory>
#include <iomanip>

#include <opencv2/core/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/core/mat.hpp>
#include <opencv2/imgproc.hpp>
#include <queue>

#include "path.h"
#include "Player.h"
#include "AREmuFreePlayer.h"
#include "util.h"
#include "portable_endian.h"

AREmuFreePlayer::AREmuFreePlayer(std::string dir, size_t qsize, bool isRepeat, bool notify_orientation,
                                 bool is_cooked_orientation, bool notify_location, bool notify_raw_sensor)
                : Player(dir), queue_size(qsize)
//--------------------------------------------------------------------------------------------------------
{
   frames_queue = new moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::shared_ptr<cv::Mat>>, 512>(queue_size);
   bw_queue= new moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::shared_ptr<cv::Mat>>, 512>(queue_size);
   is_repeat = isRepeat;
   std::string name = directory.filename();
   std::string separator = directory.separator();
   filesystem::path header(directory.str() + separator + name + ".head");
   filesystem::path frames(directory.str() + separator + name + ".frames");
   filesystem::path nv21frames(directory.str() + separator + "frames.RAW");
   if ( (header.exists()) && (read_header(header.str(), notify_orientation, is_cooked_orientation)) )
      header_file = header;
   if (frames.exists())
   {
      bufsize = width * height * 4;
      is_nv21 = false;
      frames_file = frames;
   }
   else if (nv21frames.exists())
   {
      bufsize = width * height * 12 / 8;;
      frames_file = nv21frames;
      is_nv21 = true;
   }
   else
   {
      log << "Frames file " << frames.str() << " must be a .frames (RGBA) or .frames.part (YUV NV21) file" << std::endl;
      std::cerr << log.str();
      std::cerr.flush();
      throw std::runtime_error(log.str());
   }

   if ( (notify_orientation) && ( (orientation_file.empty()) || (! orientation_file.exists()) ) )
   {
      filesystem::path orientation(directory.str() + separator + DEFAULT_ORIENTATION_FILE);
      filesystem::path cooked_orientation(directory.str() + separator + DEFAULT_COOKED_ORIENTATION_FILE);
      is_notify_orientation = true;
      if ( (is_cooked_orientation) && (cooked_orientation.exists()) && (cooked_orientation.file_size() > 0) )
         orientation_file = cooked_orientation;
      else if ( (orientation.exists()) && (orientation.file_size() > 0) )
         orientation_file = orientation;
      else
      {
         log << "No orientation files found or zero size. Tried " << orientation.str() << ", "
             << cooked_orientation.str() << std::endl;
         std::cerr << log.str();
         std::cerr.flush();
         is_notify_orientation = false;
      }
   }
   if (notify_location)
   {
      filesystem::path location(directory.str() + separator + DEFAULT_LOCATION_FILE);
      if ( (location.exists()) && (location.file_size() > 0) )
      {
         location_file = location;
         is_notify_location = true;
      }
      else
      {
         log << "No location files found or zero size. Tried " << location.str() << std::endl;
         std::cerr << log.str();
         std::cerr.flush();
         is_notify_location = false;
      }
   }
   if (notify_raw_sensor)
   {
      filesystem::path sensor(directory.str() + separator + DEFAULT_SENSOR_FILE);
      if ( (sensor.exists()) && (sensor.file_size() > 0) )
      {
         raw_sensor_file = sensor;
         is_notify_sensors = true;
      }
      else
      {
         log << "No raw sensor files found or zero size. Tried " << sensor.str() << std::endl;
         std::cerr << log.str();
         std::cerr.flush();
         is_notify_sensors = false;
      }
   }
}

bool AREmuFreePlayer::read_header(std::string header_path, bool notify_orientation, bool is_cooked_orientation)
//-------------------------------------------------------------------------------------------------------------
{
   std::ifstream f(header_path.c_str(), std::ios_base::in);
   try
   {
      if (! f.good())
      {
         log << std::string("Cannot open file ") << header_path << std::endl;
         std::cerr << log.str();
         std::cerr.flush();
         width = 640;
         height = 480;
         return false;
      }
      char buf[120];
      std::string key, value;
      f.getline(buf, 120);
      while (! f.eof())
      {
         std::string s(buf);
         unsigned long pos = s.find_first_of("=");
         if (pos < 0)
            continue;
         key = s.substr(0, pos);
         try { value = trim(s.substr(pos+1)); } catch (...) { value = ""; }
         if ( (key == "FilteredOrientationFile") && (notify_orientation) && (is_cooked_orientation) )
         {
            const filesystem::path& ff = filesystem::path(trim(value));
            if ( (ff.exists()) && (ff.file_size() > 0) )
            {
               orientation_file = ff;
               is_notify_orientation = true;
            }
         }
         if ( (key == "OrientationFile") && (notify_orientation) &&
              ( (orientation_file.empty()) || (! orientation_file.exists()) ) )
         {
            const filesystem::path& ff = filesystem::path(trim(value));
            if ( (ff.exists()) && (ff.file_size() > 0) )
            {
               orientation_file = ff;
               is_notify_orientation = true;
            }
         }

         if (key == "StartTime")
            try { start_time = stol(value); } catch (...) { start_time = 0; std::cerr << "Error reading StartTime" << std::endl; }
         else if (key == "PreviewWidth")
            try { width = stoi(value); } catch (...) { width = 640; std::cerr << "Error reading PreviewWidth" << std::endl; }
         else if (key == "PreviewHeight")
            try { height = stoi(value); } catch (...) { height = 480; std::cerr << "Error reading PreviewHeight" << std::endl; }
         f.getline(buf, 120);
      }
   }
   catch (...)
   {
      log << std::string("Exception reading header ") << header_path << std::endl;
      std::cerr.flush();
      std::cerr << log.str();
      return false;
   }
   f.close();
   return true;
}

void AREmuFreePlayer::stop_preview()
//----------------------------------
{
   if ( (preview_thread) && (is_previewing) )
   {
      abort = true;
      std::this_thread::sleep_for(std::chrono::milliseconds(100));
      preview_thread.reset();
//      preview_thread->join();
//      delete preview_thread;
//      preview_thread = nullptr;
   }
}

void AREmuFreePlayer::preview(bool is_monochrome_convert, int fps)
//------------------------------------------------------------------------------------
{
   stop_preview();
   preview_thread.reset(new std::thread(&AREmuFreePlayer::preview_thread_proc, std::ref(*this), is_monochrome_convert, fps));
}


void stop_tasks(std::vector<std::pair<PreviewTask *, std::thread *>> tasks)
//------------------------------------------------------------------------
{
   if (! tasks.empty())
   {
      for (std::pair<PreviewTask *, std::thread *> pp : tasks)
      {
         PreviewTask *task = pp.first;
         if (task->running())
            task->stop();
         thread *thread = pp.second;
         thread->join();
         delete task;
         delete thread;
      }
      tasks.clear();
   }
}

void AREmuFreePlayer::preview_thread_proc(bool is_monochrome_convert, int fps)
//--------------------------------------------------------------------------------------
{
   fps_interval = (fps > 0) ? (1000000000L / fps) : 0;
   is_previewing = true;
   moodycamel::ReaderWriterQueue<long, 512> *orientatation_ts_queue = nullptr, *location_ts_queue = nullptr,
         *sensor_ts_queue = nullptr;
   std::vector<std::pair<PreviewTask *, std::thread *>> tasks;

   int c = 1;
   fstream in(frames_file.str(), std::ifstream::in);
   if (! in.good())
   {
      std::cerr << "Error on open of frame file " << frames_file.str() << " (" << strerror(errno) << ")" << std::endl;
      std::cerr.flush();
   }
   std::shared_ptr<cv::Mat> last_frame, last_mono;
   try
   {
      abort = false;
      do
      {
         stop_tasks(tasks);
         tasks.clear();
         int tc = notify_orientation() + notify_location() + notify_sensor() + 1;
         Latch latch(tc);

         if (is_notify_orientation)
         {
            if (fps > 0)
            {
               OrientationQueueThread *task = new OrientationQueueThread(orientation_file.str(), *this);
               orientatation_ts_queue = task->queue();
               std::thread *thread = new std::thread(&OrientationQueueThread::exec, std::ref(*task), &latch);
               tasks.push_back(std::pair<PreviewTask *, std::thread *>(task, thread));
            }
            else
            {
               OrientationThread *task = new OrientationThread(orientation_file.str(), *this);
               std::thread *thread = new std::thread(&OrientationThread::exec, std::ref(*task), &latch);
               tasks.push_back(std::pair<PreviewTask *, std::thread *>(task, thread));
            }
         }
         if (is_notify_location)
         {
            if (fps > 0)
            {
               LocationQueueThread *task = new LocationQueueThread(location_file.str(), *this);
               location_ts_queue = task->queue();
               std::thread *thread = new std::thread(&LocationQueueThread::exec, std::ref(*task), &latch);
               tasks.push_back(std::pair<PreviewTask *, std::thread *>(task, thread));
            }
            else
            {
               LocationThread *task = new LocationThread(location_file.str(), *this);
               std::thread *thread = new std::thread(&LocationThread::exec, std::ref(*task), &latch);
               tasks.push_back(std::pair<PreviewTask *, std::thread *>(task, thread));
            }
         }
         if (is_notify_sensors)
         {
            if (fps > 0)
            {
               SensorQueueThread *task = new SensorQueueThread(raw_sensor_file.str(), *this);
               sensor_ts_queue = task->queue();
               std::thread *thread = new std::thread(&SensorQueueThread::exec, std::ref(*task), &latch);
               tasks.push_back(std::pair<PreviewTask *, std::thread *>(task, thread));
            }
            else
            {
               SensorThread *task = new SensorThread(raw_sensor_file.str(), *this);
               std::thread *thread = new std::thread(&SensorThread::exec, std::ref(*task), &latch);
               tasks.push_back(std::pair<PreviewTask *, std::thread *>(task, thread));
            }
         }
         long filepos = 0;
         bool is_duplicate;
         in.clear();
         in.seekg(filepos, std::ios_base::beg);
         unsigned long timestamp;
         int size = -1;
         std::shared_ptr<cv::Mat> rgba, bw;
         filepos = -1;
         if ( (! read_frame(in, timestamp, size, is_monochrome_convert, is_nv21, false, rgba, bw, filepos, is_duplicate))
            || (is_duplicate) )
         {
            std::cerr << "Error on initial read of frame file " << frames_file.str() << std::endl;
            std::cerr.flush();
            throw std::runtime_error("Error on initial read of frame file");
         }
         last_frame = rgba;
         last_mono = bw;
         unsigned long last_timestamp = timestamp;
         if (orientatation_ts_queue != nullptr)
         {
            if (! orientatation_ts_queue->try_enqueue(timestamp))
            {
               orientatation_ts_queue->pop();
               orientatation_ts_queue->try_enqueue(timestamp);
            }
         }
         if (location_ts_queue != nullptr)
         {
            if (! location_ts_queue->try_enqueue(timestamp))
            {
               location_ts_queue->pop();
               location_ts_queue->try_enqueue(timestamp);
            }
         }
         if (sensor_ts_queue != nullptr)
         {
            if (! sensor_ts_queue->try_enqueue(timestamp))
            {
               sensor_ts_queue->pop();
               sensor_ts_queue->try_enqueue(timestamp);
            }
         }
         std::this_thread::yield();
         latch.decrement();
         latch.wait();
         abort = false;
         auto process_start = std::chrono::high_resolution_clock::now();
         auto frame_start = process_start;
         while ((!in.eof()) && (!abort))
         {
            if (fps <= 0)
            {
               unsigned long elapsed = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                     std::chrono::high_resolution_clock::now() - process_start).count());
               unsigned long timediff = (timestamp - last_timestamp - elapsed - 1000L);// / 1000000L;
               unsigned long now, then;
               if (timediff > 0)
               {
                  now = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                        std::chrono::high_resolution_clock::now().time_since_epoch()).count());
                  then = now + timediff;
                  while ((then > now) && (!abort))
                  {
                     std::this_thread::yield();
                     now = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                           std::chrono::high_resolution_clock::now().time_since_epoch()).count());
                  }
               }
               process_start = std::chrono::high_resolution_clock::now();
            }
            bool is_rgba = false, is_mono = false;
            std::pair<unsigned long, std::shared_ptr<cv::Mat>> pp(timestamp, rgba);
            bool is_queued = frames_queue->try_enqueue(pp);
            if (! is_queued)
            {
               frames_queue->pop();
               is_queued = frames_queue->try_enqueue(pp);
            }
            if (is_queued)
            {
               is_rgba = true;
               if (is_monochrome_convert)
               {
                  std::pair<unsigned long, std::shared_ptr<cv::Mat>> ppbw(timestamp, bw);
                  is_mono = bw_queue->try_enqueue(ppbw);
                  if (! is_mono)
                  {
                     bw_queue->pop();
                     is_mono = bw_queue->try_enqueue(ppbw);
                  }
//                  last_mono = bw;
               }
            }

            if (abort) break;
            on_frame_available(is_rgba, is_mono);

            if (fps > 0)
            {
               auto frame_end = std::chrono::high_resolution_clock::now();
               long elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(frame_end - frame_start).count();
               long dozetime = fps_interval - elapsed;
               if (dozetime > 0)
                  std::this_thread::sleep_for(std::chrono::nanoseconds(dozetime));
               frame_start = std::chrono::high_resolution_clock::now();
            }

            last_timestamp = timestamp;
            filepos = -1;
            if (! read_frame(in, timestamp, size, is_monochrome_convert, is_nv21, false, rgba, bw, filepos, is_duplicate))
            {
               std::cerr << "Error on read of frame file " << frames_file.str() << std::endl;
               std::cerr.flush();
               abort = true;
               break;
            }
            if (is_duplicate)
            {
               rgba = last_frame;
               bw = last_mono;
            }
            else
            {
               last_frame = rgba;
               last_mono = bw;
            }
            if (orientatation_ts_queue != nullptr)
            {
               if (! orientatation_ts_queue->try_enqueue(timestamp))
               {
                  orientatation_ts_queue->pop();
                  orientatation_ts_queue->try_enqueue(timestamp);
               }
            }
            if (location_ts_queue != nullptr)
            {
               if (! location_ts_queue->try_enqueue(timestamp))
               {
                  location_ts_queue->pop();
                  location_ts_queue->try_enqueue(timestamp);
               }
            }
            if (sensor_ts_queue != nullptr)
            {
               if (! sensor_ts_queue->try_enqueue(timestamp))
               {
                  sensor_ts_queue->pop();
                  sensor_ts_queue->try_enqueue(timestamp);
               }
            }
         }
         if (orientatation_ts_queue != nullptr)
         {
//            int n = static_cast<int>(orientatation_ts_queue->size_approx());
//            for (int i=0; i<n; i++)
//            {
//               if (!orientatation_ts_queue->pop())
//                  break;
//            }
            orientatation_ts_queue->enqueue(-1L);
         }
         if (location_ts_queue != nullptr)
         {
//            int n = static_cast<int>(location_ts_queue->size_approx());
//            for (int i=0; i<n; i++)
//            {
//               if (!location_ts_queue->pop())
//                  break;
//            }
            location_ts_queue->enqueue(-1L);
         }
         if (sensor_ts_queue != nullptr)
         {
//            int n = static_cast<int>(sensor_ts_queue->size_approx());
//            for (int i=0; i<n; i++)
//            {
//               if (!sensor_ts_queue->pop())
//                  break;
//            }
            sensor_ts_queue->enqueue(-1L);
         }
      } while ( (on_preview_repeat(c++)) && (!abort));
      stop_tasks(tasks);
      tasks.clear();
   }
   catch (exception &e)
   {
      log << std::string("Exception in AREmuFreePlayer::preview_thread_proc") << " (" << e.what() << ")" << endl;
      std::cerr << std::string("Exception in AREmuFreePlayer::preview_thread_proc") << " (" << e.what() << ")" <<
                   std::endl << std::flush;
   }
   in.close();

   is_previewing = false;
   on_preview_complete();
}

bool AREmuFreePlayer::read_frame(fstream& in, unsigned long &timestamp, int &size, bool is_monochrome, bool is_nv21,
                                 bool is_compressed, std::shared_ptr<cv::Mat>& rgba,
                                 std::shared_ptr<cv::Mat>& bw, long& filepos, bool &was_duplicate)
//------------------------------------------------------------------------------------------------------------------
{
   if (! in.good())
      return false;
   if (filepos >= 0)
      in.seekg(filepos, std::ios_base::beg);
   uint64_t v;
   in.read((char *) &v, sizeof(uint64_t));
   if (in.eof())
      return true;
   if (! in.good())
      return false;
   timestamp = (unsigned long) be64toh(v);

   in.read((char *) &v, sizeof(uint64_t));
   if (! in.good())
      return -1;
   int sz = (int) be64toh(v);
   size = sz;
   bool ok = true;
   unsigned char *data = nullptr;
   if (size > 0)
   {
      was_duplicate = false;
      if (is_compressed)
      {
         std::cerr << "Snappy decompression not yet supported" << std::endl;
         std::cerr.flush();
         throw std::runtime_error("Snappy decompression not yet supported");
//         char* compressed = new char[size];
//         in.read(compressed, size);
//         if (! in.bad())
//         {
//            size_t len;
//            snappy::GetUncompressedLength(compressed.data(), size, len);
//            data = new unsigned char[len];
//            ok = snappy::RawUncompress(compressed, size, (char *) data);
//            if (ok) size = len;
//         }
//         delete[] compressed;
      }
      else
      {
         data = new unsigned char[size];
         in.read((char *) data, size);
         ok = ( (! in.bad()) && (in.gcount() == size) );
      }
   }
   else
   {
      was_duplicate = true;
      filepos = in.tellg();
      return true;
   }
   ok = ( (ok) && (data != nullptr) && (in.good()) );
   if (ok)
   {
      if (is_nv21)
      {
         cv::Mat nv21(height + height / 2, width, CV_8UC1, data);
         unsigned char *rgba_data = new unsigned char[width * height * 4];
         rgba.reset(new cv::Mat(height, width, CV_8UC4, static_cast<void *>(rgba_data)), mat_deleter);
         cv::cvtColor(nv21, *rgba, CV_YUV2RGBA_NV21);
         nv21.release();
         delete[] data;
         data = rgba_data;
      }
      else
         rgba.reset(new cv::Mat(height, width, CV_8UC4, data), mat_deleter);
      if (is_monochrome)
      {
         unsigned char *bw_data = new unsigned char[width * height];
         bw.reset(new cv::Mat(height, width, CV_8UC1, static_cast<void *>(bw_data)), mat_deleter);
         cv::cvtColor(*rgba, *bw, CV_RGBA2GRAY);
      }
   }
   else
   {
      rgba.reset();
      bw.reset();
   }
   filepos = in.tellg();
   return ok;
}

bool read_orientation(std::fstream& in, unsigned long& timestamp, float& x, float& y, float& z, float& w,
                      std::vector<float>& R)
//-----------------------------------------------------------------------------------------------------------------------
{
   uint64_t v;
   in.read((char *) &v, sizeof(uint64_t));
   if (! in.good())
   {
      std::cerr << " Error reading timestamp" << std::endl;
      return false;
   }
   timestamp = (unsigned long) be64toh(v);

   uint32_t v32;
   int32_t bg;
   in.read((char *) &v32, sizeof(uint32_t));
   bg = (int32_t) be32toh(v32);
   memcpy(&x, &bg, sizeof(float));

   in.read((char *) &v32, sizeof(uint32_t));
   bg = (int32_t) be32toh(v32);
   memcpy(&y, &bg, sizeof(float));

   in.read((char *) &v32, sizeof(uint32_t));
   bg = (int32_t) be32toh(v32);
   memcpy(&z, &bg, sizeof(float));

   in.read((char *) &v32, sizeof(uint32_t));
   bg = (int32_t) be32toh(v32);
   memcpy(&w, &bg, sizeof(float));

   in.read((char *) &v32, sizeof(uint32_t));
   bg = (int32_t) be32toh(v32);
   int len = static_cast<int>(bg);
   R.clear();
   float ri;
   for (int i=0; i<len; i++)
   {
      in.read((char *) &v32, sizeof(uint32_t));
      bg = (int32_t) be32toh(v32);
      memcpy(&ri, &bg, sizeof(float));
      R.push_back(ri);
   }
   return in.good();
}

void OrientationThread::exec(Latch* latch...)
//-------------------------------------------
{
   is_running = true;
   latch->decrement();
   latch->wait();
   auto process_start = std::chrono::high_resolution_clock::now();
//   auto start_time = process_start;
   std::fstream in(file.c_str(), std::ifstream::in);
   if (! in.good())
   {
      std::cerr << "error opening orientation file " << file << ": " << strerror(errno) << std::endl;
      return;
   }
   unsigned long timestamp;
   float x, y, z, w;
   std::vector<float> R;
   if (! read_orientation(in, timestamp, x, y, z, w, R))
   {
      std::cerr << "Error reading orientation file " << file << strerror(errno) << std::endl << std::flush;
      is_running = false;
      return;
   }
   unsigned long last_timestamp = timestamp;
   while ( (! in.eof()) && (! must_stop) )
   {
      unsigned long elapsed = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::high_resolution_clock::now() - process_start).count());
      unsigned long timediff = (timestamp - last_timestamp - elapsed - 1000L);// / 1000000L;
      unsigned long now, then;
      if (timediff > 0)
      {
         now = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::high_resolution_clock::now().time_since_epoch()).count());
         then = now + timediff;
         while ((then > now) && (! must_stop))
         {
            std::this_thread::yield();
            now = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                  std::chrono::high_resolution_clock::now().time_since_epoch()).count());
         }
      }
      process_start = std::chrono::high_resolution_clock::now();
      observer.on_orientation_available(timestamp, R, x, y, z, w);
      last_timestamp = timestamp;
      if (! read_orientation(in, timestamp, x, y, z, w, R))
      {
         std::cerr << "Error reading orientation file " << file << strerror(errno) << std::endl << std::flush;
         break;
      }
   }
   is_running = false;
   in.close();
}

void OrientationQueueThread::exec(Latch* latch...)
//-------------------------------------------
{
   is_running = true;
   latch->decrement();
   latch->wait();
//   auto start_time = std::chrono::high_resolution_clock::now();
   std::fstream in(file.c_str(), std::ifstream::in);
   if (! in.good())
   {
      std::cerr << "error opening orientation file " << file << ": " << strerror(errno) << std::endl;
      return;
   }
   unsigned long timestamp, next_timestamp = 0;
   float x, y, z, w;
   std::vector<float> R;
   if (! read_orientation(in, timestamp, x, y, z, w, R))
   {
      std::cerr << "Error reading orientation file " << file << strerror(errno) << std::endl << std::flush;
      is_running = false;
      return;
   }
   unsigned long last_timestamp = timestamp;
   while ( (! in.eof()) && (! must_stop) )
   {
      long ts;
      if (! timestamp_queue.try_dequeue(ts))
      {
         std::this_thread::sleep_for(std::chrono::nanoseconds(3000));
         continue;
      }
      if (ts < 0)
         break;
      next_timestamp = static_cast<unsigned long>(ts);
      while ((timestamp < next_timestamp) && (! must_stop))
      {
         auto process_start = std::chrono::high_resolution_clock::now();
         observer.on_orientation_available(timestamp, R, x, y, z, w);
         last_timestamp = timestamp;
         if (! read_orientation(in, timestamp, x, y, z, w, R))
         {
            std::cerr << "Error reading orientation file " << file << strerror(errno) << std::endl << std::flush;
            goto OrientationQueueThread_exec_terminate;
         }
         unsigned long elapsed = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::high_resolution_clock::now() - process_start).count());
         unsigned long timediff = (timestamp - last_timestamp - elapsed - 1000L);// / 1000000L;
         unsigned long now, then;
         if (timediff > 0)
         {
            now = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                  std::chrono::high_resolution_clock::now().time_since_epoch()).count());
            then = now + timediff;
            while ((then > now) && (! must_stop))
            {
               unsigned long ts;
               if (timestamp_queue.try_dequeue(ts))
                  next_timestamp = ts; // Slightly naughty - changes outer loop condition value
               std::this_thread::yield();
               now = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                     std::chrono::high_resolution_clock::now().time_since_epoch()).count());
            }
         }
      }
   }
OrientationQueueThread_exec_terminate:
   is_running = false;
   in.close();
}

bool read_location(std::fstream& in, unsigned long& timestamp, bool &isGPS, double& latitude, double& longitude,
                   double& altitude, float& accuracy)
//-----------------------------------------------------------------------------------------------------------------
{
   uint64_t v;
   in.read((char *) &v, sizeof(uint64_t));
   if (! in.good())
   {
      std::cerr << " Error reading timestamp" << std::endl;
      return false;
   }
   timestamp = (unsigned long) be64toh(v);

   char ch;
   in.read(&ch, 1);
   isGPS = (ch == 'G');

   in.read((char *) &v, sizeof(uint64_t));
   uint64_t vv = (uint64_t)  be64toh(v);
   memcpy(&latitude, &vv, sizeof(double));

   in.read((char *) &v, sizeof(uint64_t));
   vv = (uint64_t)  be64toh(v);
   memcpy(&longitude, &vv, sizeof(double));

   in.read((char *) &v, sizeof(uint64_t));
   vv = (uint64_t)  be64toh(v);
   memcpy(&altitude, &vv, sizeof(double));

   uint32_t v32;
   in.read((char *) &v32, sizeof(uint32_t));
   uint32_t vv32 = (int32_t) be32toh(v32);
   memcpy(&accuracy, &vv32, sizeof(float));
   if (! in.good())
   {
      std::cerr << " Error reading location" << std::endl;
      return false;
   }
   return in.good();
}

void LocationThread::exec(Latch* latch, ...)
//------------------------------------------
{
   is_running = true;
   latch->decrement();
   latch->wait();
   auto process_start = std::chrono::high_resolution_clock::now();
//   auto start_time = process_start;
   std::fstream in(file.c_str(), std::ifstream::in);
   if (! in.good())
   {
      std::cerr << "error opening orientation file " << file << ": " << strerror(errno) << std::endl;
      return;
   }
   unsigned long timestamp;
   bool is_GPS;
   double latitude, longitude, altitude;
   float accuracy;
   if (! read_location(in, timestamp, is_GPS, latitude, longitude, altitude, accuracy))
   {
      std::cerr << "Error reading location file " << file << strerror(errno) << std::endl << std::flush;
      is_running = false;
      return;
   }
   unsigned long last_timestamp = timestamp;
   while ( (! in.eof()) && (! must_stop) )
   {
      unsigned long elapsed = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::high_resolution_clock::now() - process_start).count());
      unsigned long timediff = (timestamp - last_timestamp - elapsed - 1000L);// / 1000000L;
      unsigned long now, then;
      if (timediff > 0)
      {
         now = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::high_resolution_clock::now().time_since_epoch()).count());
         then = now + timediff;
         while ((then > now) && (! must_stop))
         {
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
            now = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                  std::chrono::high_resolution_clock::now().time_since_epoch()).count());
         }
      }
      process_start = std::chrono::high_resolution_clock::now();
      observer.on_location_available(timestamp, is_GPS, latitude, longitude, altitude, accuracy);
      last_timestamp = timestamp;
      if (! read_location(in, timestamp, is_GPS, latitude, longitude, altitude, accuracy))
      {
         std::cerr << "Error reading location file " << file << strerror(errno) << std::endl << std::flush;
         break;
      }
   }
   is_running = false;
   in.close();
}

void LocationQueueThread::exec(Latch* latch...)
//-------------------------------------------
{
   is_running = true;
   latch->decrement();
   latch->wait();
//   auto start_time = std::chrono::high_resolution_clock::now();
   std::fstream in(file.c_str(), std::ifstream::in);
   if (! in.good())
   {
      std::cerr << "error opening orientation file " << file << ": " << strerror(errno) << std::endl;
      return;
   }
   unsigned long timestamp, next_timestamp = 0;
   bool is_GPS;
   double latitude, longitude, altitude;
   float accuracy;
   if (! read_location(in, timestamp, is_GPS, latitude, longitude, altitude, accuracy))
   {
      std::cerr << "Error reading location file " << file << strerror(errno) << std::endl << std::flush;
      is_running = false;
      return;
   }
   unsigned long last_timestamp = timestamp;
   while ( (! in.eof()) && (! must_stop) )
   {
      long ts;
      if (! timestamp_queue.try_dequeue(ts))
      {
         std::this_thread::sleep_for(std::chrono::nanoseconds(3000));
         continue;
      }
      if (ts < 0)
         break;
      next_timestamp = static_cast<unsigned long>(ts);
      while ((timestamp < next_timestamp) && (! must_stop))
      {
         auto process_start = std::chrono::high_resolution_clock::now();
         observer.on_location_available(timestamp, is_GPS, latitude, longitude, altitude, accuracy);
         last_timestamp = timestamp;
         if (! read_location(in, timestamp, is_GPS, latitude, longitude, altitude, accuracy))
         {
            std::cerr << "Error reading location file " << file << strerror(errno) << std::endl << std::flush;
            goto LocationQueueThread_exec_terminate;
         }
         unsigned long elapsed = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::high_resolution_clock::now() - process_start).count());
         unsigned long timediff = (timestamp - last_timestamp - elapsed - 1000L);// / 1000000L;
         unsigned long now, then;
         if (timediff > 0)
         {
            now = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                  std::chrono::high_resolution_clock::now().time_since_epoch()).count());
            then = now + timediff;
            while ((then > now) && (! must_stop))
            {
               unsigned long ts;
               if (timestamp_queue.try_dequeue(ts))
                  next_timestamp = ts; // Slightly naughty - changes outer loop condition value
               std::this_thread::sleep_for(std::chrono::milliseconds(200));
               now = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                     std::chrono::high_resolution_clock::now().time_since_epoch()).count());
            }
         }
      }
   }
LocationQueueThread_exec_terminate:
   is_running = false;
   in.close();
}

bool read_sensor(std::fstream& in, unsigned long& timestamp, int &type, std::array<float, 5>& event)
//-----------------------------------------------------------------------------------------------------------------
{
   uint64_t v;
   uint32_t v32;
   in.read((char *) &v32, sizeof(uint32_t));
   type = (int) be32toh(v32);

   if (! in.good())
   {
      std::cerr << " Error reading timestamp" << std::endl;
      return false;
   }

   in.read((char *) &v, sizeof(uint64_t));
   timestamp = (unsigned long) be64toh(v);

   for (int i=0; i<5; i++)
   {
      in.read((char *) &v32, sizeof(uint32_t));
      uint32_t vv32 = (int32_t) be32toh(v32);
      memcpy(&event[i], &vv32, sizeof(float));
   }
   if (! in.good())
   {
      std::cerr << " Error reading location" << std::endl;
      return false;
   }
   return in.good();
}


void SensorThread::exec(Latch* latch...)
//--------------------------------------
{
   is_running = true;
   latch->decrement();
   latch->wait();
   auto process_start = std::chrono::high_resolution_clock::now();
//   auto start_time = process_start;
   std::fstream in(file.c_str(), std::ifstream::in);
   if (! in.good())
   {
      std::cerr << "error opening sensor file " << file << ": " << strerror(errno) << std::endl;
      return;
   }

   //skip header
   uint32_t v32;
   in.read((char *) &v32, sizeof(uint32_t));
   int len = (int) be32toh(v32);
   for (int i = 0; i < len; i++)
      in.read((char *) &v32, sizeof(uint32_t));
   if (! in.good())
   {
      std::cerr << "error reading sensor file header: " << file << ": " << strerror(errno) << std::endl;
      return;
   }

   unsigned long timestamp;
   int type;
   std::array<float, 5> event;
   if (! read_sensor(in, timestamp, type, event))
   {
      std::cerr << "Error reading sensor file " << file << strerror(errno) << std::endl << std::flush;
      is_running = false;
      return;
   }
   unsigned long last_timestamp = timestamp;
   while ( (! in.eof()) && (! must_stop) )
   {
      unsigned long elapsed = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::high_resolution_clock::now() - process_start).count());
      unsigned long timediff = (timestamp - last_timestamp - elapsed - 1000L);// / 1000000L;
      unsigned long now, then;
      if ( (timediff > 0) && (! must_stop))
      {
         now = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::high_resolution_clock::now().time_since_epoch()).count());
         then = now + timediff;
         while ((then > now) && (! must_stop))
         {
            std::this_thread::yield();
            now = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                  std::chrono::high_resolution_clock::now().time_since_epoch()).count());
         }
      }
      process_start = std::chrono::high_resolution_clock::now();
      observer.on_sensor_event_available(timestamp, type, event);
      last_timestamp = timestamp;
      if (! read_sensor(in, timestamp, type, event))
      {
         std::cerr << "Error reading sensor file " << file << strerror(errno) << std::endl << std::flush;
         break;
      }
   }
   is_running = false;
   in.close();
}

void SensorQueueThread::exec(Latch* latch...)
//-------------------------------------------
{
   is_running = true;
   latch->decrement();
   latch->wait();
//   auto start_time = std::chrono::high_resolution_clock::now();
   std::fstream in(file.c_str(), std::ifstream::in);
   if (! in.good())
   {
      std::cerr << "error opening orientation file " << file << ": " << strerror(errno) << std::endl;
      return;
   }
   //skip header
   uint32_t v32;
   in.read((char *) &v32, sizeof(uint32_t));
   int len = (int) be32toh(v32);
   for (int i = 0; i < len; i++)
      in.read((char *) &v32, sizeof(uint32_t));
   if (! in.good())
   {
      std::cerr << "error reading sensor file header: " << file << ": " << strerror(errno) << std::endl;
      return;
   }

   unsigned long timestamp, next_timestamp;
   int type;
   std::array<float, 5> event;
   if (! read_sensor(in, timestamp, type, event))
   {
      std::cerr << "Error reading sensor file " << file << strerror(errno) << std::endl << std::flush;
      is_running = false;
      return;
   }
   unsigned long last_timestamp = timestamp;
   while ( (! in.eof()) && (! must_stop) )
   {
      long ts;
      if (! timestamp_queue.try_dequeue(ts))
      {
         std::this_thread::sleep_for(std::chrono::nanoseconds(3000));
         continue;
      }
      if (ts < 0)
         break;
      next_timestamp = static_cast<unsigned long>(ts);
      while ((timestamp < next_timestamp) && (! must_stop))
      {
         auto process_start = std::chrono::high_resolution_clock::now();
         observer.on_sensor_event_available(timestamp, type, event);
         last_timestamp = timestamp;
         if (! read_sensor(in, timestamp, type, event))
         {
            std::cerr << "Error reading sensor file " << file << strerror(errno) << std::endl << std::flush;
            goto SensorQueueThread_exec_terminate;
         }
         unsigned long elapsed = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::high_resolution_clock::now() - process_start).count());
         unsigned long timediff = (timestamp - last_timestamp - elapsed - 1000L);// / 1000000L;
         unsigned long now, then;
         if (timediff > 0)
         {
            now = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                  std::chrono::high_resolution_clock::now().time_since_epoch()).count());
            then = now + timediff;
            while ((then > now) && (! must_stop))
            {
               unsigned long ts;
               if (timestamp_queue.try_dequeue(ts))
                  next_timestamp = ts; // Slightly naughty - changes outer loop condition value
               std::this_thread::yield();
               now = static_cast<unsigned long>(std::chrono::duration_cast<std::chrono::nanoseconds>(
                     std::chrono::high_resolution_clock::now().time_since_epoch()).count());
            }
         }
      }
   }
SensorQueueThread_exec_terminate:
   is_running = false;
   in.close();
}

//
//void AREmuFreePlayer::stop_preview()
////---------------------------------
//{
//   if (thread != nullptr)
//   {
//      is_previewing = false;
//      thread->join();
//      thread = nullptr;
//   }
//   else
//      is_previewing = false;
//}
//
//void AREmuFreePlayer::run(bool is_monochrome_convert, int fps)
////-------------------------------------------------------------
//{
//   uchar buf1[bufsize];
//   std::stringstream ss;
//   std::fstream framesfile(frames_file.str(), std::ios_base::in);
//   if (! framesfile.good())
//   {
//      log << "Cannot open file " << frames_file.str() << std::endl;
//      throw std::runtime_error(log.str());
//   }
//
//   is_previewing = true;
//   std::shared_ptr<cv::Mat> last_frame, last_mono;
//   while (is_previewing)
//   {
//      bool is_rgba = false, is_mono = false;
//      on_frame_available(is_rgba, is_mono);
//   }
//   is_previewing = false;
//   on_preview_complete();
//}
//
//AREmuFreePlayer::~AREmuFreePlayer()
////-------------------------------
//{
//   stop_preview();
//}
