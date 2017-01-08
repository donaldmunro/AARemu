#ifndef POSTPROCESSOR_ORIENTATIONDATA_H
#define POSTPROCESSOR_ORIENTATIONDATA_H

#if defined(_WIN32) || defined(_WIN64)
#define NOMINMAX
#include <WinSock2.h>
#include <Windows.h>
#	if REG_DWORD == REG_DWORD_LITTLE_ENDIAN

#		define htobe16(x) htons(x)
#		define htole16(x) (x)
#		define be16toh(x) ntohs(x)
#		define le16toh(x) (x)

#		define htobe32(x) htonl(x)
#		define htole32(x) (x)
#		define be32toh(x) ntohl(x)
#		define le32toh(x) (x)

#		define htobe64(x) htonll(x)
#		define htole64(x) (x)
#		define be64toh(x) ntohll(x)
#		define le64toh(x) (x)

#	elif REG_DWORD == REG_DWORD_BIG_ENDIAN

#		define htobe16(x) (x)
#		define htole16(x) __builtin_bswap16(x)
#		define be16toh(x) (x)
#		define le16toh(x) __builtin_bswap16(x)

#		define htobe32(x) (x)
#		define htole32(x) __builtin_bswap32(x)
#		define be32toh(x) (x)
#		define le32toh(x) __builtin_bswap32(x)

#		define htobe64(x) (x)
#		define htole64(x) __builtin_bswap64(x)
#		define be64toh(x) (x)
#		define le64toh(x) __builtin_bswap64(x)

#	else

#		error byte order not supported

#	endif
#else
#include <endian.h>
#include <arpa/inet.h>
#endif
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
