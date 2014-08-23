/*
* Copyright (C) 2014 Donald Munro.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package to.augmented.reality.android.em.recorder;

public class BearingRingBuffer
//============================
{
   public class RingBufferContent
   //============================
   {
      long timestamp;
      float bearing;

      RingBufferContent() { bearing = -1; timestamp = -1; }
   }

   RingBufferContent[] bearings;
   int head, tail, length, count;

   public BearingRingBuffer(int count)
   //---------------------------------
   {
      bearings = new RingBufferContent[count];
      for (int i=0; i<count; i++)
         bearings[i] = new RingBufferContent();
      this.count = count;
      head = tail = length = 0;
   }

   public synchronized void clear() { head = tail = length = 0; }

   public synchronized boolean isEmpty() { return (length == 0); }

   public synchronized boolean isFull() { return (length >= count); }

   public synchronized int push(long timestamp, float bearing)
   //-------------------------------------------------------------
   {
      if (length >= count)
      {
         tail = indexIncrement(tail);
         length--;
      }
      bearings[head].timestamp = timestamp;
      bearings[head].bearing = bearing;
      head = indexIncrement(head);
      length++;
      return count - length;
   }

   public synchronized RingBufferContent pop()
   //-----------------------------------------
   {
      RingBufferContent popped = null;
      if (length > 0)
      {
         popped =  bearings[tail];
         tail = indexIncrement(tail);
         length--;
      }
      return popped;
   }

   public float popBearing()
   {
      float bearing = -1;
      if (length > 0)
      {
         bearing =  bearings[tail].bearing;
         tail = indexIncrement(tail);
         length--;
      }
      return bearing;
   }

   public synchronized RingBufferContent peek()
   //-------------------------------------------
   {
      if (length > 0)
         return bearings[tail];
      return null;
   }

   public synchronized RingBufferContent peekHead()
   //----------------------------------------------
   {
      if (length > 0)
         return bearings[head];
      return null;
   }

   public synchronized RingBufferContent findLess(long timestampCompareNS, long epsilonNS)
   //-------------------------------------------------------------------------------------
   {
      if (length == 0)
         return null;
      int index = head;
      for (int i=0; i<length; i++)
      {
         final long ts = bearings[index].timestamp;
         if ( (ts >= timestampCompareNS - epsilonNS) && (ts <= timestampCompareNS) )
            return bearings[index];
         index = indexDecrement(index);
      }
      return null;
   }

   public synchronized float peekBearing()
   //-------------------------------------
   {
      if (length > 0)
         return bearings[tail].bearing;
      return Float.MIN_VALUE;
   }

   public synchronized float peekLatestBearing()
   //-------------------------------------------
   {
      if (length > 0)
         return bearings[head].bearing;
      return Float.MIN_VALUE;
   }

   public synchronized RingBufferContent find(float bearing, float epsilon, long timestampCompareNS, long epsilonNS)
   //---------------------------------------------------------------------------------------------------------------
   {
      if (length == 0)
         return null;
      int index = tail;
      float needle;
      for (int i=0; i<length; i++)
      {
         needle = bearings[index].bearing;
         if ( (needle >= bearing) && (needle < bearing + epsilon) )
         {
            if (timestampCompareNS > 0)
            {
               final long ts = bearings[index].timestamp;
               if ((ts >= timestampCompareNS - epsilonNS) && (ts <= (timestampCompareNS + epsilonNS)))
                  return bearings[index];
            }
            else
               return bearings[index];
         }
         index = indexIncrement(index);
      }
      return null;
   }

   private int indexIncrement(int i) { return (++i >= count) ? 0 : i; }
   private int indexDecrement(int i) { return (0 == i) ? (length - 1) : (i - 1);  }
}
