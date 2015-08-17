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
   public RingBufferContent createContent() { return new RingBufferContent(); }

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

   public synchronized float popBearing()
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
      {
         final int index = indexDecrement(head);
         return bearings[index];
      }
      return null;
   }

   public RingBufferContent find(final long timestampCompareNS, final long epsilonNS)
   //-------------------------------------------------------------------------------------
   {
      int index, len;
      long gt, le;
      synchronized(this)
      {
         if (length == 0)
            return null;
         index = indexDecrement(head);
         gt = timestampCompareNS - epsilonNS;
         le = timestampCompareNS + epsilonNS;
         len = length;
      }
      for (int i=0; i<len; i++)
      {
         final long ts = bearings[index].timestamp;
         if (gt > ts)
         {
            //Log.i("BearingRingBuffer", "find terminate " + gt + " " + timestampCompareNS + " " + ts + " " + (timestampCompareNS - ts) + " " + (gt > ts));
            return null;
         }
         if ( (ts >= gt) && (ts <= le) )
            return bearings[index];
         index = indexDecrement(index);
      }
      return null;
   }

   public synchronized RingBufferContent findClosest(long timestampCompareNS, long epsilonNS)
   //-------------------------------------------------------------------------------------
   {
      if (length == 0)
         return null;
      int index = indexDecrement(head), ii = -1;
      long mindiff = Long.MAX_VALUE;
      for (int i=0; i<length; i++)
      {
         final long ts = bearings[index].timestamp;
         if ( (ts >= (timestampCompareNS - epsilonNS)) && (ts <= (timestampCompareNS + epsilonNS)) )
         {
            final long diff = Math.abs(ts - timestampCompareNS);
            if (diff < mindiff)
            {
               mindiff = diff;
               ii = index;
            }
         }
         index = indexDecrement(index);
      }
      if (ii >= 0)
         return bearings[ii];
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
      {
         final int index = indexDecrement(head);
         return bearings[index].bearing;
      }
      return Float.MIN_VALUE;
   }

   public float findLess(long timestamp)
   //-----------------------------------
   {
      if (length > 0)
      {
         int index = indexDecrement(head);
         for (int i=0; i<length; i++)
         {
            if (bearings[index].timestamp < timestamp)
               return bearings[index].bearing;
            index = indexDecrement(index);
         }
      }
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
