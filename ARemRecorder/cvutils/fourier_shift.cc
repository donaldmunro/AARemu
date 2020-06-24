#include <iostream>
#include <opencv2/core/core.hpp>
#include <opencv2/core/mat.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <fstream>
#include <iomanip>

#include <opencv2/highgui/highgui.hpp>

using namespace std;
using namespace cv;

Mat toGrey(string filename, int width, int height)
//----------------------------------------------------
{
   uchar buf[460800];
   ifstream imgf(filename.c_str());
   imgf.read((char *) buf, 460800);
   Mat nv21(height+height/2, width, CV_8UC1, buf);
   Mat grey(height, width, CV_8UC1);
   cvtColor(nv21, grey, COLOR_YUV2GRAY_420);
   
//   namedWindow(filename, CV_WINDOW_AUTOSIZE);
//   cvMoveWindow(filename.c_str(), 400, 10);
//   imshow(filename.c_str(), grey);
//   cvWaitKey(300000);
   return grey;
}

int main(int argc, char **argv)
{
   string file1(argv[1]), file2(argv[2]);
   Mat grey1 = toGrey(file1, 640, 480);
   Mat grey2 = toGrey(file2, 640, 480);
   
   int width = getOptimalDFTSize(max(grey1.cols, grey2.cols));
   int height = getOptimalDFTSize(max(grey1.rows, grey2.rows));
   Mat fft1(Size(width,height),CV_32F,Scalar(0));
   Mat fft2(Size(width,height),CV_32F,Scalar(0));
   
   grey1.convertTo(fft1, CV_32F);
//   for(int j=0; j<grey1.rows; j++)
//      for(int i=0; i<grey1.cols; i++)
//         fft1.at<float>(j,i) = grey1.at<unsigned char>(j,i);
   grey2.convertTo(fft2, CV_32F);
//   for(int j=0; j<grey2.rows; j++)
//      for(int i=0; i<grey2.cols; i++)
//         fft2.at<float>(j,i) = grey2.at<unsigned char>(j,i);
   
   dft(fft1,fft1, 0, grey1.rows);
   dft(fft2,fft2, 0, grey2.rows);
   mulSpectrums(fft1, fft2, fft1, 0, true);
   idft(fft1, fft1);
   double maxVal;
   Point maxLoc;
   minMaxLoc(fft1,NULL,&maxVal,NULL,&maxLoc);
   auto resX = (maxLoc.x<width/2) ? (maxLoc.x) : (maxLoc.x-width);
   auto resY = (maxLoc.y<height/2) ? (maxLoc.y) : (maxLoc.y-height);
   cout << resX << ", " << resY << endl;
   return 0;
}
