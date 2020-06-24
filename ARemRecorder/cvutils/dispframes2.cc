#include <iostream>
#include <iomanip>
#include <fstream>
#include <string>

#include <opencv2/core/core.hpp>
#include <opencv2/core/mat.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/highgui/highgui.hpp>

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

int main(int argc, char **argv)
{
   const int height = 480, width = 640;
   int x = 0, y = 0, bufsize = 640*480*4;
   float increment = 1.0;
   int pause = 300;
   std::string outputname("");
   if (argc < 2)
   {
      std::cerr << "format: dispframes [-f] [-i increment] [-p pausems] framefile" << std::endl;
      std::cerr << "-f = Augmented frames file [.RGBA files are assumed to be augmented]" << std::endl;
      return 1;
   }
   bool is_augmented = false;
   int i = 1, j = 1;
   for (; i<argc; i++)
   {
      std::string opt = argv[i];
      if (opt == "-f")
      {
         is_augmented = true;
         j = i + 1;
      }
      else if (opt == "-i")
      {
         if (++i >=argc)
         {
            std::cerr << "Error parsing increment "  << std::endl;
            return 2;
         }
         std::string s(argv[i]);
         std::stringstream ss(s);
         if (! (ss >> increment))
         {
            std::cerr << "Error parsing increment " << s << std::endl;
            return 2;
         }
         j = i + 1;
      }
      else if (opt == "-p")
      {
         if (++i >=argc)
         {
            std::cerr << "Error parsing pause " << std::endl;
            return 2;
         }
         std::string s(argv[i]);
         std::stringstream ss(s);
         if (! (ss >> pause))
         {
            std::cerr << "Error parsing pause " << s << std::endl;
            return 2;
         }
         j = i + 1;
      }
   }
   std::cout << i << " " << j << std::endl;
   if (j >= argc)
   {
      std::cerr << "format: dispframes [-f] [-i increment] [-p pausems] framefile" << std::endl;
      std::cerr << "-f = Augmented frames file [.RGBA files are assumed to be augmented]" << std::endl;
      return 1;
   }
   std::string filename(argv[j]);
   std::string s(filename);
   std::transform(s.begin(), s.end(), s.begin(), ::tolower);
   bool is_nv21;
   if (s.find(".raw") != std::string::npos)
   {
      bufsize = width * height * 12 / 8;;
      is_nv21 = true;
   }
   else if ( (s.find(".frames") != std::string::npos) ||
             (s.find(".rgba") != std::string::npos) )
   {
      bufsize = 640*480*4;
      is_nv21 = false;
      if (s.find(".rgba") != std::string::npos)
         is_augmented = true;
   }
   else
   {
      std::cerr << "File must be a .frames (RGBA) or .frames.part (YUV NV21) file" << std::endl;
      return 1;
   }
   if (is_augmented)
   {
      bufsize += sizeof(uint64_t)*2;
      std::cout << "augmented " << bufsize << std::endl;
   }

   std::fstream framesfile(filename);
   if (! framesfile.good())
   {
      std::cerr << "Error opening " << filename << std::endl;
      return 2;
   }

   uchar buf1[bufsize];
   cv::namedWindow("frame", CV_WINDOW_NORMAL);
   cvMoveWindow("frame", x, y);
   long no = (long) (360.0 / increment);
   std::cout << no << std::endl;
   std::stringstream ss;
   while (true)
   {
      if (is_augmented)
      {
         uint64_t v;
         framesfile.read((char *) &v, sizeof(uint64_t));
         framesfile.read((char *) &v, sizeof(uint64_t));
         uint64_t iv;
         from_big_endian64(v, (unsigned char *) &iv);
         int size = (int) iv;
         std::cout << size << std::endl;
         if (size > 0)
            framesfile.read((char *) buf1, size);
      }
      else
         framesfile.read((char *) buf1, bufsize);
      if ( (! framesfile.good()) || (framesfile.eof()) )
         break;
      cv::Mat rgba1(height, width, CV_8UC4);
      if (is_nv21)
      {
         cv::Mat nv21(height+height/2, width, CV_8UC1, buf1);
         cv::cvtColor(nv21, rgba1, CV_YUV2RGBA_NV21);
      }
      else
         rgba1 = cv::Mat(height, width, CV_8UC4, buf1);

      cv::imshow("frame", rgba1);
      int c = cv::waitKey(pause);
      //std::cout << "key " << c << std::endl;
      if (c == 27) break;
   }
}
