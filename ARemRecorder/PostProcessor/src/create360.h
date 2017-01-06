#ifndef POSTPROCESSOR_CREATE360_H
#define POSTPROCESSOR_CREATE360_H

#include <unordered_map>
#include <memory>

#include "path.h"

struct FrameData
{
   long timestamp =0, fileOffset =0;
   int size = 0;
   std::shared_ptr<unsigned char> data;

   long readFrame(std::ifstream& in, long filepos =-1, bool isCompressed =false);
};

bool create360(const filesystem::path &dir, const filesystem::path &headerFile, const filesystem::path &frames_file,
               const filesystem::path &orientation_file, const int orientationCount, const float startIncrement,
               const float endIncrement, const int shift_totalx, const int shift_totaly, const bool is_stitch,
               const int maxKludges, const std::unordered_map<std::string, std::string> &headers);

#endif //POSTPROCESSOR_CREATE360_H
