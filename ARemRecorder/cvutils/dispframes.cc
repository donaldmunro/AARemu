#include <iostream>
#include <iomanip> 
#include <fstream>
#include <string>

#include <opencv2/core/core.hpp>
#include <opencv2/core/mat.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/highgui/highgui.hpp>

int main(int argc, char **argv)
{
   const int height = 480, width = 640;
   int x = 0, y = 0, bufsize = 640*480*4;
   float increment = 1.0;
   int pause = 300;
   std::string outputname("");
   if (argc < 2)
   {   
      std::cerr << "format: dispframes framefile pausems [increment] " << std::endl;
      return 1;
   }
   std::string filename(argv[1]);
   bool is_nv21;
   if (filename.find(".frames.part") != std::string::npos)
   {
      bufsize = width * height * 12 / 8;;      
      is_nv21 = true;
   }
   else if (filename.find(".frames") != std::string::npos)
   {
      bufsize = 640*480*4;
      is_nv21 = false;
   }
   else
   {
      std::cerr << "File must be a .frames (RGBA) or .frames.part (YUV NV21) file" << std::endl;
      return 1;
   }
   
   std::fstream framesfile(filename);
   if (! framesfile.good())
   {
      std::cerr << "Error opening " << filename << std::endl;
      return 2;
   }
   if (argc > 2)
   {   
      std::string s(argv[2]);
      std::stringstream ss(s);      
      if (! (ss >> pause))
      {
         std::cerr << "Error parsing pause " << s << std::endl;
         return 2;
      }
   }
   if (argc > 3)
   {   
      std::string s(argv[3]);
      std::stringstream ss(s);      
      if (! (ss >> increment))
      {
         std::cerr << "Error parsing increment " << s << std::endl;
         return 2;
      }
   }
   uchar buf1[bufsize];
   cv::namedWindow("frame", CV_WINDOW_AUTOSIZE);
   cvMoveWindow("frame", x, y);
   long no = (long) (360.0 / increment);
   std::cout << no << std::endl;
   std::stringstream ss;
   for (long offset=0; offset<no*bufsize;)
   {      
      framesfile.seekp(offset, std::ios_base::beg);            
      framesfile.read((char *) buf1, bufsize);
      cv::Mat rgba1(height, width, CV_8UC4);
      if (is_nv21)
      {
         cv::Mat nv21(height+height/2, width, CV_8UC1, buf1);
         cv::cvtColor(nv21, rgba1, CV_YUV2RGBA_NV21);            
      }
      else      
         rgba1 = cv::Mat(height, width, CV_8UC4, buf1);
      
      float bearing = (offset / bufsize) * increment;
      ss.str("");
      ss << std::fixed << std::setprecision(1) << bearing;
      cv::putText(rgba1, ss.str(), cvPoint(30,30), CV_FONT_HERSHEY_COMPLEX_SMALL, 0.8, cvScalar(0, 0, 255), 1, CV_AA);
      cv::imshow("frame", rgba1);            
      int c = cv::waitKey(pause);
      std::cout << "key " << c << std::endl;
      switch (c)
      {
         case 1048688:
            while (cv::waitKey(20) != 1048688);
            break;
         case 1113937:
            offset -= bufsize;
            if (offset < 0)
               offset = bufsize*359;
            continue;
         case 1113939: // >
            offset += bufsize;
            break;
         case 1048603:
            offset = no*bufsize;
            break;            
      }
      offset += bufsize;
   }
}
