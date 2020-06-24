#include <iostream>
#include <fstream>
#include <string>
#include <opencv2/core/core.hpp>
#include <opencv2/core/mat.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/highgui/highgui.hpp>

#include <math.h>

int shift(cv::Mat frame1, int height, int width,  cv::Mat frame2)
//--------------------------------------
{
   cv::Mat grey1(height, width, CV_8UC1);
   cvtColor(frame1, grey1, CV_RGBA2GRAY);
   cv::Mat grey2(height, width, CV_8UC1);
   cvtColor(frame2, grey2, CV_RGBA2GRAY);
      
   width = cv::getOptimalDFTSize(std::max(grey1.cols, grey2.cols));
   height = cv::getOptimalDFTSize(std::max(grey1.rows, grey2.rows));
   cv::Mat fft1(cv::Size(width,height), CV_32F, cv::Scalar(0));
   cv::Mat fft2(cv::Size(width,height), CV_32F, cv::Scalar(0));
   
   for(int j=0; j<grey1.rows; j++)
      for(int i=0; i<grey1.cols; i++)
         fft1.at<float>(j,i) = grey1.at<unsigned char>(j,i);

   for(int j=0; j<grey2.rows; j++)
      for(int i=0; i<grey2.cols; i++)
         fft2.at<float>(j,i) = grey2.at<unsigned char>(j,i);
   
   dft(fft1,fft1, 0, grey1.rows);
   dft(fft2,fft2, 0, grey2.rows);
   mulSpectrums(fft1, fft2, fft1, 0, true);
   idft(fft1, fft1);
   double maxVal;
   cv::Point maxLoc;
   minMaxLoc(fft1,NULL,&maxVal,NULL,&maxLoc);
   auto resX = (maxLoc.x<width/2) ? (maxLoc.x) : (maxLoc.x-width);
   //auto resY = (maxLoc.y<height/2) ? (maxLoc.y) : (maxLoc.y-height);
   //cout << resX << ", " << resY << endl;
   return resX;
}

cv::Mat readMat(std::string filename, int width, int height)
{
   if (filename.find(".nv21") != std::string::npos)
   {
      std::ifstream imgf1(filename.c_str());      
      char buf[460800];
      imgf1.read((char *) buf, 460800);
      
      cv::Mat nv21(height+height/2, width, CV_8UC1, buf);
      cv::Mat rgba1(height, width, CV_8UC4);
      cv::cvtColor(nv21, rgba1, CV_YUV2RGBA_NV21);
      return rgba1;
   }
   else
   {
      std::ifstream imgf1(filename.c_str());      
      char buf[width*height*4];
      imgf1.read(buf, width*height*4);      
      return cv::Mat(height, width, CV_8UC4, buf);      
   }
}

int main(int argc, char **argv)
//----------------------------
{
   const int height = 480, width = 640;   
   std::string file1(argv[1]), file2(argv[2]);
   cv::Mat M1 = readMat(file1, width, height);
   cv::Mat M2 = readMat(file2, width, height);
   std::cout << shift(M1, height, width, M2) << std::endl;
}


