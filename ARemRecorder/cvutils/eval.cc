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

template<typename T>
double deviation(T* data, int count, double average)
{
   double sum = 0;
   for (int i=0; i<count; i++)
   {
      T v = data[i];
      sum += (v - average) * (v - average);
   }
   return sqrt(sum / (double) (count - 1));
}


int main(int argc, char **argv)
//----------------------------
{
   const int height = 480, width = 640;   
   float increment = 1.0;
   std::string outputname("");
   if (argc < 2)
   {   
      std::cerr << "format: eval framefile [increment] [outputfile]" << std::endl;
      return 1;
   }
   std::string filename(argv[1]);
   std::fstream framesfile(filename);
   if (! framesfile.good())
   {
      std::cerr << "Error opening " << filename << std::endl;
      return 2;
   }
   bool is_nv21;
   int bufsize;
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
   if (argc > 2)
   {   
      try { increment = std::stoi(argv[2]); } catch (...) { outputname = argv[2]; }      
//      std::string s(argv[2]);
//      std::stringstream ss;      
//      ss << argv[2];
//      ss >> increment;
//      if (ss.fail())
//         outputname = s;
   }
   if (argc > 3)
      outputname = argv[3];
   std::ofstream shiftOut, corrOut;
   if (! outputname.empty())
   {
      shiftOut.open(outputname + ".shift", std::ofstream::out);
      corrOut.open(outputname + ".corr", std::ofstream::out);
   }
//   cv::namedWindow("frame", CV_WINDOW_AUTOSIZE);
//   cvMoveWindow("frame", x, y);
   long no = (long) (360.0 / increment);
   std::cout << no << std::endl;
   char *buf1 = new char[bufsize];
   framesfile.seekp(0, std::ios_base::beg);            
   framesfile.read((char *) buf1, bufsize);            
   cv::Mat frame1, frame2;
   if (is_nv21)
   {
      cv::Mat nv21(height+height/2, width, CV_8UC1, buf1);   
      cv::Mat frame(height, width, CV_8UC4);   
      cv::cvtColor(nv21, frame, CV_YUV2RGBA_NV21);
      frame1 = frame;
   }
   else
      frame1 = cv::Mat(height, width, CV_8UC4, buf1);
   int total_shift = 0, all_shifts[no], count_shift = 0, count_corr = 0;
   double total_corr = 0, all_corrs[no];   
   char *buf2 = new char[bufsize];
   for (long offset=bufsize; offset<no*bufsize; offset += bufsize)
   {      
      framesfile.seekp(offset, std::ios_base::beg);            
      framesfile.read((char *) buf2, bufsize);
      if (is_nv21)
      {
         cv::Mat nv21(height+height/2, width, CV_8UC1, buf2);   
         cv::Mat frame(height, width, CV_8UC4);   
         cv::cvtColor(nv21, frame, CV_YUV2RGBA_NV21);
         frame2 = frame;
      }
      else
         frame2 = cv::Mat(height, width, CV_8UC4, buf2);

      float bearing = (offset / bufsize) * increment;
      int sh = shift(frame1, height, width, frame2);
//      if (sh < 0) continue;
      all_shifts[count_shift++] = sh;
      total_shift += sh;
//      std::cout << bearing << " shift " << sh << " " << total_shift << std::endl;
      cv::Mat match = cv::Mat(1, 1, CV_32FC1);
      matchTemplate(frame1, frame2, match, CV_TM_CCORR_NORMED);
      float corr = match.at<float>(0, 0);
      all_corrs[count_corr++] = corr;
      total_corr += corr;
//      std::cout << "corr " << corr << " " << total_corr << std::endl;
      frame2.copyTo(frame1);
      if (! outputname.empty())
      {
         shiftOut << bearing << " " << sh << std::endl;
         corrOut<< bearing << " " << corr << std::endl; 
      }      
      //imwrite( s + ".png", rgba1 );
   }
   delete[] buf1;
   delete[] buf2;
   if (! outputname.empty())
   {
      shiftOut.close();
      corrOut.close();
   }
   double shift_average = (double) total_shift / (double) count_shift;
   double corr_average = (double) total_corr / (double) count_corr;
   double shift_deviation = deviation(all_shifts, count_shift, shift_average);
   double corr_deviation = deviation(all_corrs, count_corr, corr_average);
   std::cout << "Shift avarage: " << shift_average << ", deviation: " << shift_deviation << std::endl;
   std::cout << "Correlation avarage: " << corr_average << ", deviation: " << corr_deviation << std::endl;
//   double sum = 0.0;
//   for (int i=0; i<count_shift; i++)
//   {
//      sum += (ashift - averageShift) * (ashift - averageShift);
//   }
//         deviation = Math.sqrt(sum / (double) (allShifts.size() - 1));
//         return new MutablePair<>(averageShift, deviation);
   //cvWaitKey(300000);
}


