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

public class RecorderRingBuffer
//=============================
{
   class RingBufferContent
   //=====================
   {
      long timestamp;
      byte[] buffer;

      RingBufferContent(int bufferSize) { buffer = new byte[bufferSize]; timestamp = -1; }
   }

   RingBufferContent[] buffers;
   int head, tail, length, count, size;
   public synchronized int length() { return length; }

   public RecorderRingBuffer(int count, int size)
   //----------------------------
   {
      buffers = new RingBufferContent[count];
      for (int i=0; i<count; i++)
         buffers[i] = new RingBufferContent(size);
      this.count = count;
      this.size = size;
      head = tail = length = 0;
   }

   public synchronized void clear() { head = tail = length = 0; }

   public boolean isEmpty() { return (length == 0); }

   public boolean isFull() { return (length >= count); }

//   public int push(long timestamp, byte[] buffer)
//   //--------------------------------------------
//   {
//      if (length < count)
//      {
//         buffers[head].timestamp = timestamp;
//         System.arraycopy(buffer, 0, buffers[head].buffer, 0, size);
//         head = indexIncrement(head);
//         length++;
//         return count - length;
//      }
//      return -1;
//   }

   public synchronized int push(long timestamp, byte[] buffer)
   //---------------------------------------------------------
   {
      if (length >= count)
      {
         tail = indexIncrement(tail);
         length--;
      }
      buffers[head].timestamp = timestamp;
      System.arraycopy(buffer, 0, buffers[head].buffer, 0, size);
      head = indexIncrement(head);
      length++;
      return count - length;
   }

   public synchronized long pop(byte[] buffer)
   //----------------------------
   {
      long ts = -1;
      if (length > 0)
      {
         ts = buffers[tail].timestamp;
         System.arraycopy(buffers[tail].buffer, 0, buffer, 0, size);
         tail = indexIncrement(tail);
         length--;
      }
      return ts;
   }

   public long peek(byte[] buffer)
   //----------------------------
   {
      long ts = -1;
      if (length > 0)
      {
         ts = buffers[tail].timestamp;
         System.arraycopy(buffers[tail].buffer, 0, buffer, 0, size);
      }
      return ts;
   }

   public long peekHeadTime()
   //------------------------
   {
      long ts = -1;
      if (length > 0)
         ts = buffers[head].timestamp;
      return ts;
   }

   public synchronized long findBest(long timestampCompareNS, long epsilonNS, byte[] buffer)
   //----------------------------------------------------------------------------
   {
      if (length == 0)
         return Long.MIN_VALUE;
      long mindiff = Long.MAX_VALUE, tss = Long.MIN_VALUE;
      int index = tail, ii = -1;
      for (int i=0; i<length; i++)
      {
         final long ts = buffers[index].timestamp;
         if ( (ts >= timestampCompareNS - epsilonNS) && (ts <= (timestampCompareNS + epsilonNS)) )
         {
            final long diff = Math.abs(ts - timestampCompareNS);
            if (diff < mindiff)
            {
               mindiff = diff;
               tss = ts;
               ii = index;
            }
         }
         index = indexIncrement(index);
      }
      if (ii >= 0)
      {
         System.arraycopy(buffers[ii].buffer, 0, buffer, 0, size);
         return tss;
      }
      return Long.MIN_VALUE;
   }

   public synchronized long findFirst(final long timestampCompareNS, final long epsilonNS, final byte[] buffer)
   //----------------------------------------------------------------------------
   {
      if (length == 0)
         return Long.MIN_VALUE;
      int index = tail;
      for (int i=0; i<length; i++)
      {
         final long ts = buffers[index].timestamp;
         if ( (ts >= timestampCompareNS - epsilonNS) && (ts <= (timestampCompareNS + epsilonNS)) )
         {
            System.arraycopy(buffers[index].buffer, 0, buffer, 0, size);
            return ts;
         }
         index = indexIncrement(index);
      }
      return Long.MIN_VALUE;
   }

   synchronized public long findGreater(long timestamp, byte[] buffer)
   //----------------------------------------------------
   {
      if (length == 0)
         return -1;
      final long ts = buffers[head].timestamp;
      if (ts < timestamp)
         return -1;
      System.arraycopy(buffers[head].buffer, 0, buffer, 0, size);
      return ts;
   }


   @Override
   public String toString()
   //----------------------
   {
      StringBuilder sb = new StringBuilder("Size = ").append(length).append("\n");
      int index = tail;
      for (int i=0; i<length; i++)
      {
         sb.append(buffers[index].timestamp).append(": ");
         for (int j=0; j<16; j++)
            sb.append(buffers[index].buffer[j]).append(" ");
         sb.append("\n");
         index = indexIncrement(index);
      }
      return sb.toString();
   }

   private int indexIncrement(int i) { return  (++i >= count) ? 0 : i; }

   private int indexDecrement(int i) { return (0 == i) ? (count - 1) : (i - 1);  }
}
