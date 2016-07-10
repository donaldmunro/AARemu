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

#pragma version(1)
#pragma rs java_package_name(to.augmented.reality.em.recorder)

rs_allocation in;

uchar4 RS_KERNEL yuv2rgba(uint32_t x, uint32_t y)
//------------------------------------------------
{
   uchar Y = rsGetElementAtYuv_uchar_Y(in, x, y);
   uchar U = rsGetElementAtYuv_uchar_U(in, x, y);
   uchar V = rsGetElementAtYuv_uchar_V(in, x, y);

   uchar4 out;
   out.r = Y + 1.402 * (V - 128);
   out.g = Y - 0.34414 * (U - 128) - 0.71414 * (V - 128);
   out.b = Y + 1.772 * (U - 128);
   out.a = 255;
   return out;
}
