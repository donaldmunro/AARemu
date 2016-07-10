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

import java.util.Arrays;

public class EventRingBuffer
//==========================
{
   volatile int head, tail, length;
   final int count;

   class SensorEvent
   //===============
   {
      long timestamp;
      int sensorType;
      float[] data;

      public SensorEvent()
      //------------------
      {
         timestamp = -1;
         sensorType = -1;
         data = null;
      }

      public SensorEvent(SensorEvent other)
      //-----------------------------------------
      {
         timestamp = other.timestamp;
         sensorType = other.sensorType;
         data = Arrays.copyOf(other.data, other.data.length);
      }
   }

   SensorEvent[] content;

   public EventRingBuffer(int count)
   //---------------------------------
   {
      content = new SensorEvent[count];
      for (int i=0; i<count; i++)
         content[i] = new SensorEvent();
      this.count = count;
      head = tail = length = 0;
   }

   public synchronized void clear() { head = tail = length = 0; }

   public synchronized boolean isEmpty() { return (length == 0); }

   public synchronized boolean isFull() { return (length >= count); }

   public synchronized int push(long timestamp, int sensorType, float[] event)
   //----------------------------------------------------------------
   {
      if (length >= count)
      {
         tail = indexIncrement(tail);
         length--;
      }
      SensorEvent rbc = content[head];
      rbc.timestamp = timestamp;
      rbc.sensorType = sensorType;
      rbc.data = Arrays.copyOf(event, event.length);
      head = indexIncrement(head);
      length++;
      return count - length;
   }

   public synchronized SensorEvent pop()
   //-----------------------------------------
   {
      SensorEvent popped = null;
      if (length > 0)
      {
         popped = new SensorEvent(content[tail]);
         tail = indexIncrement(tail);
         length--;
      }
      return popped;
   }

   public synchronized SensorEvent[] popAll()
   //----------------------------------------------
   {
      SensorEvent[] contents = new SensorEvent[length];
      if (length > 0)
      {
         int i = 0;
         while (length > 0)
         {
            contents[i++] = new SensorEvent(content[tail]);
            tail = indexIncrement(tail);
            length--;
         }
      }
      return contents;
   }

   private int indexIncrement(int i) { return (++i >= count) ? 0 : i; }
   private int indexDecrement(int i) { return (0 == i) ? (length - 1) : (i - 1);  }
}
