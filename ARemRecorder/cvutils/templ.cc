#include <iostream>
#include <opencv2/core/core.hpp>
#include <opencv2/core/mat.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <fstream>
#include <iomanip>

#include <opencv2/highgui/highgui.hpp>

using namespace std;
using namespace cv;

Mat toRGBA(string filename, int width, int height)
//----------------------------------------------------
{
   uchar buf[460800];
   ifstream imgf(filename.c_str());
   imgf.read((char *) buf, 460800);
   Mat nv21(height+height/2, width, CV_8UC1, buf);
   Mat rgba(height, width, CV_8UC4);
   cvtColor(nv21, rgba, CV_YUV2RGBA_NV21);
   
   //namedWindow(filename, CV_WINDOW_AUTOSIZE);
   //cvMoveWindow(filename.c_str(), 400, 10);
   //imshow(filename.c_str(), rgba);
   return rgba;
}

int main(int argc, char **argv)
{
   string file1(argv[1]), file2(argv[2]);
   Mat rgba1 = toRGBA(file1, 640, 480);
   Mat rgba2 = toRGBA(file2, 640, 480);
   Mat match = Mat(1, 1, CV_32FC1);
   matchTemplate(rgba1, rgba2, match, CV_TM_CCORR_NORMED);
   cout << match.at<float>(0, 0) << endl;
   auto p = match.ptr<float>();
   cout << p[0] << endl;
   double minVal, maxVal; 
//   Point minLoc, maxLoc, matchLoc;
//   minMaxLoc(match, &minVal, &maxVal, &minLoc, &maxLoc, Mat() );
//   cout << minLoc << " " <<  maxLoc << " " << minVal << " " << maxVal << endl;
   return 0;
}
