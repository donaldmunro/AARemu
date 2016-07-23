#include <math.h>
#include <cstdarg>
#include <string>
#include <iostream>
#include <fstream>
#include <iomanip>

#include <opencv2/core/core.hpp>
#include <opencv2/imgcodecs.hpp>
#include <opencv2/core/mat.hpp>
#include <opencv2/imgproc.hpp>
#include <queue>

#include "path.h"
#include "Player.h"
#include "KeyBuffer.h"
#include "AREmu360Player.h"
#include "util.h"

//#define DEBUG_FPS

AREmu360Player::AREmu360Player(std::string dir, KeyBuffer &keybuf, size_t queue_size, bool dirty_only)
               : Player(dir), key_buffer(keybuf), is_dirty_only(dirty_only)
//---------------------------------------------------------------------------------------------------------------------
{
   frames_queue = new moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::shared_ptr<cv::Mat>>, 512>(queue_size);
   bw_queue = new moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::shared_ptr<cv::Mat>>, 512>(queue_size);
   std::string name = directory.filename();
   std::string separator = directory.separator();
   filesystem::path header(directory.str() + separator + name + ".head");
   filesystem::path frames(directory.str() + separator + name + ".frames");
   filesystem::path nv21frames(directory.str() + separator + "frames.RAW");
   if (header.exists())
      read_header(header.str());
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
}

void AREmu360Player::read_header(std::string header_path)
//-------------------------------------------------------
{
   std::ifstream f(header_path.c_str(), std::ios_base::in);
   try
   {
      if (! f.good())
      {
         log << std::string("Cannot open file ") << header_path << std::endl;
         std::cerr << log.str();
         std::cerr.flush();
         increment = 1.0f;
         width = 640;
         height = 480;
         start_bearing = 0.0f;
         return;
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
         if (key == "Increment")
            try { increment = stof(value); } catch (...) { increment = 1.0f; std::cerr << "Error reading increment" << std::endl; }
         else if (key == "PreviewWidth")
            try { width = stoi(value); } catch (...) { width = 640; std::cerr << "Error reading PreviewWidth" << std::endl; }
         else if (key == "PreviewHeight")
            try { height = stoi(value); } catch (...) { height = 480; std::cerr << "Error reading PreviewHeight" << std::endl; }
         else if (key == "StartBearing")
            try { start_bearing = stof(value); } catch (...) { start_bearing = 0.0f; }
         f.getline(buf, 120);
      }
   }
   catch (...)
   {
      log << std::string("Exception reading header ") << header_path << std::endl;
      std::cerr.flush();
      std::cerr << log.str();
   }
   f.close();
}

void AREmu360Player::preview(bool is_monochrome_convert, int fps)
//---------------------------------------------------------------------------
{
   stop_preview();
   key_buffer.clear();
   thread = new std::thread(&AREmu360Player::run, std::ref(*this), is_dirty_only, is_monochrome_convert, fps);
}

void AREmu360Player::stop_preview()
//---------------------------------
{
   if (thread != nullptr)
   {
      if (is_previewing)
         key_buffer.push(TERMINATE_THREAD);
      is_previewing = false;
      thread->join();
      thread = nullptr;
   }
   else
      is_previewing = false;
}

float AREmu360Player::next_bearing(float bearing...)
//----------------------------------------------------------
{
   va_list args;
   va_start(args, bearing);
   long *offset = va_arg(args, long *);
   KeyPress key = static_cast<KeyPress>(va_arg(args, int));
   int bufsize = va_arg(args, int);
   float increment = static_cast<float>(va_arg(args, double));
   switch (key)
   {
      case RIGHT:
         bearing += increment;
         if (bearing >= 360)
         {
            bearing = bearing - 360;
            *offset = static_cast<long>(bearing / increment)*bufsize;
         }
         else
            *offset += bufsize;
         break;
      case LEFT:
         bearing -= increment;
         if (bearing < 0)
         {
            bearing += 360;
            *offset = static_cast<long>(bearing / increment)*bufsize;
         }
         else
            *offset -= bufsize;
         break;
      default:;
   }
   va_end(args);
   return bearing;
}

void AREmu360Player::run(bool is_dirty_only, bool is_monochrome_convert, int fps)
//-------------------------------------------------------------------------------
{
   fps_interval = (fps > 0) ? (1000000000L / fps) : 0;
   std::stringstream ss;
   std::fstream framesfile(frames_file.str(), std::ios_base::in);
   if (! framesfile.good())
   {
      log << "Cannot open file " << frames_file.str() << std::endl;
      throw std::runtime_error(log.str());
   }

   float bearing = start_bearing, last_bearing = -1;
   long offset = static_cast<long>(bearing / increment)*bufsize, last_offset = -1;
   is_previewing = true;
   bool is_paused = false;
   MatDeleter mat_deleter;
   std::shared_ptr<cv::Mat> last_frame(nullptr, mat_deleter), last_mono(nullptr, mat_deleter);
   auto frame_start = std::chrono::high_resolution_clock::now();
#ifdef DEBUG_FPS
   long framecount  = 0;
   long fps_display_interval = 1000000000L;
   auto fps_last_display = frame_start;
#endif
   while (is_previewing)
   {
      KeyPress key = key_buffer.pop();
      if ( (key == TERMINATE_THREAD) || (key == ESC) )
         break;
      if (is_paused)
      {
         if (key == RESUME)
            is_paused = false;
         else
         {
            std::this_thread::sleep_for(std::chrono::milliseconds(100));
            continue;
         }
      }
      bearing = next_bearing(bearing, &offset, key, bufsize, increment);
      switch (key)
      {
         case PAUSE:
            is_paused = true;
            break;
         case SAVE:
            if (last_frame)
            {
               ss.str("");
               ss << "frame-" << std::fixed << std::setprecision(1) << last_bearing << ".png";
               cv::Mat *m = last_frame.get();
               if ( (m != nullptr) && (! m->empty()) )
                  cv::imwrite(ss.str(), *m);
            }
            if ( (is_monochrome_convert) && (last_mono) )
            {
               ss.str("");
               ss << "monoframe-" << std::fixed << std::setprecision(1) << last_bearing << ".png";
               cv::Mat *m = last_mono.get();
               if ( (m != nullptr) && (! m->empty()) )
                  cv::imwrite(ss.str(), *m);
            }
            break;
         default:;
      }

      if (bearing >= 360)
      {
         offset = 0;
         bearing = 0;
      }

//      if ( (is_dirty) && (last_offset == offset) )
      bool is_rgba = false, is_mono = false;
      if (last_offset != offset)
      {
         unsigned char *data = new unsigned char[bufsize];
         framesfile.seekp(offset, std::ios_base::beg);
         framesfile.read((char *) data, bufsize);
         if ( (framesfile.bad()) || (framesfile.gcount() != bufsize) )
         {
            log << "Error reading frame at offset " << offset << " file " << frames_file.str() << std::endl;
            std::cout << "Error reading frame at offset " << offset << " file " << frames_file.str() << std::endl;
            continue;
         }

         std::shared_ptr<cv::Mat> rgba, bw;
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
         last_frame = rgba;
         if (is_monochrome_convert)
         {
            unsigned char *bw_data = new unsigned char[width * height];
            bw.reset(new cv::Mat(height, width, CV_8UC1, static_cast<void *>(bw_data)), mat_deleter);
            cv::cvtColor(*rgba, *bw, CV_RGBA2GRAY);
            last_mono = bw;
         }

         std::pair<unsigned long, std::shared_ptr<cv::Mat>> pp(0, rgba);
         bool is_queued = frames_queue->try_enqueue(pp);
         if (! is_queued)
         {
            frames_queue->pop();
            is_queued = frames_queue->try_enqueue(pp);
         }
         if (is_queued)
         {
            is_rgba = true;
            std::pair<unsigned long, std::shared_ptr<cv::Mat>> ppbw(0, bw);
            is_mono = bw_queue->try_enqueue(ppbw);
            if (! is_mono)
            {
               bw_queue->pop();
               is_mono = bw_queue->try_enqueue(ppbw);
            }
         }
         last_bearing = bearing;
      }
      else
      {
         if (is_dirty_only)
            continue;
         else
         {
            std::shared_ptr<cv::Mat> rgba, bw;
            if (last_frame)
            {
               cv::Mat *m = last_frame.get();
               if ( (m != nullptr) && (! m->empty()) )
               {
                  rgba = last_frame;
                  std::pair<unsigned long, std::shared_ptr<cv::Mat>> pp(0, rgba);
                  is_rgba = frames_queue->try_enqueue(pp);
                  if (! is_rgba)
                  {
                     frames_queue->pop();
                     is_rgba = frames_queue->try_enqueue(pp);
                  }
               }
            }
            if (last_mono)
            {
               cv::Mat *m = last_mono.get();
               if ( (m != nullptr) && (! m->empty()) )
               {
                  bw = last_mono;
                  std::pair<unsigned long, std::shared_ptr<cv::Mat>> pp(0, bw);
                  is_mono = bw_queue->try_enqueue(pp);
                  if (! is_mono)
                  {
                     bw_queue->pop();
                     is_mono = bw_queue->try_enqueue(pp);
                  }
               }
            }
         }
      }
      on_frame_available(is_rgba, is_mono, bearing);
      if (fps > 0)
      {
         auto frame_end = std::chrono::high_resolution_clock::now();
         long elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(frame_end - frame_start).count();
#ifdef DEBUG_FPS
         long display_delta = (frame_end - fps_last_display).count();
         if (display_delta >= fps_display_interval)
         {
            fps_last_display = frame_end;
            std::cout << "Notified " << framecount << " frames in " << display_delta << "ns" << std::endl;
            framecount = 1; // NB not 0
         }
         else
            framecount++;
#endif
         long dozetime = fps_interval - elapsed;
         if (dozetime > 0)
            std::this_thread::sleep_for(std::chrono::nanoseconds(dozetime));
         frame_start =std::chrono::high_resolution_clock::now();
      }
      last_offset = offset;
   }
   is_previewing = false;
   on_preview_repeat(1);
   on_preview_complete();
}

AREmu360Player::~AREmu360Player() { stop_preview(); }
