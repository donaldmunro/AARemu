
#include <sys/types.h>
#include <dirent.h>

#include <limits>
#include <cstddef>
#include <iostream>
#include <fstream>
#include <algorithm>
#include <unordered_map>
#include <map>
#include <unordered_set>
#include <set>
#include <string>

#include <opencv2/core/core.hpp>
#include <opencv2/core/mat.hpp>
#include <opencv2/core/ocl.hpp>
#include <opencv2/imgproc/imgproc.hpp>

using namespace std;
using namespace cv;

#include "postprocess.h"

float recording_increment = 1.0;
int buffersize = -1;
int width = -1, height = -1, no = 0;
float start_bearing = -1;
string dirpath(".");
unordered_set<long> skipped_bearings;

const double DEFAULT_SHIFT_AVERAGE = 10;
const double DEFAULT_SHIFT_DEVIATION = 4;

int main(int argc, char **argv)
//-----------------------------
{
   if ( (argc == 2) && (strcasecmp(argv[1], "-h") == 0))
   {
      cout << "Run postprocess in a directory containing the following files ({name} is the recording name)" << endl;
      cout << "or having the first argument as such a directory." << endl;
      cout << "{name}.head" << endl;
      cout << "{name}.frames.part" << endl;
      cout << "completed.bearings" << endl;
      return 1;
   }   
   if (argc == 2)
      dirpath = argv[1];
   string name = get_name(dirpath);
   if (name == "")
   {
      cerr << "Could not find file name ?????.head" << endl;
      return 1;
   }
   if ( (buffersize < 0) || (width < 0) || (height < 0) || (start_bearing < 0) )
   {
      cerr << "Header file " << (dirpath + "/" + name  + ".head") << " did not contain required header information" << endl;
      return 1;
   }
   check_opencl();
   string filename(dirpath + "/" + name + ".frames.part");
   fstream framesfile(filename, std::fstream::in | std::fstream::out);
   if (! framesfile.good())
   {
      std::cerr << "Error opening " << filename << std::endl;
      return 2;
   }
   no = (int) (360.0 / recording_increment);
   long completed_bearings[no];
   filename = dirpath + "/completed.bearings";

   ifstream f(filename);
   if (! f.good())
   {
      cerr << filename << " not found." << endl;
      return 1;
   }
   string first, second;
   getline(f, first, '=');
   while (! f.eof())
   {
      getline(f, second);
      try
      {
         completed_bearings[stol(first)] = stol(second);
      }
      catch (...)
      {
         cerr << "Error converting " << first << "=" << second << " in " << filename << endl;
         return 1;
      }
      getline(f, first, '=');
   }

   for (int off=0; off<no; off++)
   {
      if (completed_bearings[off] < 0)
         skipped_bearings.insert((long) off);
   }

   double average, deviation;
   if (! stats(framesfile, 1, 40, completed_bearings, nullptr, average, deviation))
      return 2;

   long start_offset = (long) (floor(start_bearing / recording_increment));
   long offset = start_offset;
   while (skipped_bearings.find(offset) != skipped_bearings.end())
   {
      start_bearing += recording_increment;
      if (start_bearing >= 360)
         start_bearing -= 360;
      offset = (long) (floor(start_bearing / recording_increment));
      if (offset == start_offset)
      {
         cerr << "No completed bearings ?" << endl;
         return 1;
      }
   }
   char buf[buffersize], buf2[buffersize];
   start_offset = offset;
   float bearing = start_bearing, previousBearing;
   readat(framesfile, offset, buf);
   Mat frame(height+height/2, width, CV_8UC1, buf), nextFrame(height+height/2, width, CV_8UC1);
   double badmaxshift = (int) round(average + deviation * 4.5);
   double badminshift = (int) max(round(average - deviation * 4.5), 1.0);
   int shiftx, shifty, cnt = 0;
   cout << "Checking for out of range frame skips:    ";
   do
   {
      progress(cnt++, no);
      previousBearing = bearing;
      bearing += recording_increment;
      if (bearing >= 360)
         bearing -= 360;
      offset = (long) (floor(bearing / recording_increment));
      if (offset == start_offset) break;
      if (skipped_bearings.find(offset) != skipped_bearings.end())
      {
         while ( (skipped_bearings.find(offset) != skipped_bearings.end()) &&
                 (offset != start_offset) )
         {
            bearing += recording_increment;
            if (bearing >= 360)
               bearing -= 360;
            offset = (long) (floor(bearing / recording_increment));
         }
         if (offset == start_offset)
            break;
         readat(framesfile, offset, buf);
         frame = Mat(height+height/2, width, CV_8UC1, buf);
         continue;
      }
      readat(framesfile, offset, buf2);
      nextFrame = Mat(height+height/2, width, CV_8UC1, buf2);
      shift(frame, height, width, nextFrame, shiftx, shifty);
      if ( (shiftx < badminshift) || (shiftx > badmaxshift) )
      {
         float next_bearing;
         int bearing_dist;
         if (! checkRot(framesfile, frame, bearing, average, deviation, start_offset, buffersize,
                        next_bearing, bearing_dist))
            break;
         if (next_bearing < 0)
            break;
         long nextOffset = (long) (floor(next_bearing / recording_increment));
         readat(framesfile, nextOffset, buf2);
         nextFrame = Mat(height+height/2, width, CV_8UC1, buf2);
         shift(frame, height, width, nextFrame, shiftx, shifty);
         if (shiftx < 1)
            break;
         shiftx /= bearing_dist;
         float shift_bearing = bearing;
         offset = (long) (floor(shift_bearing / recording_increment));
         stringstream msg;
         int kc = 1;
         Mat rgbaFrame;
         do
         {
            if (! do_shift(height, width, frame, shiftx*kc++, true, rgbaFrame))
            {
               msg << "Error shifting image for skipped bearing " << shift_bearing << endl;
               next_bearing = shift_bearing;
               nextOffset = (long) (floor(next_bearing / recording_increment));
               readat(framesfile, nextOffset, buf2);
               nextFrame = Mat(height+height/2, width, CV_8UC1, buf2);
               break;
            }
            else
               msg << "kludged frame at: " << shift_bearing << endl;
            if (skipped_bearings.find(offset) != skipped_bearings.end())
            {
               skipped_bearings.erase(offset);
               completed_bearings[(int) offset] = 1;
            }
            if (! writeKludgeFile(shift_bearing, rgbaFrame))
               msg << "Error writing Kludge file for " << shift_bearing << endl;
            shift_bearing += recording_increment;
            if (shift_bearing >= 360)
               shift_bearing -= 360;
            offset = (long) (floor(shift_bearing / recording_increment));
         } while ( (offset != nextOffset) && (offset != start_offset) );
         //cout << msg.str() << endl;
         if (offset == start_offset) break;
         bearing = next_bearing;
         offset = (long) (floor(bearing / recording_increment));
      }
      nextFrame.copyTo(frame);
   } while (offset != start_offset);
   progress(no, no);
   cout << endl;

   bearing = start_bearing;
   stringstream msg;
   Mat rgbaFrame;
   cnt = 0;
   cout << "Processing skipped bearing:   ";
   do
   {
      progress(cnt++, (int) skipped_bearings.size());
      previousBearing = bearing;
      bearing += recording_increment;
      if (bearing >= 360)
         bearing -= 360;
      offset = (long) (floor(bearing / recording_increment));
      if (skipped_bearings.find(offset) != skipped_bearings.end())
      {
         long previousOffset = (long) (floor(previousBearing / recording_increment));
         readat(framesfile, previousOffset, buf);
         Mat frame(height+height/2, width, CV_8UC1, buf);
         if (! do_shift(height, width, frame, (int) average, true, rgbaFrame))
            msg << "Error shifting image for skipped bearing " << bearing << endl;
         else
            msg << "kludged frame at: " << bearing << endl;
         if (! writeKludgeFile(bearing, rgbaFrame))
            msg << "Error writing Kludge file for " << bearing << endl;
         skipped_bearings.erase(offset);
         completed_bearings[(int) offset] = 1;
      }
   } while (offset != start_offset);
   cout << endl;

   if (! YUV2RGBA(name, framesfile, dirpath))
      return 2;
   framesfile.close();
   return 0;
}

bool YUV2RGBA(const string &name, fstream &framesfile, const string &dirpath)
//--------------------------------------------------------------------------------------------------------
{
   string filename = dirpath + "/" + name + ".frames";
   ofstream rgbafile(filename);
   if (! rgbafile.good())
   {
      std::cerr << "Error creating " << (dirpath + "/" + name + ".frames") << std::endl;
      return false;
   }
   long no = (long) (360.0 / recording_increment);
   const int rgbasize = height*width*4;
   char yuv[buffersize], rgba[rgbasize];
   cout << "Converting to RGBA:    ";
   for (long offset=0; offset<no; offset++)
   {
      progress(offset, no);
      rgbafile.seekp(offset * rgbasize, std::ios_base::beg);
      if (! rgbafile.good())
      {
         cerr << "Error seeking to " << offset << " for file " << filename << endl;
         continue;
      }
      float bearing = offset * recording_increment;
      char name[128];
      sprintf(name, "%.1f.rgba", bearing);
      string kludgefile_name = dirpath + "/" + name;
      char *data = file_contents(kludgefile_name);
      if (data != nullptr)
      {
         rgbafile.write((const char *) data, rgbasize);
         delete[] data;
         if (! rgbafile.good())
            cerr << "Error writing " << offset << " for file " << filename << endl;
      }
      else
      {
         framesfile.seekg(offset * buffersize, std::ios_base::beg);
         if (! framesfile.good())
         {
            cerr << "Error seeking to " << offset << " for file " << filename << ".part" << endl;
            continue;
         }
         framesfile.read(yuv, buffersize);
         if (! framesfile.good())
         {
            cerr << "Error reading " << offset << " for file " << filename << ".part" << endl;
            continue;
         }
         Mat nv21(height+height/2, width, CV_8UC1, yuv);
         Mat frame(height, width, CV_8UC4);
         cvtColor(nv21, frame, cv::COLOR_YUV2RGBA_NV21);
         rgbafile.write((const char *) frame.data, rgbasize);
         if (! rgbafile.good())
            cerr << "Error writing " << offset << " for file " << filename << endl;
      }
   }
   rgbafile.close();
   progress(no, no);
   cout << endl;
   return true;
}

bool checkRot(fstream& framesfile, Mat& ok_frame, float bearing, double average, double deviation, long start_offset,
              int buffersize, float &next_bearing, int &next_distance)
//------------------------------------------------------------------------------------------
{
   char buf[buffersize];
   long offset;
   double badmaxshift = (int) round(average + deviation * 4.5);
   double badminshift = (int) max(round(average - deviation * 4.5), 1.0);
   int shiftCount = 2, shiftInitial;
   float worstCaseBearing = -1;
   double bestDistance = numeric_limits<double>::max();
   next_bearing = -1.0; next_distance = 0;
   int shift_initial, shifty;
   do
   {
      bearing += recording_increment;
      if (bearing >= 360)
         bearing -= 360;
      offset = (long) (floor(bearing / recording_increment));
      if (skipped_bearings.find(offset) == skipped_bearings.end())
      {
         readat(framesfile, offset, buf);
         Mat nextFrame(height+height/2, width, CV_8UC1, buf);
         shift(ok_frame, height, width, nextFrame, shift_initial, shifty);
         double initialMin = badminshift * shiftCount;
         double initialMax = badmaxshift * shiftCount;
         bool isInitialBad = ((shift_initial < initialMin) || (shift_initial > initialMax));
         if (! isInitialBad)
         {
            next_bearing = bearing;
            next_distance = shiftCount;
            return true;
         }
         if (shift_initial > 3 * shiftCount)
         {
            double distance;
            if (shift_initial < initialMin)
               distance = initialMin - shift_initial;
            else if (shift_initial > initialMax)
               distance = shift_initial - initialMax;
            else
               distance = 0;
            if (distance < bestDistance)
            {
               worstCaseBearing = bearing;
               bestDistance = distance;
            }
         }
      }
   } while ( (offset != start_offset) && (shiftCount++ < 12) );
   next_bearing = worstCaseBearing;
   next_distance = shiftCount - 1;
   return worstCaseBearing >= 0;
}

bool writeKludgeFile(float bearing, Mat& rgba)
//-------------------------------------------------
{
   char name[128];
   sprintf(name, "%.1f.rgba", bearing);
   string filename = dirpath + "/" + name;
   ofstream ofs(filename);
   if (! ofs.good())
   {
      std::cerr << "Error opening " << filename << std::endl;
      return false;
   }
   ofs.write((const char *) rgba.data, height * width * 4);
   if (! ofs)
   {
      std::cerr << "Error writing " << filename << std::endl;
      return false;
   }
   ofs.close();
   return true;
}

bool readat(fstream &fs, long offset, char *buffer)
//------------------------------------------------
{
   fs.seekg(offset * buffersize, std::ios_base::beg);
   if (! fs.good())
   {
      cerr << "Error seeking to " << offset;
      return false;
   }
   fs.read(buffer, buffersize);
   if (! fs.good())
   {
      cerr << "Error reading from offset " << offset << endl;
      return false;
   }
   return true;
}

char *file_contents(string f)
//---------------------------
{
   ifstream ifs(f);
   if (! ifs.good())
      return nullptr;
   char *buf = nullptr;
   try
   {
      ifs.seekg (0, ifs.end);
      long length = ifs.tellg();
      buf = new char [length];
      ifs.seekg (0, ifs.beg);
      ifs.read (buf, length);
   }
   catch (exception &e)
   {
      cerr << "Exception reading " << f << ": " << e.what() << endl;
      if (buf != nullptr)
         delete [] buf;
      ifs.close(); 
      return nullptr;
   }
   if (! ifs)
   {
      cerr << "Error reading " << f << endl;
      delete [] buf;
      ifs.close(); 
      return nullptr;
   }
   return buf;
}

float distance(float bearing1, float bearing2)
//-----------------------------------------------
{
   float dist;
   if ((bearing1 >= 270) && (bearing2 <= 90))
      dist = (360 - bearing1) + bearing2;
   else if ((bearing1 <= 90) && (bearing2 >= 270))
      dist = -((360 - bearing2) + bearing1);
   else
      dist = bearing2 - bearing1;
   return dist;
}

bool stats(fstream &framesfile, const int min_shift, const int max_shift, long *completed_bearings,
           unordered_set<float> *bad_bearings, double &average, double &deviation)
//-----------------------------------------------------------------------------------------------------------------------
{
   char buf1[buffersize];
   const long start_offset = (long) (floor(start_bearing / recording_increment));
   long off = start_offset;
   int sample_count = 0, total_shift = 0;
   vector<int> all_shifts;
   bool is_frame2 = false;
   Mat frame2(height, width, CV_8UC1);
   int shiftx, shifty, cnt = 0;
   float bearing = start_bearing;
   cout << "Evaluating frames:     ";
   do
   {
      progress(cnt++, no);
      try
      {
         if (completed_bearings[off] >= 0)
         {
            framesfile.seekg(off * buffersize, std::ios_base::beg);
            framesfile.read((char *) buf1, buffersize);
            Mat frame(height+height/2, width, CV_8UC1, buf1);
            if (is_frame2)
            {
               shift(frame2, height, width,  frame, shiftx, shifty);
               if ( (shiftx <= min_shift) || (shiftx >= max_shift) )
               {
//                  completed_bearings[off] = -1;
                  if (bad_bearings != nullptr)
                     bad_bearings->insert((float) (off * recording_increment));
                  is_frame2 = false;
               }
               else
               {
                  total_shift += shiftx;
                  all_shifts.push_back(shiftx);
                  sample_count++;
                  frame.copyTo(frame2);
               }
            }
            else
            {
               frame.copyTo(frame2);
               is_frame2 = true;
            }
         }
         else
            is_frame2 = false;
         bearing += recording_increment;
         if (bearing >= 360)
            bearing -= 360;
         off = (long) (floor(bearing / recording_increment));
      }
      catch (...)
      {
         cerr << "Caught exception in stats" << endl;
         return false;
      }
   } while (off != start_offset);
   progress(cnt++, no);
   cout << endl;

   average = DEFAULT_SHIFT_AVERAGE;
   deviation = DEFAULT_SHIFT_DEVIATION;
   if (sample_count > 5)
   {
      average = (double) total_shift / (double) sample_count;
      double sum = 0.0;
      for (int ashift : all_shifts)
         sum += (ashift - average) * (ashift - average);
      deviation = sqrt(sum / (double) (all_shifts.size() - 1));
   }
   else
      return false;
   return true;
}

void shift(cv::Mat &nv21_frame1, int height, int width,  cv::Mat &nv21_frame2, int &resX, int &resY)
//---------------------------------------------------------------------------------------------------
{
   UMat grey1(height, width, CV_8UC1), grey2(height, width, CV_8UC1);;
   cvtColor(nv21_frame1, grey1, cv::COLOR_YUV2GRAY_420);
   cvtColor(nv21_frame2, grey2, cv::COLOR_YUV2GRAY_420);

   width = cv::getOptimalDFTSize(std::max(grey1.cols, grey2.cols));
   height = cv::getOptimalDFTSize(std::max(grey1.rows, grey2.rows));
   UMat fft1(cv::Size(width,height), CV_32F, cv::Scalar(0));
   UMat fft2(cv::Size(width,height), CV_32F, cv::Scalar(0));
   
   grey1.convertTo(fft1, CV_32F);
   grey2.convertTo(fft2, CV_32F);
         
   dft(fft1, fft1, 0, grey1.rows);
   dft(fft2, fft2, 0, grey2.rows);
   mulSpectrums(fft1, fft2, fft1, 0, true);
   idft(fft1, fft1);
   double maxVal;
   cv::Point maxLoc;
   minMaxLoc(fft1, NULL, &maxVal, NULL, &maxLoc);
   resX = (maxLoc.x < width/2) ? (maxLoc.x) : (maxLoc.x - width);
   resY = (maxLoc.y < height/2) ? (maxLoc.y) : (maxLoc.y - height);
   //cout << resX << ", " << resY << endl;
}

bool do_shift(const int height, const int width, Mat& nv21, int shift, bool is_right, Mat& out)
//--------------------------------------------------------------------------------------------------
{
   try
   {
      Mat rgba(height, width, CV_8UC4);
      cv::cvtColor(nv21, rgba, CV_YUV2RGBA_NV21);
      int len = rgba.step * rgba.rows;
      out = cv::Mat(height, width, CV_8UC4);
      rgba.copyTo(out);
      if (is_right)
         rgba(cv::Rect(0, 0, rgba.cols-shift, rgba.rows)).copyTo(out(cv::Rect(shift, 0, rgba.cols-shift, rgba.rows)));
      else
         rgba(cv::Rect(shift, 0, rgba.cols-shift, rgba.rows)).copyTo(out(cv::Rect(0, 0, rgba.cols-shift, rgba.rows)));
      return true;
   }
   catch (std::exception &e)
   {
      std::cerr << e.what() << std::endl;
      return false;
   }
}   

string get_name(string &dirpath)
//-------------------------------
{
   string name = "";
   DIR *dir = opendir(dirpath.c_str());
   if (dir == nullptr)
   {
      perror("opendir");
      return "";
   }
   struct dirent *dir_entry = readdir(dir);
   while (dir_entry != nullptr)
   {
      string s(dir_entry->d_name);
      auto p = s.find(".frames.part");
      if (p != string::npos)
      {
         name = s.erase(p);
         cout << name << endl;
         break;
      }
      dir_entry = readdir(dir);
   }
   closedir(dir);
   if (! name.empty())      
   {
      string filename(dirpath + "/" + name  + ".head");
      ifstream f(filename);
      if (! f.good())
      {
         cerr << "Error opening header file " << filename << endl;
         exit(1);
      }
      string k, v;
      getline(f, k, '=');
      while (! f.eof())
      {
         getline(f, v);
         if (k == "Increment")
            recording_increment = stof(v);
         else if (k == "BufferSize")
            buffersize = stoi(v);
         else if (k == "PreviewWidth")
            width = stoi(v);
         else if (k == "PreviewHeight")
            height = stoi(v);
         else if (k == "StartBearing")
            start_bearing = stof(v);
         getline(f, k, '=');
      }
      f.close();      
   }   
   return name;
}

void progress(int i, int n)
//-------------------------
{
   stringstream ss;
   if (n == 0)
      ss << "100%";
   else
      ss << (i*100)/n << '%';
   string percentage = ss.str();
   int len = (int) percentage.size();
   char backspace[len*3 + 1];
   backspace[len*3] = 0;
   for (int ii=0; ii<len; ii++)
   {
      backspace[ii] = '\b';
      backspace[ii+len] = ' ';
      backspace[ii+len*2] = '\b';            
   }         
   cout << backspace << percentage << flush;
}

void check_opencl()
//-----------------
{
   vector<ocl::PlatformInfo> info;
   getPlatfomsInfo(info); //sic
   ocl::PlatformInfo sdk = info.at(0);
   if (sdk.deviceNumber()<1)
      return;
   ocl::setUseOpenCL(true);
   cout << "****** GPU Support *******" << endl;
   cout << "Name:              " << sdk.name() << endl;
   cout << "Vendor:            " << sdk.vendor() << endl;
   cout << "Version:           " << sdk.version() << endl;
   cout << "Number of devices: " << sdk.deviceNumber() << endl;
   for (int i=0; i<sdk.deviceNumber(); i++)
   {
      ocl::Device device;
      sdk.getDevice(device, i);
      cout << "Device               " << i+1 << endl;
      cout << "Vendor ID:           " << device.vendorID()<< endl;
      cout << "Vendor name:         " << device.vendorName()<< endl;
      cout << "Name:                " << device.name()<< endl;
      cout << "Driver version:      " << device.driverVersion()<< endl;
      cout << "Manufacturer:        "
           << ((device.isNVidia()) ? "NVidia" : ((device.isAMD()) ? "AMD" : ((device.isIntel()) ? "Intel" : "Unknown")))
           << endl;
      cout << "Global Memory size:  " << device.globalMemSize() << endl;
      cout << "Memory cache size:   " << device.globalMemCacheSize() << endl;
      cout << "Memory cache type:   " << device.globalMemCacheType() << endl;
      cout << "Local Memory size:   " << device.localMemSize() << endl;
      cout << "Local Memory type:   " << device.localMemType() << endl;
      cout << "Max Clock frequency: " << device.maxClockFrequency()<< endl;
      cout << endl;
   }

//   if (! cv::ocl::haveOpenCL())
//   {
//       cout << "OpenCL is not available..." << endl;
//      return;
//   }
//
//   cv::ocl::Context context;
//   if (!context.create(cv::ocl::Device::TYPE_GPU))
//   {
//      cout << "Could not create OpenCL context..." << endl;
//      //return;
//   }
//   cout << "OpenCL GPU Support:" <<endl;
//   cout << context.ndevices() << " GPU devices." << endl;
//   for (size_t i = 0; i < context.ndevices(); i++)
//   {
//      cv::ocl::Device device = context.device(i);
//      cout << "name:              " << device.name() << endl;
//      cout << "available:         " << device.available() << endl;
//      cout << "imageSupport:      " << device.imageSupport() << endl;
//      cout << "OpenCL_C_Version:  " << device.OpenCL_C_Version() << endl;
//      cout << endl;
//   }
}