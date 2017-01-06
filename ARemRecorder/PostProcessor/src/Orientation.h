#ifndef POSTPROCESSOR_ORIENTATIONDATA_H
#define POSTPROCESSOR_ORIENTATIONDATA_H

#include <endian.h>
#include <arpa/inet.h>
#include <inttypes.h>

#include <fstream>
#include <array>
#include <vector>

class OrientationData
//===================
{
public:
   long timestamp = 0;
   float Q[4];
   float *R = nullptr;
   uint32_t rlen = 0;
   float bearing = -1.0f;

   OrientationData() {}
   OrientationData(const OrientationData &other);
   virtual ~OrientationData() { if (R != nullptr) delete[] R; }
   virtual int size() { return sizeof(long) + sizeof(float) * (4 + rlen) + sizeof(int); }
   virtual bool read(std::ifstream& in);
   virtual bool write(std::ofstream& orientationWriter, float bearing);
   virtual OrientationData &operator=(const OrientationData &other);
};
#endif //POSTPROCESSOR_ORIENTATIONDATA_H
