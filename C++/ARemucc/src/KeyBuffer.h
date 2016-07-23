#ifndef KEYBUFFER_H
#define KEYBUFFER_H

#include<thread>
#include <cinttypes>
#include<mutex>

enum KeyPress { NONE, LEFT, RIGHT, SAVE, PAUSE, RESUME, ESC, TERMINATE_THREAD };

class KeyBuffer
//=============
{
private :
   volatile int head, tail, size, count;
   KeyPress *buffer = nullptr;
   std::mutex mutex;

   unsigned inc (const unsigned v, const unsigned mod)
   //-------------------------------------------------
   {
      unsigned vv = v + 1;
      if (vv >= mod)
         vv  = 0;
      return vv;
   }

   unsigned dec (const unsigned v, const unsigned int mod)
   {
      unsigned int vv = (0 == v) ? (mod - 1) : (v - 1);
      return vv;
   }

public :
   KeyBuffer(int size)
   //-----------------
   {
      if (size < 0)
      {
         std::cerr << "KeyBuffer::KeyBuffer(" << size << "): Invalid size" << std::endl;
         std::cerr.flush();
         throw std::runtime_error("KeyBuffer constructor: Invalid size");
      }
      KeyBuffer::size = size;
      head = tail = count = 0;
      buffer = new KeyPress[size];
      memset(buffer, 0, size);
   }

   inline bool empty() { return (count == 0); }

   inline bool full() { return (count >= size); }

   KeyPress *flush(int &len)
   //------------------------
   {
      std::lock_guard<std::mutex> lock(mutex);
      len = count;
      KeyPress *p = new KeyPress[len];
      int i = 0;
      while (count > 0)
      {
         p[i++] = buffer[tail];
         tail = inc(tail, size);
         --count;
      }
      count  = head = tail = 0;
      memset(buffer, 0, size);
      return p;
   }

   void clear()
   //----------
   {
      count  = head = tail = 0;
      memset(buffer, 0, size);
   }

   void push(KeyPress c)
   //---------------------
   {
      if (count < size)
      {
         std::lock_guard<std::mutex> lock(mutex);
         buffer[head] = c;
         head = inc(head, size);
         ++count;
      }
   }


   KeyPress pop()
   //-------------
   {
      KeyPress key;
      if (count > 0)
      {
         std::lock_guard<std::mutex> lock(mutex);
         key = buffer[tail];
         tail = inc (tail, size);
         --count;
      }
      else
         key = NONE;
      return key;
   }

};
#endif