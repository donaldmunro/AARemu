#ifndef AREMUCC_SAMPLE360_H
#define AREMUCC_SAMPLE360_H

#include "Player.h"
#include "AREmu360Player.h"

class Sample360 : public AREmu360Player
//=====================================
{
public:
   Sample360(const std::string &dir, KeyBuffer &keybuf, size_t qsize, bool is_dirty_only =false)
      : AREmu360Player(dir, keybuf, qsize, is_dirty_only) {};

   virtual void on_frame_available(bool is_RGBA_avail, bool is_mono_avail...) override;
   virtual void on_preview_complete() override;
};

#endif //AREMUCC_SAMPLE360_H
