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

//#include "rs_debug.rsh"

rs_allocation in;

//uchar4 RS_KERNEL yuv2grey(uint32_t x, uint32_t y)
////------------------------------------------------
//{
//   uchar Y = rsGetElementAtYuv_uchar_Y(in, x, y)  & 0xFF;
//   uchar4 out;
//   out.r = out.g = out.b = Y;
//   out.a = 255;
//   return out;
//}

uchar RS_KERNEL yuv2grey(uint32_t x, uint32_t y)
//------------------------------------------------
{
   uchar Y = rsGetElementAtYuv_uchar_Y(in, x, y)  & 0xFF;
   uchar out;
   out = Y;
//   rsDebug("YUV Y = ", (char) out);
   return out;
}
