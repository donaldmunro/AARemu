#include <string>

std::string trim(const std::string &str, std::string chars)
//------------------------------------------
{
   if (str.length() == 0)
      return str;
   unsigned long b = str.find_first_not_of(chars);
   unsigned long e = str.find_last_not_of(chars);
   if (b == std::string::npos) return "";
   return std::string(str, b, e - b + 1);
}

