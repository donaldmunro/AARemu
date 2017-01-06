#ifndef POSTPROCESSOR_RINGBUFFER_H
#define POSTPROCESSOR_RINGBUFFER_H

#include <vector>
#include <mutex>

template <typename T>
class RingBuffer
//==============
{
protected:
   std::vector<T> content;
   volatile int head =0, tail =0, length =0;
   int size =-1;
   std::mutex mtx;

   inline int indexIncrement(int i) { return (++i >= size) ? 0 : i; }
   inline int indexDecrement(int i) { return (0 == i) ? (length - 1) : (i - 1);  }

public:
   RingBuffer() = delete;
   RingBuffer(int _size) : size(_size) {};
   void clear() { head = tail = length = 0; }
   bool empty() { return (length == 0); }
   bool full() { return (length >= size); }

   int push(T &data)
   //---------------
   {
      if (length >= size)
      {
         tail = indexIncrement(tail);
         length--;
      }
      content[head] = data;
      head = indexIncrement(head);
      length++;
      return size - length;
   }

   int pushmt(T &data)
   //---------------
   {
      std::lock_guard<std::mutex> lock(mtx);
      if (length >= size)
      {
         tail = indexIncrement(tail);
         length--;
      }
      content[head] = data;
      head = indexIncrement(head);
      length++;
      return size - length;
   }

   bool pop(T& popped)
   //-----------------
   {
      if (length > 0)
      {
         popped = content[tail];
         tail = indexIncrement(tail);
         length--;
         return true;
      }
      return false;
   }

   bool popmt(T& popped)
   //-----------------
   {
      std::lock_guard<std::mutex> lock(mtx);
      if (length > 0)
      {
         popped = content[tail];
         tail = indexIncrement(tail);
         length--;
         return true;
      }
      return false;
   }

   int peekList(std::vector<T>& contents)
   //--------------------------------------------------
   {
      contents.clear();
      int c = 0;
      if (length > 0)
      {
         int len = length;
         int t = tail;
         while (len > 0)
         {
            contents.push_back(content[t]);
            t = indexIncrement(t);
            len--;
            c++;
         }
      }
      return c;
   }

   int peekListmt(std::vector<T>& contents)
   //--------------------------------------------------
   {
      contents.clear();
      int c = 0;
      if (length > 0)
      {
         std::lock_guard<std::mutex> lock(mtx);
         int len = length;
         int t = tail;
         while (len > 0)
         {
            contents.push_back(content[t]);
            t = indexIncrement(t);
            len--;
            c++;
         }
      }
      return c;
   }
};

#endif //POSTPROCESSOR_RINGBUFFER_H
