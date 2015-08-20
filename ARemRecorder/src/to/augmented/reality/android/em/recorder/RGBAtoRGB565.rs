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

rs_allocation gIn;
rs_allocation gOut;
rs_script gScript;

/*  Stupid Java has no way to cast short[] to byte[] for writing to file
uint16_t __attribute__((kernel)) root(const uchar4 in, uint32_t x, uint32_t y)
//---------------------------------------------------------------------------
{
  uint16_t out;
  uint16_t r = (in.r << 11) & 0xF800;
  uint16_t g = (in.g << 5) & 0x7E0;
  uint16_t b = in.b & 0x1F;
  out = r | g | b;
  return out;
}
*/

uchar2 __attribute__((kernel)) root(const uchar4 in, uint32_t x, uint32_t y)
//---------------------------------------------------------------------------
{
  uint16_t out;
  uchar2 ret;
  uint16_t r = (in.r << 11) & 0xF800;
  uint16_t g = (in.g << 5) & 0x7E0;
  uint16_t b = in.b & 0x1F;
  out = r | g | b;
  ret.r = out >> 8;
  ret.g = out & 0x00FF;
  return out;
}
