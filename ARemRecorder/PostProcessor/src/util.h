#ifndef POSTPROCESSOR_UTIL_H
#define POSTPROCESSOR_UTIL_H

#include <cstdio>
#include <cstdint>
#include <string>
#include <unordered_map>
#include <functional>
#include <memory>

void to_big_endian64(const unsigned char *src, uint64_t &result);

void to_big_endian32(const unsigned char *src, uint32_t &result);

void from_big_endian64(const uint64_t &v, unsigned char *dst);

void from_big_endian32(const uint32_t &v, unsigned char *dst);

//C++14
//template <typename V>
//const std::function< V (const V* v) > defaultHeaderPredTempl = [](const V* v) -> V { return v; };
//template <typename K, typename V>
//V headerValue(const std::unordered_map<K, V> m, const K key, const V notFoundValue,
//              std::function< V (const V* v) > fn_predicate = defaultHeaderPredTempl);

const std::function<std::string (const std::string v)> defaultHeaderPred = [](std::string v) -> std::string { return v; };
std::string headerValue(const std::unordered_map<std::string, std::string> headers, const std::string key,
                        const std::string notFoundValue,
                        std::function<std::string (const std::string v)> fn_predicate = defaultHeaderPred);
std::string trim(const std::string &str, std::string chars =" \t");

#if defined(_WIN32) || defined(_WIN64)
#pragma warning(disable : 4996)
#endif
template<typename ... Args> // http://stackoverflow.com/questions/2342162/stdstring-formatting-like-sprintf#comment61134428_2342176
std::string string_format(const std::string& format, Args ... args)
//-----------------------------------------------------------------
{
   int size = snprintf(nullptr, 0, format.c_str(), args ... ) + 1; // Extra space for '\0'
   std::unique_ptr<char[]> buf( new char[ size ] );
   snprintf( buf.get(), size, format.c_str(), args ... );
   return std::string( buf.get(), buf.get() + size - 1 ); // We don't want the '\0' inside
}

bool copyfile(std::string source, std::string dest);

#endif //POSTPROCESSOR_UTIL_H
