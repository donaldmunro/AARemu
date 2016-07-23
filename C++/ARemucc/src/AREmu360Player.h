#ifndef AREMUCC_AREMU360PLAYER_H
#define AREMUCC_AREMU360PLAYER_H

#include <string>
#include <memory>

#include "Player.h"

class AREmu360Player : public Player
//==================================
{
public:
   AREmu360Player(std::string dir, KeyBuffer &keybuf, size_t queue_size, bool is_dirty_only =false);
   virtual ~AREmu360Player();
   virtual void preview(bool is_monochrome_convert, int fps) override;
   virtual moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::shared_ptr<cv::Mat>>, 512>& get_rgba_queue() { return *frames_queue; }
   virtual moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::shared_ptr<cv::Mat>>, 512>& get_mono_queue() { return *bw_queue; }

   virtual void on_frame_available(bool is_RGBA_avail, bool is_mono_avail...) =0;
   virtual void on_preview_complete() =0;
   virtual bool on_preview_repeat(int iterno) override { return false; };

   virtual float next_bearing(float bearing...);

   virtual long get_fps_intervalns() override { return fps_interval; }
protected:
   virtual void stop_preview() override;

   float increment;
   float start_bearing = 0;
   volatile long fps_interval = -1;
   KeyBuffer &key_buffer;

protected:
   void read_header(std::string path);
   void run(bool is_dirty_only, bool is_monochrome_convert, int fps);

   filesystem::path header_file, frames_file;
   bool is_dirty_only;
   std::thread *thread = nullptr;
   moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::shared_ptr<cv::Mat>>, 512> *frames_queue, *bw_queue;
};

#endif //AREMUCC_AREMU360PLAYER_H
