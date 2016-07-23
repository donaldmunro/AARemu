#ifndef AREMUCC_AREMUFREEPLAYER_H
#define AREMUCC_AREMUFREEPLAYER_H

#include <string>
#include <memory>

#include "Player.h"
#include "path.h"
#include "Latch.h"

class AREmuFreePlayer : public Player, OrientationListener, LocationListener, RawSensorListener
//=============================================================================================
{
public:
   AREmuFreePlayer(std::string dir, size_t qsize, bool isRepeat, bool notify_orientation, bool is_cooked_orientation,
                   bool notify_location, bool notify_raw_sensor);
   virtual ~AREmuFreePlayer() {};
   virtual void preview(bool is_monochrome_convert, int fps) override;
   virtual void stop_preview() override;
   virtual moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::shared_ptr<cv::Mat>>, 512>& get_rgba_queue() { return *frames_queue; }
   virtual moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::shared_ptr<cv::Mat>>, 512>& get_mono_queue() { return *bw_queue; }
   virtual long get_fps_intervalns() override { return fps_interval; }

   virtual void on_frame_available(bool is_RGBA_avail, bool is_mono_avail...) =0;
   virtual void on_orientation_available(unsigned long timestamp, std::vector<float>& R, float x, float y, float z, float w) =0;
   virtual void on_location_available(unsigned long timestamp, bool is_GPS, double latitude, double longitude, double altitude,
                                      float accuracy) =0;
   virtual void on_sensor_event_available(unsigned long timestamp, int sensor_type, std::array<float, 5> event) =0;
   virtual void on_preview_complete() =0;
   virtual bool on_preview_repeat(int iterno) { return is_repeat; }

   inline bool notify_orientation() { return is_notify_orientation; }
   inline bool notify_location() { return is_notify_location; }
   inline bool notify_sensor() { return is_notify_sensors; }

   const std::string DEFAULT_ORIENTATION_FILE = "orientation";
   const std::string DEFAULT_COOKED_ORIENTATION_FILE = "orientation.smooth";
   const std::string DEFAULT_LOCATION_FILE = "location";
   const std::string DEFAULT_SENSOR_FILE = "sensordata.raw";

protected:
   bool read_header(std::string path, bool notify_orientation, bool is_cooked_orientation);
   virtual bool read_frame(fstream& in, unsigned long &timestamp, int &size, bool is_monochrome, bool is_nv21,
                           bool is_compressed, std::shared_ptr<cv::Mat>& rgba, std::shared_ptr<cv::Mat>& bw,
                           long& filepos, bool &was_duplicate);
   virtual void preview_thread_proc(bool is_monochrome_convert, int fps);

   filesystem::path header_file, frames_file, orientation_file, location_file, raw_sensor_file;
   bool is_repeat = false, is_notify_orientation = false, is_notify_location = false, is_notify_sensors = false;
   int queue_size;
   long start_time;
   volatile long fps_interval = -1;
   //std::thread *preview_thread = nullptr;
   std::unique_ptr<std::thread, ThreadDeleter> preview_thread;
   moodycamel::ReaderWriterQueue<std::pair<unsigned long, std::shared_ptr<cv::Mat>>, 512> *frames_queue, *bw_queue;
   MatDeleter mat_deleter;
};

class PreviewTask
//===============
{
public:
   PreviewTask(std::string filename) : file(filename) {}
   virtual void exec(Latch* latch...) =0;
   virtual moodycamel::ReaderWriterQueue<long, 512>* queue() { return nullptr; };
   virtual inline bool running() { return is_running; }
   virtual inline void stop() { must_stop = true; }

   virtual ~PreviewTask() { }

protected:
   std::string file;
   volatile bool is_running = false, must_stop = false;
};

class OrientationThread : public PreviewTask
//==========================================
{
public:
   OrientationThread(std::string filepath, OrientationListener& listener) : PreviewTask(filepath), observer(listener) { }

   virtual void exec(Latch* latch...) override;

   virtual ~OrientationThread() { must_stop = true; }

private:
   OrientationListener& observer;
};


class OrientationQueueThread : public PreviewTask
//===============================================
{
public:
   OrientationQueueThread(std::string filepath, OrientationListener& listener) : PreviewTask(filepath), observer(listener) {}

   virtual moodycamel::ReaderWriterQueue<long, 512>* queue() override { return &timestamp_queue; };
   virtual void exec(Latch* latch...) override;
   virtual void stop() override { must_stop = true; timestamp_queue.enqueue(-1); }

   virtual ~OrientationQueueThread() { stop(); }

private:
   OrientationListener& observer;
   moodycamel::ReaderWriterQueue<long, 512> timestamp_queue{30};
};

class LocationThread : public PreviewTask
//==========================================
{
public:
   LocationThread(std::string filepath, LocationListener& listener) : PreviewTask(filepath), observer(listener) { }
   virtual void exec(Latch* latch...) override;

   virtual ~LocationThread() { must_stop = true; }

private:
   LocationListener& observer;
};


class LocationQueueThread : public PreviewTask
//===============================================
{
public:
   LocationQueueThread(std::string filepath, LocationListener& listener) : PreviewTask(filepath), observer(listener) {}

   virtual moodycamel::ReaderWriterQueue<long, 512>* queue() override { return &timestamp_queue; };
   virtual void exec(Latch* latch...) override;
   virtual void stop() override { must_stop = true; timestamp_queue.enqueue(-1); }

   ~LocationQueueThread() { stop(); }

private:
   LocationListener& observer;
   moodycamel::ReaderWriterQueue<long, 512> timestamp_queue{30};
};

class SensorThread : public PreviewTask
//==========================================
{
public:
   SensorThread(std::string filepath, RawSensorListener& listener) : PreviewTask(filepath), observer(listener) { }
   virtual void exec(Latch* latch...) override;

   ~SensorThread() { must_stop = true; }

private:
   RawSensorListener& observer;
};


class SensorQueueThread : public PreviewTask
//===============================================
{
public:
   SensorQueueThread(std::string filepath, RawSensorListener& listener) : PreviewTask(filepath), observer(listener) {}

   virtual moodycamel::ReaderWriterQueue<long, 512>* queue() override { return &timestamp_queue; };
   virtual void exec(Latch* latch...) override;
   virtual void stop() override { must_stop = true; timestamp_queue.enqueue(-1); }

   ~SensorQueueThread() { stop(); }
private:
   RawSensorListener& observer;
   moodycamel::ReaderWriterQueue<long, 512> timestamp_queue{30};
};

#endif //AREMUCC_AREMUFREEPLAYER_H