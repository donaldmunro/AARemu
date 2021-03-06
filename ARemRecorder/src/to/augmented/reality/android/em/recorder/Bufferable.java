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

import java.io.File;
import java.io.IOException;

public interface Bufferable
//=========================
{
   void bufferOn();

   void bufferOff();

   void bufferClear();

   boolean bufferEmpty();

   void startTimestamp(long timestamp);

   void writeFile(File f) throws IOException;

   File writeFile();

   int writeCount();

   long writeSize();

   boolean closeFile() throws IOException;

   void flushFile();

   void writeOn();

   void writeOff();

   /**
    * Push data onto buffer
    * @param timestamp The timestamp for the data
    * @param data The data
    * @param retries Number of retries when buffer is full. 0 = no retries, -1 = Retry forever
    */
   void push(long timestamp, byte[] data, int retries);

   void stop();

   boolean openForReading();

   boolean openForReading(File f);

   BufferData read() throws IOException;

   long readPos(long offset);

   public class BufferData
   {
      public long timestamp;
      public Object data;
      public long fileOffset;
   };
}
