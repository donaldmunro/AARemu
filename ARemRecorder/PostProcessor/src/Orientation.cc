#include <string.h>

#include <iostream>
#include <sstream>

#include "Orientation.h"
#include "util.h"

inline bool _read(std::ifstream& in, char * buf, const int size, const std::string message)
//-----------------------------------------------------------------------------------------
{
   in.read(buf, size);
   if (! in.good())
   {
      if (! in.eof()) std::cerr << "OrientationData: " << message << std::endl;
      return false;
   }
   return (! in.eof());
}

bool OrientationData::read(std::ifstream& in)
//----------------------------------------------------------------------
{
   uint64_t v;
   if (! _read(in, (char *) &v, sizeof(uint64_t), "Error reading timestamp"))
      return false;
   timestamp = (unsigned long) be64toh(v);

   uint32_t v32;
   int32_t bg;
   for (int i=0; i<4; i++)
   {
      std::stringstream ss;
      ss << "Error reading Q[" << i << "]";
      if (!_read(in, (char *) &v32, sizeof(uint32_t), ss.str()))
         return false;
      bg = (int32_t) be32toh(v32);
      memcpy(&Q[i], &bg, sizeof(float));
   }

   if (!_read(in, (char *) &v32, sizeof(uint32_t), "Error reading R length"))
      return false;
   bg = (int32_t) be32toh(v32);
   const uint32_t lastRLen = rlen;
   rlen = static_cast<uint32_t>(bg);
   if ( (lastRLen != rlen) && (R != nullptr) )
   {
      delete[] R;
      R = nullptr;
   }
   if (R == nullptr)
      R = new float[rlen];
   for (uint32_t i=0; i<rlen; i++)
   {
      std::stringstream ss;
      ss << "Error reading R[" << i << "]";
      if (!_read(in, (char *) &v32, sizeof(uint32_t), "Error reading R length"))
         return false;
      bg = (int32_t) be32toh(v32);
      memcpy(&R[i], &bg, sizeof(float));
   }
   if (!_read(in, (char *) &v32, sizeof(uint32_t), "Error reading bearing"))
      return false;
   bg = (int32_t) be32toh(v32);
   memcpy(&bearing, &bg, sizeof(float));
   if (! in.good())
   {
      if (R) delete [] R;
      R = nullptr;
      rlen = 0;
      Q[0] = Q[1] = Q[2] = Q[3] = 0;
      timestamp = -1;
      bearing = -1;
   }
   return in.good();
}

bool OrientationData::write(std::ofstream& orientationWriter, float bearing)
//-------------------------------------------------------------------------
{
   uint64_t v;
   uint32_t v32;
   to_big_endian64((const unsigned char *) &timestamp, v);
   orientationWriter.write((const char *) &v, sizeof(uint64_t));
   for (int i=0; i<4; i++)
   {
      to_big_endian32((const unsigned char *) &Q[i], v32);
      orientationWriter.write((const char *) &v32, sizeof(uint32_t));
   }
   to_big_endian32((const unsigned char *) &rlen, v32);
   orientationWriter.write((const char *) &v32, sizeof(uint32_t));
   for (uint32_t i=0; i<rlen; i++)
   {
      to_big_endian32((const unsigned char *) &R[i], v32);
      orientationWriter.write((const char *) &v32, sizeof(uint32_t));
   }
   to_big_endian32((const unsigned char *) &bearing, v32);
   orientationWriter.write((const char *) &v32, sizeof(uint32_t));
   return orientationWriter.good();
}

OrientationData &OrientationData::operator=(const OrientationData &other)
//-----------------------------------------------------------------------
{
   if (&other != this)
   {
      timestamp = other.timestamp;
      std::copy(other.Q, other.Q + 4, Q);
      rlen = other.rlen;
      if ( (rlen > 0) && (other.R != nullptr) )
      {
         R = new float[rlen];
         std::copy(other.R, other.R + rlen, R);
      }
      bearing = other.bearing;
   }
   return *this;
}

OrientationData::OrientationData(const OrientationData &other)
//-----------------------------------------------------
{
   timestamp = other.timestamp;
   std::copy(other.Q, other.Q + 4, Q);
   rlen = other.rlen;
   if ( (rlen > 0) && (other.R != nullptr) )
   {
      R = new float[rlen];
      std::copy(other.R, other.R + rlen, R);
   }
   bearing = other.bearing;
}
