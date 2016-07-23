#ifndef _INC_LATCH_
#define _INC_LATCH_

#include <mutex>
#include <condition_variable>

using namespace std;

class Latch
//=========
{
public:
   explicit Latch(unsigned int no) : initial_count(no), count(no) {}
   Latch(Latch const &) = delete;

   inline void reset() { count = initial_count; }
   
   void wait()
   //--------
   {
      if (count == 0) return;
      unique_lock<mutex> lock(mtx);
      condition.wait(lock, [this]() { return (count == 0); });      
   }
   
   void decrement()
   //--------------
   {
      lock_guard<mutex> lock(mtx);
      if (count == 0) 
         throw std::system_error(std::make_error_code(std::errc::invalid_argument));      
      if (--count == 0) 
         condition.notify_all();         

   }

   void operator=(Latch const &latch) = delete;
   
private:
   unsigned int initial_count, count;
   condition_variable condition;
   mutex mtx;   
};
#endif
