#include <iostream>
#include <fstream>
#include <string>
#include <opencv2/core/core.hpp>
#include <opencv2/core/mat.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/highgui/highgui.hpp>

int main(int argc, char **argv)
{
   const int height = 480, width = 640;
   int x = 0, y = 0;
   if (argc < 3)
   {   
      std::cerr << "format: dispframe file frameno1 ... framenox" << std::endl;
      return 1;
   }
   std::string filename(argv[1]);
   std::fstream framesfile(filename);
   if (! framesfile.good())
   {
      std::cerr << "Error opening " << filename << std::endl;
      return 2;
   }
   
   int bufsize;   
   bool is_nv21;
   if (filename.find(".frames.part") != std::string::npos)
   {
      bufsize = width * height * 12 / 8;
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
   char *buf = new char[bufsize];
   for (int i=2; i<argc; i++)
   {      
      int frameno = std::stoi(argv[i]);
      framesfile.seekp(bufsize*frameno, std::ios_base::beg);            
      framesfile.read(buf, bufsize);
      
      if (is_nv21)
      {
         cv::Mat nv21(height+height/2, width, CV_8UC1, buf);   
         cv::Mat frame(height, width, CV_8UC4);   
         cv::cvtColor(nv21, frame, CV_YUV2RGBA_NV21);            
         cv::namedWindow(argv[i], CV_WINDOW_AUTOSIZE);
         cvMoveWindow(argv[i], x, y);         
         cv::imshow(argv[i], frame);
      }
      else
      {
         cv::Mat frame(height, width, CV_8UC4, buf);
         cv::namedWindow(argv[i], CV_WINDOW_AUTOSIZE);
         cvMoveWindow(argv[i], x, y);
         cv::imshow(argv[i], frame);
      }
      x += 200;
      y += 50;
   }
   cvWaitKey(300000);
}
