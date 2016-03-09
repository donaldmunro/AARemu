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
   for (int i=1; i<argc; i++)
   {
      std::string name = argv[i];      
      std::ifstream imgf1(name.c_str());  
      bool is_nv21 = false;
      int bufsize;
      if (name.find(".rgba") != std::string::npos)
      {
         bufsize = 640*480*4;
         is_nv21 = false;
      }
      else
      {         
         bufsize = width * height * 12 / 8;
         is_nv21 = true;
      }      
      
      char *buf = new char[bufsize];
      imgf1.read(buf, bufsize);
      cv::Mat rgba(height, width, CV_8UC4);
   
      if (is_nv21)
      {
         cv::Mat nv21(height+height/2, width, CV_8UC1, buf);         
         cv::cvtColor(nv21, rgba, CV_YUV2RGBA_NV21);
      }
      else
         rgba = cv::Mat(height, width, CV_8UC4, buf);
      
      auto p = name.find_last_of(".");
      if (p != std::string::npos)
         name = name.erase(p);
      cv::namedWindow(name, CV_WINDOW_AUTOSIZE);
      cvMoveWindow(name.c_str(), x, y);
      x += 200;
      y += 50;
      cv::imshow(name, rgba);            
      //imwrite( name + ".png", rgba1 );
   }
   cvWaitKey(300000);
}
