#include <cstdio>
#include <string>
#include <cstdint>
#include <unordered_map>
#include <functional>
#include <iostream>
#include <fstream>

void to_big_endian64(const unsigned char *src, uint64_t &result)
//--------------------------------------------------------------
{
   unsigned char *dst = (unsigned char *) &result;
   dst[0] = src[7];
   dst[1] = src[6];
   dst[2] = src[5];
   dst[3] = src[4];
   dst[4] = src[3];
   dst[5] = src[2];
   dst[6] = src[1];
   dst[7] = src[0];
}

void to_big_endian32(const unsigned char *src, uint32_t &result)
//-------------------------------------------------------------
{
   unsigned char *dst = (unsigned char *) &result;
   dst[0] = src[3];
   dst[1] = src[2];
   dst[2] = src[1];
   dst[3] = src[0];
}

void from_big_endian64(const uint64_t &v, unsigned char *dst)
//--------------------------------------------------------------
{
   unsigned char *src = (unsigned char *) &v;
   dst[7] = src[0];
   dst[6] = src[1];
   dst[5] = src[2];
   dst[4] = src[3];
   dst[3] = src[4];
   dst[2] = src[5];
   dst[1] = src[6];
   dst[0] = src[7];
}

void from_big_endian32(const uint32_t &v, unsigned char *dst)
//-------------------------------------------------------------
{
   unsigned char *src = (unsigned char *) &v;
   dst[3] = src[0];
   dst[2] = src[1];
   dst[1] = src[2];
   dst[0] = src[3];
}

//C++ 14
//template <typename K, typename V>
//V headerValue(const std::unordered_map<K, V> m, const K key, const V notFoundValue,
//               std::function< V (const V* v) > fn_predicate)
////----------------------------------------------------------------------------------
//{
//   auto it = m.find(key);
//   if (it == m.end())
//      return notFoundValue;
//   else
//      return fn_predicate(it->second);
//}

std::string headerValue(const std::unordered_map<std::string, std::string> headers, const std::string key,
                        const std::string notFoundValue,
                        std::function<std::string (const std::string v)> fn_predicate)
//---------------------------------------------------------------------------------------------------------
{
   auto it = headers.find(key);
   if (it == headers.end())
      return notFoundValue;
   else
      return fn_predicate(it->second);
}

std::string trim(const std::string &str, std::string chars)
//---------------------------------------------------------
{
   if (str.length() == 0)
      return str;
   unsigned long b = str.find_first_not_of(chars);
   unsigned long e = str.find_last_not_of(chars);
   if (b == std::string::npos) return "";
   return std::string(str, b, e - b + 1);
}

bool copyfile(std::string source, std::string dest)
//-------------------------------------------------
{
   std::ifstream  src(source, std::ios::binary);
   std::ofstream  dst(dest,   std::ios::binary);
   dst << src.rdbuf();
   return dst.good();
}

//template<typename T>
//inline uint64_t to_big_endian64(const T val)
////--------------------------------
//{
//   uint64_t v = val;
//   return htobe64(v);
//}
//
//template<typename T>
//T from_big_endian64(const uint64_t v)
////-----------------------------
//{
//   T vv = (T) be64toh(v);
//   return vv;
//}
//
//template<typename T>
//uint32_t to_big_endian32(const T val)
////-------------------------------------------
//{
//   uint32_t v = val;
//   return htonl(v);
//}
//
//template<typename T>
//T from_big_endian32(const uint32_t v)
////-----------------------------
//{
//   T vv = (T) ntohl(v);
//   return vv;
//}
