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
   
   grey1.convertTo(fft1, CV_32F);
   grey2.convertTo(fft2, CV_32F);
   
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

int main(int argc, char **argv)
//----------------------------
{
   const int height = 480, width = 640;   
   float increment = 1.0;
   int frameno1, frameno2;
   std::string outputname("");
   if (argc < 4)
   {   
      std::cerr << "format: framediff framefile frame1 frame2 [increment]" << std::endl;
      return 1;
   }
   std::string filename(argv[1]);   
   std::fstream framesfile(filename);
   if (! framesfile.good())
   {
      std::cerr << "Error opening " << filename << std::endl;
      return 2;
   }
   else
      std::cout << "Opened " << filename << std::endl;
   try { frameno1 = std::stoi(argv[2]); } catch (...) { frameno1 = -1; }
   try { frameno2 = std::stoi(argv[3]); } catch (...) { frameno2 = -1; }   
   if (argc > 4)
      try { increment = std::stoi(argv[4]); } catch (...) { increment = -1; }      
   if ( (frameno1 < 0) || (frameno2 < 0) || (increment < 0) )
   {
      std::cerr << "format: framediff framefile frame1 frame2 [increment]" << std::endl;
      return 1;
   }
   long no = (long) (360.0 / increment);
   if ( (frameno1 > no) || (frameno2 > no) )
   {
      std::cerr << "format: framediff framefile frame1 frame2 [increment] where frame1 and frame2 between 0 and " << no << std::endl;
      return 1;
   }

   int bufsize;
   char *buf1 = nullptr, *buf2 = nullptr;
   bool is_nv21;
   if (filename.find(".frames.part") != std::string::npos)
   {
      bufsize = 460800;      
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
   buf1 = new char[bufsize]; 
   buf2 = new char[bufsize];
   framesfile.seekp(frameno1*bufsize, std::ios_base::beg);            
   framesfile.read((char *) buf1, bufsize);        
   framesfile.seekp(frameno2*bufsize, std::ios_base::beg);               
   framesfile.read((char *) buf2, bufsize);
   if (is_nv21)
   {
      cv::Mat nv21(height+height/2, width, CV_8UC1, buf1);   
      cv::Mat frame1(height, width, CV_8UC4);   
      cv::cvtColor(nv21, frame1, CV_YUV2RGBA_NV21);   
      
      cv::Mat nv21_2(height+height/2, width, CV_8UC1, buf2);
      cv::Mat frame2(height, width, CV_8UC4);   
      cv::cvtColor(nv21_2, frame2, CV_YUV2RGBA_NV21);
      std::cout << shift(frame1, height, width, frame2) << std::endl;
      cv::imwrite(std::string(argv[2]) + ".png", frame1 );
      cv::imwrite(std::string(argv[3]) + ".png", frame2 );
      
   }
   else
   {
      cv::Mat frame1(height, width, CV_8UC4, buf1);
      cv::Mat frame2(height, width, CV_8UC4, buf2);
      std::cout << shift(frame1, height, width, frame2) << std::endl;
      cv::imwrite(std::string(argv[2]) + ".png", frame1 );
      cv::imwrite(std::string(argv[3]) + ".png", frame2 );
   }      
   
   delete[] buf1;
   delete[] buf2;
}


