#ifndef AREMUCC_SAMPLEFREE_H
#define AREMUCC_SAMPLEFREE_H

#include "Player.h"
#include "AREmuFreePlayer.h"

class SampleFree : public AREmuFreePlayer
//=====================================
{
public:
   SampleFree(const string &dir, size_t queue_size, bool isRepeat, bool notify_orientation, bool is_cooked_orientation,
              bool notify_location, bool notify_raw_sensor) :
   AREmuFreePlayer(dir, queue_size, isRepeat, notify_orientation, is_cooked_orientation, notify_location, notify_raw_sensor)
   {}

   virtual void on_frame_available(bool is_RGBA_avail, bool is_mono_avail...) override;
   virtual void on_orientation_available(unsigned long timestamp, std::vector<float>& R, float x, float y, float z, float w) override;
   virtual void on_location_available(unsigned long timestamp, bool is_GPS, double latitude, double longitude, double altitude,
                                      float accuracy) override ;
   virtual void on_sensor_event_available(unsigned long timestamp, int sensor_type, std::array<float, 5> event) override ;
   virtual void on_preview_complete() override;
};

#endif //AREMUCC_SAMPLE360_H
