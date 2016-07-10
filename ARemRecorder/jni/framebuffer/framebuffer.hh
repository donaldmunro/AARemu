#ifndef _INC_RINGBUF_
#define _INC_RINGBUF_

#include <string.h> //memset
#include <endian.h>
#include <arpa/inet.h>
#include <inttypes.h>
#include <errno.h>

#include <string>
#include <iostream>
#include <fstream>
#include <chrono>
#include <thread>
#include <mutex>
#include <condition_variable>

#include "readerwriterqueue.h"
#include "snappy.h"

#ifdef ANDROID_LOG
#include <android/log.h>
#define  LOG_TAG    "framebuffer.so"
#define  LOGI(...)  __android_log_print(ANDROID_LOG_INFO,LOG_TAG,__VA_ARGS__)
#define  LOGD(...)  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define  LOGE(...)  __android_log_print(ANDROID_LOG_ERROR,LOG_TAG,__VA_ARGS__)
#endif

struct FrameBufferData
//====================
{
public:
   FrameBufferData(int64_t ts, size_t sz, unsigned char *data =nullptr) :
   _timestamp(ts), _size(sz)
   //-------------------------------------------------------------------------------------------
   {
      if ( (data != nullptr) && (sz > 0) )
      {
         _data = new unsigned char[sz];
         ::memcpy(_data, data, sz);
      }
      else
         _data = nullptr;
   }

   virtual ~FrameBufferData() { if (_data) delete[] _data; }

   FrameBufferData(FrameBufferData &other) = delete;

   FrameBufferData(FrameBufferData &&other)
   //------------------------------
   {
      _size = other._size;
      _timestamp = other._timestamp;
      if (other._data != nullptr)
      {
         _data = new unsigned char[_size];
         ::memcpy(_data, other._data, _size);
         delete[] other._data;
         other._data = nullptr;
      }
      else
         _data = nullptr;
      other._size = 0;
   }

   friend class FrameBuffer;

private:
   unsigned char *_data;
   size_t _size;
   int64_t _timestamp;
};

class FrameBuffer
//===============
{
public:
   FrameBuffer(int count, size_t size, bool must_compress) : _count(count), _size(size), _must_compress(must_compress),
                                                             _timeoffset(0)
   //-----------------------------------------------------------------------------------------------------------------
   {
      q = new moodycamel::ReaderWriterQueue<FrameBufferData *>(count);
      _write_thread = std::thread(&FrameBuffer::run, this);
   }

   virtual ~FrameBuffer()
   //--------------------
   {
      stop();
      close();
      if (q != nullptr)
         delete q;
   }

   void stop()
   //---------
   {
      if (_running)
      {
         _stopping = true;
         if (! _writing)
         {
            std::unique_lock<std::mutex> lock(_writing_mutex);
            _writing_cond.notify_all();
         }
         _write_thread.join();
         _running = false;
      }
   }

   inline void timeoffset(int64_t offset) { _timeoffset = offset; }

   inline int64_t timeoffset() { return _timeoffset; }

   bool filename(std::string file)
   //-----------------------------
   {
      if (_filename != file)
      {
         _filename = file;
         if (_output != nullptr)
         {
            _output->close();
            delete _output;
         }
         _output = new std::ofstream(file, std::ios::out | std::ios::binary | std::ios::ate);
         if (! _output->is_open())
         {
            delete _output;
            _output = nullptr;
            return false;
         }
         _writecount = 0;
         return (_output->is_open());
      }
      else
         return ( (_output != nullptr) && (_output->is_open()) );
   }

   void close()
   //----------
   {
      if (_output != nullptr)
      {
         _output->close();
         _output = nullptr;
      }
   }

   void flush()
   //----------
   {
      if (_output != nullptr)
         _output->flush();
   }

   inline std::string filename() { return _filename; }

   inline void buffering(bool is_buffer) { _buffering = is_buffer; }

   inline bool buffering() { return _buffering; }

   bool writing(bool writeon)
   //------------------------
   {
      if ( (writeon) && ( (_output == nullptr) || (_filename.empty()) ) )
         return false;
      if ( (! _writing) && (writeon) )
      {
         std::unique_lock<std::mutex> lock(_writing_mutex);
         _writing = true;
         _writing_cond.notify_all();
      }
      else
         _writing = writeon;

   }

   inline bool writing() { return _writing; }

   void push(int64_t timestamp, unsigned char *buf)
   //----------------------------------------------
   {
      if ( (! _buffering) || (_stopping) ) return;
      timestamp -= _timeoffset;
      FrameBufferData *data = new FrameBufferData(timestamp, _size, buf);
      if (! q->try_enqueue(data))
      {
         delete data;
#ifdef ANDROID_LOG
         LOGE("Error enqueueing frame at timestamp %lld", (long long) timestamp);
#endif
      }
//      else
//      {
//#ifdef ANDROID_LOG
//         LOGI("Enqueued frame at timestamp %lld", (long long) timestamp);
//#endif
//      }
   }

   void push_YUV(int64_t timestamp, unsigned char *Y, int yLen, unsigned char* U, int uLen, int uStride,
                 unsigned char *V, int vLen, int vStride)
   //------------------------------------------------------------------------------------------------
   {
      if ( (! _buffering) || (_stopping) ) return;
      timestamp -= _timeoffset;
      unsigned char *buf = nullptr;
      if ( (Y == nullptr) && (U == nullptr) && (V == nullptr) )
         buf = nullptr;
      else
      {
         buf = new unsigned char[_size];
         memcpy(buf, Y, (size_t) yLen);
         int i = yLen;
         if (uStride == 1)
         {
            memcpy(&buf[i], U, (size_t) uLen);
            i += uLen;
            memcpy(&buf[i], V, (size_t) vLen);
         }
         else
         {
            for (int j = 0; j < uLen; j += uStride)
               buf[i++] = U[j];
            for (int j = 0; j < vLen; j += vStride)
               buf[i++] = V[j];
         }
      }
      FrameBufferData *data = new FrameBufferData(timestamp, _size, buf);
      if (! q->try_enqueue(data))
      {
         delete data;
#ifdef ANDROID_LOG
         LOGE("Error enqueueing frame at timestamp %lld", (long long) timestamp);
#endif
      }
      else
      {
#ifdef ANDROID_LOG
         LOGE("Enqueued frame at timestamp %lld", (long long) timestamp);
#endif
      }
   }

   FrameBufferData *pop()
   //----------------
   {
      FrameBufferData *data = nullptr;
      if (q->try_dequeue(data))
         return data;
      else
         return nullptr;
   }

   inline int written() { return _writecount; }

   inline int size() { return q->size_approx(); }

   bool read_open(std::string filename ="")
   //---------------------------------
   {
      if ( (filename.empty()) && (_filename.empty()) )
         return false;
      if (_input != nullptr)
      {
         _input->close();
         delete _input;
      }
      if (filename.empty())
         filename = _filename;
      _input = new std::fstream(filename.c_str(), std::ifstream::in);
      if (_input->good())
      {
         _filename = filename;
         return true;
      }
      return false;
   }

   int64_t read(int64_t &timestamp, int &size, unsigned char *data, int64_t filepos =-1)
   //-----------------------------------------------------------------------------
   {
      if ( (_input == nullptr) || (! _input->good()) )
         return -1L;
      if (filepos >= 0)
         _input->seekg(filepos, std::ios_base::beg);
      uint64_t v;
      _input->read((char *) &v, sizeof(uint64_t));
      if (! _input->good())
         return -1L;
      int64_t ts = from_big_endian64<int64_t>(v);
      timestamp = (int64_t) ts;

      _input->read((char *) &v, sizeof(uint64_t));
      if (! _input->good())
         return -1L;
      size = from_big_endian64<int>(v);
      bool ok = true;
      if (size > 0)
      {
         if (_must_compress)
         {

            char* compressed = new char[size];
            _input->read(compressed, size);
            if (! _input->bad())
               ok = snappy::RawUncompress(compressed, size, (char *) data);
            delete[] compressed;
         }
         else
         {
            _input->read((char *) data, size);
            ok = ( (! _input->bad()) && (_input->gcount() == size) );
         }
      }
      if (! ok)
         return -1L;
      return _input->tellg();
   }

   int64_t readoffset(int64_t offset)
   //--------------------------------
   {
      if ( (_input == nullptr) || (! _input->good()) )
         return -1L;
      if (offset >= 0)
         _input->seekg(offset, std::ios_base::beg);
      return _input->tellg();
   }

   void run()
   //--------
   {
      _running = true;
      volatile std::ofstream *output = nullptr;
      char c8[8] = {0};
      int head = 0;
      int tail = 0;
      FrameBufferData *data;
      while (_running)
      {
         if (! _writing)
         {
            std::unique_lock<std::mutex> lock(_writing_mutex);
            _writing_cond.wait_for(lock, std::chrono::milliseconds(200),
                                   [this] { return ( (this->_writing) || (this->_stopping) || (! this->_running) ); });
            if ( (_stopping) || (! _running) )
               break; // Presumably stop if writing is disabled when request stop happens
            continue;
         }

         data = nullptr;
         if (! q->try_dequeue(data))
         {
            if (_stopping)
               break;
            else
            {
               std::this_thread::yield();
               continue;
            }
         }

//          std::ofstream ofs("/sdcard/Documents/ARRecorder/t/frame.write");
//          ofs.write((const char *)data->_data, _size);
//          ofs.close();

         output = ((volatile std::ofstream *) _output);
         if ( (output != nullptr) && (_writing) )
         {
            uint64_t v = to_big_endian64<int64_t>(data->_timestamp);
            ((std::ofstream *)output)->write((const char *) &v, sizeof(uint64_t));
            int write_size;
            if (data->_data == nullptr)
            {
               write_size = 0;
               v = to_big_endian64<int>(write_size);
               ((std::ofstream *)output)->write((const char *) &v, sizeof(uint64_t));
            }
            else
            {
               char *write_data;
               if (_must_compress)
               {
                  write_data = new char[snappy::MaxCompressedLength(_size)];
                  size_t sz;
                  snappy::RawCompress((const char *) data->_data, _size, write_data, &sz);
                  write_size = (int) sz;
               }
               else
               {
                  write_size = (int) _size;
                  write_data = (char *) data->_data;
               }
               v = to_big_endian64<int>(write_size);
               ((std::ofstream *)output)->write((const char *) &v, sizeof(uint64_t));
               ((std::ofstream *)output)->write(write_data, write_size);
               if (_must_compress)
                  delete write_data;
            }
            _writecount++;
         }
         if (data != nullptr)
            delete data;
      }
      _running = false;
   }


   template<typename T>
   static uint64_t to_big_endian64(const T val)
   //--------------------------------
   {
      uint64_t v = val;
      return htobe64(v);
   }

   template<typename T>
   static T from_big_endian64(const uint64_t v)
   //-----------------------------
   {
      T vv = (T) be64toh(v);
      return vv;
   }

   template<typename T>
   static uint32_t to_big_endian32(const T val)
   //-------------------------------------------
   {
      uint32_t v = val;
      return htonl(v);
   }

   template<typename T>
   static T from_big_endian32(const uint32_t v)
   //-----------------------------
   {
      T vv = (T) ntohl(v);
      return vv;
   }

private:
   const int _count;
   const size_t _size;
   std::string _filename = "";
   int64_t _timeoffset = 0;
   std::ofstream *_output = nullptr;
   std::fstream *_input = nullptr;
   bool _must_compress = true;
   volatile bool _writing = false, _running = false, _stopping = false, _buffering = false;
   volatile int _writecount;
   std::mutex _writing_mutex;
   std::condition_variable _writing_cond;
   moodycamel::ReaderWriterQueue<FrameBufferData *> *q;
   std::thread _write_thread;
};

#endif