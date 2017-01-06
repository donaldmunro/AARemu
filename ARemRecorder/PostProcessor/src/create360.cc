#include <iostream>
#include <iomanip>
#include <fstream>

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#include <opencv2/highgui.hpp>

#include "create360.h"
#include "cv.h"
#include "util.h"
#include "Orientation.h"

extern bool is_verbose;

long FrameData::readFrame(std::ifstream &in, long filepos, bool isCompressed)
//---------------------------------------------------------------------------
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
         {
            frameData.data.reset(new unsigned char[rgbaSize]);
            ok = snappy::RawUncompress(compressed, size, (char *) frameData.data.get());
         }
         delete[] compressed;
      }
      else */
      {
         data.reset(new unsigned char[size]);
         std::streamsize len = 0;
         while ( (len < size) && (in.good()) )
         {
            in.read((char *) data.get(), size);
            len += in.gcount();
         }
         ok = ( (in.good()) && (len == size) );
      }
   }
   if (! ok)
      return -1L;
   fileOffset = in.tellg();
   return fileOffset;
}

bool readRGBA(std::fstream& raf, long offset, int size, int width, int height, cv::Mat &rgba, cv::Mat &bw)
//-------------------------------------------------------------------------------------------------------
{
   raf.seekg(offset, std::ios::beg);
   if (! raf.good())
   {
      std::cerr << "Could not seek to  location " << offset;
      return false;
   }
   char startFrameBuf[size];
   raf.read((char *) startFrameBuf, size);
   if (! raf.good())
   {
      std::cerr << "Could not read " << size << " bytes ";
      return false;
   }
   rgba = cv::Mat(height, width, CV_8UC4, startFrameBuf);
   cv::cvtColor(rgba, bw, cv::COLOR_RGBA2GRAY);
   return true;
}

void syncLastFrame(filesystem::path recordingDir, filesystem::path framesFile, int previewWidth, int previewHeight,
                   int rgbaBufferSize, long startOffset, long stopOffset, std::string format, int shift_mean,
                   long startTimestamp, bool mustStitch)
//----------------------------------------------------------------------------------------------------------------
{
   filesystem::path rawFile(recordingDir.str() + "/frames.RAW");
   std::ifstream raw(rawFile.str());
   if (! raw.good())
   {
      std::cerr << "Could not open RAW frames file " << rawFile.str() << " in syncLastFrame" << std::endl;
      return;
   }
   std::fstream raf(framesFile.str(), std::ios::in | std::ios::out | std::ios::binary);
   if (! raf.good())
   {
      std::cerr << "Could not open frames file " << framesFile.str() << " in syncLastFrame" << std::endl;
      return;
   }
   try
   {
      cv::Mat startFrame(previewHeight, previewWidth, CV_8UC4), startFrameBW,
              nextFrame(previewHeight, previewWidth, CV_8UC4), nextFrameBW,
              lastFrame(previewHeight, previewWidth, CV_8UC4), lastFrameBW;
      if (! readRGBA(raf, startOffset * rgbaBufferSize, rgbaBufferSize, previewWidth, previewHeight,
                     startFrame, startFrameBW))
      {
         std::cerr << " in frames file " << framesFile.str() << " in syncLastFrame" << std::endl;
         return;
      }
      if (! readRGBA(raf, (startOffset + 1) * rgbaBufferSize, rgbaBufferSize, previewWidth, previewHeight,
                     nextFrame, nextFrameBW))
      {
         std::cerr << " in frames file " << framesFile.str() << " in syncLastFrame" << std::endl;
         return;
      }
      if (! readRGBA(raf, stopOffset * rgbaBufferSize, rgbaBufferSize, previewWidth, previewHeight,
                     lastFrame, lastFrameBW))
      {
         std::cerr << " in frames file " << framesFile.str() << " in syncLastFrame" << std::endl;
         return;
      }

      int shiftx, shifty;
      int sh, shift_min = std::numeric_limits<int>::max();
      // First compare last and first frames from written frame file
      cvutil::shift(lastFrameBW, startFrameBW, shiftx, shifty);
      if (shiftx > 0)
      {
         sh = shiftx - shift_mean; sh *= sh;
         shift_min = sh;
      }
      FrameData frameBufData;
      long pos = frameBufData.readFrame(raw);
      cv::ColorConversionCodes convertCode; //, convertGreyCode;
      if (format == "NV21")
      {
         convertCode = cv::COLOR_YUV2RGBA_NV21;
//         convertGreyCode = cv::COLOR_YUV2GRAY_NV21;
      }
      else
      {
         convertCode = cv::COLOR_YUV2RGBA_I420;
//         convertGreyCode = cv::COLOR_YUV2GRAY_I420;
      }
      cv::Mat frameMatch;
      while ( (pos >= 0) && (frameBufData.timestamp < startTimestamp) )
      {
         char *rawdata = nullptr;
         if ( (frameBufData.timestamp < 0) ||  (! frameBufData.data) )
         {
            pos = frameBufData.readFrame(raw);
            continue;
         }
         else
            rawdata = (char *) frameBufData.data.get();
         cv::Mat yuv(previewHeight+previewHeight/2, previewWidth, CV_8UC1, rawdata);
         cv::Mat lastRawFrameRGBA(previewHeight, previewWidth, CV_8UC4);
         cv::cvtColor(yuv, lastRawFrameRGBA, convertCode);
//         cv::imwrite("yuv.png", lastRawFrameRGBA);
         //lastFrame = cv::Mat(previewHeight, previewWidth, CV_8UC1, frameBufData.data.get());
         cv::Mat lastRawFrameBW(previewHeight, previewWidth, CV_8UC1);
         cv::cvtColor(lastRawFrameRGBA, lastRawFrameBW, cv::COLOR_RGBA2GRAY);
         cvutil::shift(lastRawFrameBW, startFrameBW, shiftx, shifty);
         if (shiftx > 0)
         {
            sh = shiftx - shift_mean; sh *= sh;
            if (sh < shift_min)
            {
               shift_min = sh;
               lastRawFrameRGBA.copyTo(frameMatch);
               if (sh == 0)
                  break;
            }
         }
         pos = frameBufData.readFrame(raw);
      }
      if (! frameMatch.empty())
      {
         raf.seekp(stopOffset * rgbaBufferSize, std::ios::beg);
         if (mustStitch)
         {
            cv::Mat stitchedFrame;
            if (cvutil::stitch3(frameMatch, startFrame, nextFrame, stitchedFrame))
            {
               raf.write((const char *) stitchedFrame.data, previewWidth * previewHeight * 4);
#ifdef DEBUG
               cv::imwrite("1.png", frameMatch); cv::imwrite("2.png", startFrame);
               cv::imwrite("3.png", nextFrame); cv::imwrite("stitch.png", stitchedFrame);
#endif
            }
         }
         else
            raf.write((const char *) frameMatch.data, previewWidth * previewHeight * 4);
      }
   }
   catch (std::exception &e)
   {
      std::cerr << "Exception: " << e.what() << std::endl;
      return;
   }
   raf.close();
   raw.close();
}

bool createThree60(const filesystem::path& recordingDir, std::ifstream& rgbaIn, std::ifstream& orientationIn,
                   std::unordered_map<std::string, std::string> headers, const float recordingIncrement,
                   const int no, const int orientationCount, const int shiftx, const int shifty,
                   filesystem::path& output, int& kludgeCount, const bool mustStitch, const int maxKludges)
//------------------------------------------------------------------------------------------------------------------
{
   int increments = static_cast<int>(floorf(360.0f / recordingIncrement));
   int shift_mean = shiftx / increments;
   filesystem::path framesFile(recordingDir.str() + "/" + string_format("%s.frames.%.1f", recordingDir.filename().c_str(),
                                                                        recordingIncrement));

   kludgeCount = 0;
   std::ofstream raf(framesFile.str().c_str());
   int n = 0, N = 0;
   OrientationData orientationData;
   if (! orientationData.read(orientationIn))
      return false;
   long videoStartTimestamp = -1;
   std::string format = trim(headerValue(headers, "RawBufferFormat", ""));
   const int previewWidth = (const int) strtol(trim(headerValue(headers, "PreviewWidth", "-1")).c_str(), nullptr, 10);
   const int previewHeight = (const int) strtol(trim(headerValue(headers, "PreviewHeight", "-1")).c_str(), nullptr, 10);
   const int rgbaBufferSize = previewWidth*previewHeight*4;
   float startBearing = strtof(trim(headerValue(headers, "StartBearing", "-1")).c_str(), nullptr);
   if (startBearing < 0)
   {
      startBearing = orientationData.bearing;
      videoStartTimestamp = orientationData.timestamp;
   }
   float currentBearing = startBearing;
   long currentOffset = static_cast<long>(floorf(startBearing / recordingIncrement));
   long startOffset = currentOffset;
   float stopBearing = startBearing - recordingIncrement;
   if (stopBearing < 0)
      stopBearing += 360;
   long stopOffset = static_cast<long>(floorf(stopBearing / recordingIncrement));

   if (videoStartTimestamp < 0)
   {
      while (! orientationIn.eof())
      {
         float bearing = orientationData.bearing;
         if (bearing >= startBearing)
         {
            videoStartTimestamp = orientationData.timestamp;
            break;
         }
         if (! orientationData.read(orientationIn))
            break;
      }
   }

   std::vector<OrientationData> orientationsPerBearing;
   std::shared_ptr<unsigned char> frame(new unsigned char[rgbaBufferSize]), lastFrame, lastFrames[2];
   long lastFrameOffsets[2];
   long nextTime, frameno = 0, lastFrameno =-1;
   const int LAST_FRAME = 0, FRAME_BEFORE_LAST = 1;
   lastFrameOffsets[LAST_FRAME] = -1; lastFrameOffsets[FRAME_BEFORE_LAST] = -1;
   long lastValidOffset = 0;
   int kludgeTranslate = shift_mean;
   std::string action, cvaction;
   try
   {
      while (orientationIn.good())
      {
         N++;
         float bearing = orientationData.bearing; //, lastBearing = orientationData.bearing;
         long bearingOffset = static_cast<long>(floorf(bearing / recordingIncrement));
         orientationsPerBearing.clear();
         action = "Reading all bearing for offset " + currentOffset;
         while ( (orientationIn.good()) && (bearingOffset == currentOffset) )
         {
            OrientationData data(orientationData);
            orientationsPerBearing.push_back(data);
            if (! orientationData.read(orientationIn))
               break;
            bearing = orientationData.bearing;
            bearingOffset = static_cast<long>(floorf(bearing / recordingIncrement));
         }

         nextTime = orientationData.timestamp + 300000000L;
         lastFrame = lastFrames[LAST_FRAME];
         FrameData frameBufData;
         long fileOffset = frameBufData.readFrame(rgbaIn);
         if (fileOffset < 0)
            return false;
         long frameOffset = -1;
         long minOrientationMatch = std::numeric_limits<long>::max();
         int shift_min = std::numeric_limits<int>::max(); //, matchShift1 = -1;
         std::shared_ptr<unsigned char> matchFrame, matchFrame2;
         long matchTs = -1, matchTs2 = -1, matchFrameno = -1, matchFrameno2 = -1, matchOffset =-1, matchOffset2 =-1;
         action = string_format("Reading frames < %ld (%.3f)", nextTime, currentBearing);
         while ( (fileOffset >= 0) && (frameBufData.timestamp < nextTime) )
         {
            long frameTimestamp = frameBufData.timestamp;
            if (frameTimestamp < 0)
            {
               fileOffset = frameBufData.readFrame(rgbaIn);
               continue;
            }

            if (frameBufData.data)
            {
               frame = frameBufData.data;
               if ( (frame) && (lastFrame)  )
               {
                  int xshift, yshift;
                  cvaction = string_format("greyShift (%.3f)", currentBearing);
                  cvutil::greyShift(previewWidth, previewHeight, lastFrame.get(), frame.get(), xshift, yshift);
                  cvaction = "";
                  if (xshift > 0)
                  {
                     int sh = xshift - shift_mean; sh *= sh;
                     if (sh < shift_min)
                     {
                        shift_min = sh;
                        matchFrame = frame;
//                        matchShift1 = xshift;
                        matchFrameno = frameno;
                        matchTs = frameBufData.timestamp;
                        matchOffset = frameBufData.fileOffset;
                     }
                  }
               }
               if (frame)
               {
                  for (OrientationData od : orientationsPerBearing)
                  {
                     long timediff = labs(od.timestamp - frameTimestamp);
                     if ( (timediff < minOrientationMatch) && (frameno > lastFrameno) )
                     {
                        minOrientationMatch = timediff;
                        matchFrame2 = frame;
                        matchFrameno2 = frameno;
                        matchTs2 = frameBufData.timestamp;
                        matchOffset2 = frameBufData.fileOffset;
                     }
                  }
               }
               frameno++;
            }

            fileOffset = frameBufData.readFrame(rgbaIn);
         }
         if (! matchFrame)
         {
            matchFrame = matchFrame2;
            matchFrameno = matchFrameno2;
            matchTs = matchTs2;
            matchOffset = matchOffset2;
         }
         int matchShift2 = -1;
         if ( (matchFrame != matchFrame2) && (shift_min > 0) )
         {
            int xshift, yshift;
            cvaction = string_format("greyShift (%.3f) (2)", currentBearing);
            cvutil::greyShift(previewWidth, previewHeight, lastFrame.get(), matchFrame2.get(), xshift, yshift);
            cvaction = "";
            matchShift2 = xshift;
            int sh = matchShift2 - shift_mean; sh *= sh;
            if (sh < shift_min)
            {
               shift_min = sh;
               matchFrame = matchFrame2;
               matchFrameno = matchFrameno2;
               matchTs = matchTs2;
               matchOffset = matchOffset2;
            }
         }

         if (matchFrame)
         {
            frameOffset = matchOffset;
            std::shared_ptr<unsigned char> &beforeLastFrame = lastFrames[FRAME_BEFORE_LAST];
            if ( (mustStitch) && (lastFrame) && (beforeLastFrame) )
            {
               std::unique_ptr<unsigned char> stitchedFrame(new  unsigned char[rgbaBufferSize]);
               cvaction = string_format("stitch3 (%.3f)", currentBearing);
               if (cvutil::stitch3(previewWidth, previewHeight, beforeLastFrame.get(), lastFrame.get(),
                                   matchFrame.get(), stitchedFrame.get()))
               {
                  raf.seekp(lastFrameOffsets[LAST_FRAME] * rgbaBufferSize, std::ios_base::beg);
                  raf.write((const char *) stitchedFrame.get(), rgbaBufferSize);
               }
               cvaction = "";
            }
            raf.seekp(currentOffset * rgbaBufferSize, std::ios_base::beg);
            lastFrameno = matchFrameno;
            raf.write((const char *) matchFrame.get(), rgbaBufferSize);
            lastFrames[FRAME_BEFORE_LAST] = lastFrames[LAST_FRAME];
            lastFrames[LAST_FRAME] = matchFrame;
            lastFrameOffsets[FRAME_BEFORE_LAST] = lastFrameOffsets[LAST_FRAME];
            lastFrameOffsets[LAST_FRAME] = currentOffset;
            if (is_verbose)
               std::cout << n << ": Wrote offset " << currentOffset << " bearing " << currentBearing <<
                         " frame timestamp " << matchTs << std::endl;
            n++;
            rgbaIn.seekg(frameOffset, std::ios_base::beg);
            lastValidOffset = frameOffset;
            kludgeTranslate = shift_mean;
         }
         else
         {
            kludgeCount++;
            if (kludgeCount > maxKludges)
               return false;
            std::shared_ptr<unsigned char> kludgedFrame(new unsigned char[rgbaBufferSize]);
            if (! lastFrame)
               lastFrame = frameBufData.data;
            cvaction = string_format("kludgeRGBA (%.3f)", currentBearing);
            cvutil::kludgeRGBA(previewWidth, previewHeight, lastFrame.get(), kludgeTranslate, true, kludgedFrame.get());
            cvaction = "";
            kludgeTranslate += shift_mean;
            raf.seekp(currentOffset * rgbaBufferSize, std::ios_base::beg);
            raf.write((const char *) kludgedFrame.get(), rgbaBufferSize);
            lastFrames[FRAME_BEFORE_LAST] = lastFrames[LAST_FRAME];
            lastFrames[LAST_FRAME] = kludgedFrame;
            lastFrameOffsets[FRAME_BEFORE_LAST] = lastFrameOffsets[LAST_FRAME];
            lastFrameOffsets[LAST_FRAME] = currentOffset;
            if (is_verbose)
               std::cout << "******** " << n << ": Kludged offset " << currentOffset << " bearing " << currentBearing << std::endl;
            n++;
            rgbaIn.seekg(lastValidOffset, std::ios_base::beg);

         }
//         lastBearing = currentBearing;
         currentOffset = bearingOffset;
         currentBearing = bearing;
         orientationsPerBearing.clear();
      }
      raf.close();

      if (currentOffset == stopOffset)
         syncLastFrame(recordingDir, framesFile, previewWidth, previewHeight, rgbaBufferSize, startOffset,
                       stopOffset, format, shift_mean, videoStartTimestamp, mustStitch);
      if ( (n >= no) && (currentOffset == stopOffset) )
         return true;
      //return (n >= no) ? framesFile : null;
   }
   catch (std::exception &e)
   {
      std::cerr << "Exception: " << e.what() << " " << action << " " << cvaction << std::endl;
      exit(1);
   }
   return false;
}

bool create360(const filesystem::path &dir, const filesystem::path &headerFile, const filesystem::path &frames_file,
               const filesystem::path &orientation_file, const int orientationCount, const float startIncrement,
               const float endIncrement, const int shift_totalx, const int shift_totaly, const bool is_stitch,
               const int maxKludges, const std::unordered_map<std::string, std::string> &headers)
//-----------------------------------------------------------------------------------------------------------------------
{
   std::string format = trim(headerValue(headers, "RawBufferFormat", ""));
   filesystem::path raw_file(dir.str() + "/frames.RAW");
   const std::string orientation_filename = orientation_file.make_absolute().str();
   if ( (! orientation_file.exists()) || (orientation_file.file_size() == 0) )
   {
      std::cerr << "Error creating rotational recording file - could not open orientation file " <<
                orientation_filename << std::endl;
      return false;
   }
   std::ifstream rgbain(frames_file.str()), orientin(orientation_filename);
   if (! rgbain.good())
   {
      std::cerr << "Error creating rotational recording file - could not open frames file " <<
                frames_file.make_absolute().str() << std::endl;
      return false;
   }
   if (! orientin.good())
   {
      std::cerr << "Error creating rotational recording file - could not open orientation file " <<
                orientation_filename << std::endl;
      return false;
   }

   std::unique_ptr<std::ifstream> rawin;
   if ( (raw_file.exists()) && (raw_file.file_size() > 0) )
   {
      rawin.reset(new std::ifstream(raw_file.str()));
      if (! rawin->good())
         rawin.reset();
   }

   std::vector<float> recordingIncrements(5); // = { 1f, 1.5f, 2.0f, 2.5f, 3.0f };
   float start = 0.5, incr = 0.5f;
   std::generate(recordingIncrements.begin(), recordingIncrements.end(), [&start, incr]{ start += incr; return start; });
   const int incrementCount = static_cast<int>(recordingIncrements.size());
   std::vector<std::pair<int, int>> incrementResults((unsigned long) incrementCount);
   for (int j=0; j<incrementCount; j++)
      incrementResults[j] = std::make_pair<int, int>(std::numeric_limits<int>::max(), std::numeric_limits<int>::max());
   int kludgeCount = 0, n = std::stoi(trim(headerValue(headers, "No", "0")).c_str());

   for (int i=0; i<incrementCount; i++)
   {
      try
      {
         float increment = recordingIncrements[i];
         if ( (increment < startIncrement) || (increment > endIncrement) )
            continue;;
         int no = static_cast<int>(floorf(((float) (n + 1)) / increment));
         rgbain.seekg(0, std::ios::beg);
         orientin.seekg(0L, std::ios::beg);
         if (rawin)
            rawin->seekg(0L, std::ios::beg);
         kludgeCount = 0;
         filesystem::path ff;
         if (createThree60(dir, rgbain, orientin, headers, increment, no, orientationCount,
                           shift_totalx, shift_totaly, ff, kludgeCount, is_stitch, maxKludges))
         {
            if (is_verbose)
               std::cout << "startIncrement " << increment << " kludges " << kludgeCount << std::endl;
            incrementResults[i] = std::make_pair(i, kludgeCount);
            if (kludgeCount == 0)
               break;
         }
         else if (is_verbose)
            std::cout << "startIncrement " << increment <<  " error" << std::endl;

      }
      catch (std::exception &e)
      {
         std::cerr << "Exception: " << e.what() << std::endl;
      }

   }

   bool hasResult = false;
   for (int i = 0; i < incrementCount; i++)
   {
      if (incrementResults[0].first < std::numeric_limits<int>::max())
      {
         hasResult = true;
         break;
      }
   }
   if (!hasResult)
   {
      std::cout << "Error creating 360 rotational recording (no valid result found)" << std::endl;
      return false;
   }
   std::sort(std::begin(incrementResults), std::end(incrementResults),
             [](const std::pair<int, int> &lhs, const std::pair<int, int> &rhs) -> bool
                   //----------------------------------------------------------------------------------
             {
                return (lhs.second == rhs.second) ? (lhs.first < rhs.first) : (lhs.second < rhs.second);
             });
   filesystem::path f(dir.str() + "/" + string_format("%s.frames.%.1f", dir.filename().c_str(),
                                                      recordingIncrements[incrementResults[0].second]));
   filesystem::path frameFile(dir.str() + "/" + string_format("%s.frames", dir.filename().c_str()));
   if (f.exists())
      copyfile(f.str(), frameFile.str());
   std::cout << "360 rotational recording results" << std::endl;
   std::cout <<  string_format("%-12s%9s%s", "Increment", "Kludges ", "File") << std::endl;
   std::cout << "================================" << std::endl;
   std::cout <<  string_format("* %10.1f %-9d %s (%s)", recordingIncrements[incrementResults[0].second], incrementResults[0].first,
                               frameFile.str().c_str(), f.str().c_str()) << std::endl;
   int others = 0;
   for (int i=1; i<incrementCount; i++)
   {
      if (incrementResults[i].first < std::numeric_limits<int>::max())
      {
         filesystem::path f(dir.str() + "/" + string_format("%s.frames.%.1f", dir.filename().c_str(),
                                                            recordingIncrements[incrementResults[i].second]));
         std::cout <<  string_format("  %10.1f %-9d %s", recordingIncrements[incrementResults[i].second], incrementResults[i].first,
                                     f.str().c_str()) << std::endl;
         others++;
      }
   }
   std::cout << "* = Current default renamed as " << frameFile.str() << std::endl;
   if (others > 0)
      std::cout << ". To overide delete " << frameFile.filename() <<
                   " and copy/rename another candidate from the list above" << std::endl;
   std::ofstream hdrout(headerFile.str(), std::ofstream::app);
   hdrout << "FramesFile=" << frameFile.str().c_str() << std::endl;
   hdrout << "Increment=" << std::fixed << std::setprecision(1) << recordingIncrements[incrementResults[0].second] << std::endl;
   return true;
}