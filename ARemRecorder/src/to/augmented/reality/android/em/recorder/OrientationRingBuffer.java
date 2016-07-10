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

import to.augmented.reality.android.common.math.Quaternion;

public class OrientationRingBuffer
//================================
{
   public OrientationData createContent() { return new OrientationData(); }

   OrientationData[] content;
   volatile int head, tail, length;
   final int count;

   public OrientationRingBuffer(int count)
   //---------------------------------
   {
      content = new OrientationData[count];
      for (int i=0; i<count; i++)
         content[i] = new OrientationData();
      this.count = count;
      head = tail = length = 0;
   }

   public synchronized void clear() { head = tail = length = 0; }

   public synchronized boolean isEmpty() { return (length == 0); }

   public synchronized boolean isFull() { return (length >= count); }

   public synchronized int push(long timestamp, Quaternion Q, float[] R)
   //----------------------------------------------------------------
   {
      if (length >= count)
      {
         tail = indexIncrement(tail);
         length--;
      }
      OrientationData rbc = content[head];
      rbc.set(timestamp, Q, R);
      head = indexIncrement(head);
      length++;
      return count - length;
   }

   public synchronized OrientationData pop()
   //-----------------------------------------
   {
      OrientationData popped = null;
      if (length > 0)
      {
         popped = new OrientationData(content[tail]);
         tail = indexIncrement(tail);
         length--;
      }
      return popped;
   }

   public synchronized OrientationData[] popAll()
   //----------------------------------------------
   {
      OrientationData[] contents = new OrientationData[length];
      if (length > 0)
      {
         int i = 0;
         while (length > 0)
         {
            contents[i++] = new OrientationData(content[tail]);
            tail = indexIncrement(tail);
            length--;
         }
      }
      return contents;
   }

   public synchronized OrientationData peek()
   //-------------------------------------------
   {
      if (length > 0)
         return new OrientationData(content[tail]);
      return null;
   }

   public synchronized long peekTime()
   //---------------------------------
   {
      if (length > 0)
         return content[tail].timestamp;
      return -1;
   }

   public synchronized OrientationData peekHead()
   //----------------------------------------------
   {
      if (length > 0)
      {
         final int index = indexDecrement(head);
         return new OrientationData(content[index]);
      }
      return null;
   }

   public OrientationData find(final long timestampCompareNS, final long epsilonBeforeNS,
                               final long epsilonAfterNS)
   //-------------------------------------------------------------------------------------------------------------
   {
      if (length == 0) return null;
      final long gt = timestampCompareNS - epsilonBeforeNS;
      final long lt = timestampCompareNS + epsilonAfterNS;
      int index;
      final int len;
      synchronized (this) { len = length; index = indexDecrement(head); }
      for (int i=0; i<len; i++)
      {
         final long ts = content[index].timestamp;
         if (gt > ts)
         {
//            Log.i("BearingRingBuffer", "find terminate " + gt + " " + ts + " " + lt + " item " + i);
            return null;
         }
         if ( (ts >= gt) && (ts <= lt) )
            return new OrientationData(content[index]);
         index = indexDecrement(index);
      }
      return null;
   }

   public OrientationData findClosest(final long timestampCompareNS, final long epsilonBeforeNS,
                                      final long epsilonAfterNS)
   //-------------------------------------------------------------------------------------------------------------
   {
      if (length == 0)
         return null;
      final long gt = timestampCompareNS - epsilonBeforeNS;
      final long lt = timestampCompareNS + epsilonAfterNS;
      int index;
      final int len;
      int ii = -1;
      long mindiff = Long.MAX_VALUE;
      synchronized (this) { len = length; index = indexDecrement(head); }
      for (int i=0; i<len; i++)
      {
         final long ts = content[index].timestamp;
//         Log.i("BearingRingBuffer", "findClosest " + gt/1000000 + " " + ts/1000000 + " " + lt/1000000 + " item " + i+ " "
//               + ( (ts >= gt) && (ts <= lt) ));
         if (gt > ts)
         {
//            Log.i("BearingRingBuffer", "findClosest terminate " + gt/1000000 + " " + ts/1000000 + " " + lt/1000000 +
//                  " item " + i + " " + ( (ts >= gt) && (ts <= lt) ));
            break;
         }
         if ( (ts >= gt) && (ts <= lt) )
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
         return new OrientationData(content[ii]);
      return null;
   }


   private int indexIncrement(int i) { return (++i >= count) ? 0 : i; }
   private int indexDecrement(int i) { return (0 == i) ? (length - 1) : (i - 1);  }
}
