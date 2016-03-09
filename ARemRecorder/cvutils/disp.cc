#include <iostream>
#include <fstream>
#include <string>
#include <opencv2/core/core.hpp>
#include <opencv2/core/mat.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/highgui/highgui.hpp>

int main(int argc, char **argv)
{
   const int height = 480, width = 640, shift = 2;
   uchar buf1[460800];   
   int x = 0, y = 0;
   for (int i=1; i<argc; i++)
   {
      std::string name = argv[i];      
      std::ifstream imgf1(name.c_str());      
      imgf1.read((char *) buf1, 460800);
         
      cv::Mat nv21(height+height/2, width, CV_8UC1, buf1);
      cv::Mat rgba1(height, width, CV_8UC4);
      cv::cvtColor(nv21, rgba1, CV_YUV2RGBA_NV21);
      
      auto p = name.find_last_of(".");
      if (p != std::string::npos)
         name = name.erase(p);
      cv::namedWindow(name, CV_WINDOW_AUTOSIZE);
      cvMoveWindow(name.c_str(), x, y);
      x += 200;
      y += 50;
      cv::imshow(name, rgba1);            
      //imwrite( name + ".png", rgba1 );
   }
   cvWaitKey(300000);
}
