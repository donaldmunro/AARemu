#include <stdlib.h>
#include <math.h>

#include <iostream>
#include <memory>
#include <limits>

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#include "processing.h"
#include "Orientation.h"
#include "RingBuffer.h"
#include "util.h"
#include "cv.h"

//#define DEBUG

#ifdef DEBUG
#include <opencv2/highgui/highgui.hpp>
#endif

static bool isDebug = false;

extern bool is_verbose;

inline bool isWrap(float previousBearing, float bearing)
//------------------------------------------------------
{
   return ( ((previousBearing >= 355) && (previousBearing <= 359.9999999)) &&
            ((bearing >=0) && (bearing <=10)) );
}

bool process_orientation(filesystem::path dir, std::unordered_map<std::string, std::string> headers,
                         std::string recording_type, std::string& orientationFileOut, int& count)
//------------------------------------------------------------------------
{
   filesystem::path orientation_file(headerValue(headers, "OrientationFile", "",
                                     [&dir](std::string v) -> std::string { return dir.str() + "/" + v; }));
   if ( (orientation_file.empty()) || (! orientation_file.exists()) )
      orientation_file = filesystem::path(dir.str() + "/orientation");
   if ( (! orientation_file.exists()) || (orientation_file.file_size() == 0) )
   {
      std::cerr << "WARNING: Orientation file not found. Skipping" << std::endl;
      orientationFileOut = "";
      return false;
   }
   float startBearing = -1, rangeCheck = 8;
   bool isMonotonic;
   if (recording_type == "THREE60")
   {
      errno = 0;
      startBearing = strtof(trim(headerValue(headers, "StartBearing", "-1")).c_str(), nullptr);
      if (errno)
         startBearing = -1;
      isMonotonic = true;
   }
   else
      isMonotonic = false;

   int kernelSize =0;
   count = 0;
   bool mustFilter = (kernelSize > 0);
   long N = 0;
   if ( (mustFilter) && ((kernelSize % 2) == 0) )
      kernelSize++;
   filesystem::path f(orientation_file.str() + ".smooth");
   std::unique_ptr<RingBuffer<OrientationData>> buff;
   std::unique_ptr<std::vector<OrientationData>> L;
   const int center = kernelSize / 2;
   if (mustFilter)
   {
      buff.reset(new RingBuffer<OrientationData>(kernelSize));
      L.reset(new std::vector<OrientationData>(kernelSize));
   }
   float previousBearing = -1;
   std::ifstream dis(orientation_file.str());
   if (! dis.good())
   {
      std::cerr << "Error opening orientation file " << orientation_file.str() << std::endl;
      orientationFileOut = "";
      return false;
   }

   OrientationData data;
   data.read(dis);

   int n = 0;
   if (startBearing >= 0)
   {
      while (! dis.eof())
      {
         float bearing = data.bearing;
         if (bearing >= startBearing)
            break;
         data.read(dis);
         n++;
      }
      if (dis.eof())
      {
         std::cerr << "Could not find start bearing " << startBearing << "in orientation file " << orientation_file.str()
                   << std::endl;
         orientationFileOut = "";
         return false;
      }
   }

   const std::streamsize length((sizeof(long) + sizeof(float) * 20 + sizeof(int))*1000);
   char buffer[length];
   std::ofstream dos;
   dos.rdbuf()->pubsetbuf(buffer, length);
   dos.open(f.str(), std::ios::binary | std::ios::trunc);
   if (! dos.good())
   {
      std::cerr << "Warning: Error creating smoothed orientation file " << f.str() << std::endl;
      orientationFileOut = orientation_file.str();
      return true;
   }
   std::unique_ptr<std::ofstream> pw;
   if (isDebug)
   {
      pw.reset(new std::ofstream("bearingstamps.txt"));
      if (! pw->good())
      {
         std::cerr << "WARNING: Error creating orientation debug file bearingstamps.txt" << std::endl;
         pw.reset(nullptr);
      }
   }
   int outOfRange = 0;
   bool isStarted = false;
   std::streampos offset = dis.tellg();
   while (! dis.eof())
   {
      n++;
      float bearing = data.bearing;
      if ( (! isStarted) &&  (previousBearing >= 0) && (floorf(bearing) != floorf(startBearing)) )
         isStarted = true;
      else if ( (isStarted) && (floorf(bearing) == floorf(startBearing)) )
         break;

      if ( (previousBearing >= 0) && ( (isMonotonic) || (rangeCheck > 0) ) )
      {
         float bearingDiff = bearing - previousBearing;
         bool isWrapped = isWrap(previousBearing, bearing);
         if (isWrapped)
            bearingDiff += 360;
         if ( (isMonotonic) && (! isWrapped) && (floorf(bearing) < floorf(previousBearing)) )
         {
            if (pw)
               *pw << "-- " << bearing << " " << data.timestamp << std::endl;
            data.read(dis);
            continue;
         }
         if ( (rangeCheck > 0) && (fabsf(bearingDiff) > rangeCheck) )
         {
            if (pw)
               *pw << "++ " << bearing << " " << data.timestamp << std::endl;

            if (outOfRange++ == 0)
               offset = dis.tellg();
            else if (outOfRange > 3)
            {
               outOfRange = 0;
               dis.seekg(offset);
               data.read(dis);
               if (dis.good())
                  previousBearing = data.bearing;
               else
                  previousBearing = bearing;
            }
            data.read(dis);
            continue;
         }
      }
      previousBearing = bearing;

      if (mustFilter)
      {
         N++;
         if (N > kernelSize)
         {
            buff->peekList(*L);
            buff->push(data);
            data = (*L)[center];
            float ave = 0;
            for (int i = 0; i < kernelSize; i++)
               ave += (*L)[i].bearing;
            ave /= kernelSize;
            data.write(dos, ave);
            count++;
         }
         else
         {
            buff->push(data);
            if (N < kernelSize / 2)
            {
               data.write(dos, data.bearing);
               count++;
            }
         }
      }
      else
      {
         if (pw)
            *pw << bearing << " " << data.timestamp << std::endl;
         data.write(dos, bearing);
         count++;
      }
      data.read(dis);
   }
   dis.close();
   if (mustFilter)
   {
      int left = buff->peekList(*L);
      for (int i=center+1; i<left; i++)
      {
         data = (*L)[i];
         data.write(dos, data.bearing);
         count++;
      }
   }
   dos.close();
   orientationFileOut = f.str();
   return true;
}

long readFrame(std::ifstream& in, long& timestamp, int &size, unsigned char *data, long filepos =-1, bool isCompressed =false)
//----------------------------------------------------------------------------------------------------------------------------
{
   if (filepos >= 0)
      in.seekg(filepos, std::ios_base::beg);
   uint64_t v;
   in.read((char *) &v, sizeof(uint64_t));
   if (! in.good())
      return -1L;
   int64_t ts;
   from_big_endian64(v, (unsigned char *) &ts);
   timestamp = (int64_t) ts;

   in.read((char *) &v, sizeof(uint64_t));
   if ( (! in.good()) || (in.eof()) )
      return -1L;
   uint64_t iv;
   from_big_endian64(v, (unsigned char *) &iv);
   size = (int) iv;
   bool ok = true;
   if (size > 0)
   {
/*      if (isCompressed)
      {

         char* compressed = new char[size];
         in.read(compressed, size);
         if (! in.bad())
            ok = snappy::RawUncompress(compressed, size, (char *) data);
         delete[] compressed;
      }
      else*/
      {
         in.read((char *) data, size);
         ok = ( (! in.bad()) && (in.gcount() == size) );
      }
   }
   if (! ok)
      return -1L;
   return in.tellg();
}

void writeFrame(std::ofstream& frameout, long timestamp, cv::Mat* frame, int size, bool isCompress =false)
//--------------------------------------------------------------------------------------------------------
{
   uint64_t v;
   to_big_endian64((const unsigned char *) &timestamp, v);
   frameout.write((const char *) &v, sizeof(uint64_t));
   uint64_t write_size;
   if (frame == nullptr)
   {
      write_size = 0;
      to_big_endian64((const unsigned char *) &write_size, v);
      frameout.write((const char *) &v, sizeof(uint64_t));
   }
   else
   {
      char *write_data;
      uchar *data = frame->data;
/*      if (isCompress)
      {
         write_data = new char[snappy::MaxCompressedLength(size)];
         size_t sz;
         snappy::RawCompress((const char *) data, size, write_data, &sz);
         write_size = (int) sz;
      }
      else*/
      {
         write_size = (uint64_t) size;
         write_data = (char *) data;
      }
      to_big_endian64((const unsigned char *) &write_size, v);
      frameout.write((const char *) &v, sizeof(uint64_t));
      frameout.write(write_data, write_size);
//      if (isCompress) delete write_data;
   }
}

bool convert_frames(filesystem::path dir, std::unordered_map<std::string, std::string> headers,
                    std::string recording_type, int w, int h, int yuvSize, int rgbaSize,
                    int& shift_totalx, int& shift_totaly, int& framecount)
//---------------------------------------------------------------------------------------------
{
   filesystem::path raw_file(dir.str() + "/frames.RAW");
   if (is_verbose)
      std::cout << "Converting " << raw_file.make_absolute().str() << " to RGBA" << std::endl;
   if (! raw_file.exists())
   {
      std::cerr << "Raw frames file " << raw_file.str() << " not found. Aborting" << std::endl;
      return false;
   }
   filesystem::path frames_file(dir.str() + "/frames.RGBA");
   bool isRemoveRepeats = (recording_type == "THREE60");
   std::string format = trim(headerValue(headers, "RawBufferFormat", ""));
   if (format == "")
   {
      std::cerr << "Header file has no  RawBufferFormat key. Aborting" << std::endl;
      return false;
   }
   std::unique_ptr<std::ofstream> pw;
   if (isDebug)
   {
      filesystem::path debug_file(dir.str() + "/framestamps.txt");
      pw.reset(new std::ofstream(debug_file.str()));
   }

   std::ifstream yuvfile(raw_file.str());
   std::ofstream rgbafile(frames_file.str());
   shift_totalx = shift_totaly = framecount = 0;
   std::unique_ptr<unsigned char> YUV(new unsigned char [(w * h * 12) / 8]);
   int size;
   long offset;
   cv::ColorConversionCodes convertColorCode, convertGreyCode;
   if (format == "NV21")
   {
      convertColorCode = cv::COLOR_YUV2RGBA_NV21;
      convertGreyCode = cv::COLOR_YUV2GRAY_NV21;
   }
   else
   {
      convertColorCode = cv::COLOR_YUV2RGBA_I420;
      convertGreyCode = cv::COLOR_YUV2GRAY_I420;
   }
   long ts, ts2;
   offset = readFrame(yuvfile, ts, size, YUV.get());
   if (offset < 0)
   {
      std::cerr << "WARNING: Empty Raw frames file." << std::endl;
      return true;
   }
   cv::Mat yuv(h+h/2, w, CV_8UC1, YUV.get());
   cv::Mat frame(h, w, CV_8UC4),
           grey(h, w, CV_8UC1);
   cv::cvtColor(yuv, frame, convertColorCode);
#ifdef DEBUG
   cv::imwrite("rgba.png", frame);
#endif
   if (isRemoveRepeats)
      cv::cvtColor(yuv, grey, convertGreyCode);
   std::vector<long> duplicateTimestamps;
   double psnr;
   int n = 0;
   std::cout << " .";
   while ( (offset = readFrame(yuvfile, ts2, size, YUV.get())) >= 0 )
   {
      std::cout << "." << std::flush;
      n++;
      yuv = cv::Mat(h+h/2, w, CV_8UC1, YUV.get());
      cv::Mat nextframe(h, w, CV_8UC4),
              nextGrey(h, w, CV_8UC1);
      cv::cvtColor(yuv, nextframe, convertColorCode);

#ifdef DEBUG
      cv::imwrite("rgba-1.png", frame);
      cv::imwrite("rgba-2.png", nextframe);

      uchar *data = frame.data;
      cv::Mat x(h, w, CV_8UC4, data);
      cv::imwrite("rgba-3.png", x);
#endif

      if (isRemoveRepeats)
         cv::cvtColor(yuv, nextGrey, convertGreyCode);
      psnr = cvutil::psnr(frame, nextframe);
      if (isRemoveRepeats)
      {
         if ( (psnr == 0) || (psnr > 32) )
         {
            duplicateTimestamps.push_back(ts2);
            continue;
         }
         int shiftx, shifty;
         cvutil::shift(grey, nextGrey, shiftx, shifty);
         if (shiftx < 0)
         {
//            duplicateTimestamps.push_back(ts2);
            if (pw)
               *pw << "T   " << ts << " " << shiftx << std::endl;
            continue;
         }
         else
         {
            shift_totalx += shiftx;
            shift_totaly += shifty;
            framecount++;
         }
      }
      else if ( (psnr == 0) || (psnr > 32) )
      {
         duplicateTimestamps.push_back(ts2);
         continue;
      }
      if (pw)
         *pw << ts << std::endl;
      writeFrame(rgbafile, ts, &frame, rgbaSize);
      if (! duplicateTimestamps.empty())
      {
         for (long timestamp : duplicateTimestamps)
         {
            writeFrame(rgbafile, timestamp, nullptr, rgbaSize);
            if (pw)
               *pw << "D   " << timestamp << std::endl;
         }
         duplicateTimestamps.clear();
      }
      nextframe.copyTo(frame);
      nextGrey.copyTo(grey);
      ts = ts2;
   }
   std::cout << std::endl;
   if (pw)
      pw->close();
   yuvfile.close();
   rgbafile.close();
   return true;
}