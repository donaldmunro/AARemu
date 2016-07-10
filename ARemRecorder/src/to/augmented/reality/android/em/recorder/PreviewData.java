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

public class PreviewData
//=====================
{
   long timestamp = -1;
   byte[] buffer;
   boolean isUsed = false;

   PreviewData(int bufferSize)
   {
      buffer = new byte[bufferSize];
      timestamp = -1;
   }

   public PreviewData(PreviewData other)
   //-----------------------------------------------
   {
      timestamp = other.timestamp;
      isUsed = other.isUsed;
      buffer = Arrays.copyOf(other.buffer, other.buffer.length);
   }
}
