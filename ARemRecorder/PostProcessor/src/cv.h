#ifndef POSTPROCESSOR_CV_H
#define POSTPROCESSOR_CV_H

#include <opencv2/core/mat.hpp>

namespace cvutil
{
   double psnr(cv::Mat rgba1, cv::Mat rgba2);
   void shift(cv::Mat &grey1, cv::Mat &grey2, int& shiftx, int &shifty);
   void greyShift(int width, int height, unsigned char *im1, unsigned char *im2, int &xshift, int &yshift);
   bool kludgeRGBA(int width, int height, unsigned char *im, int translate, bool is2Right, unsigned char *imout);
   bool stitch3(int width, int height, unsigned char *im1, unsigned char *im2, unsigned char *im3,
                unsigned char *output);
   bool stitch(std::vector<cv::Mat>& v, unsigned char *output);
   bool stitch3(cv::Mat& img1, cv::Mat& img2, cv::Mat& img3, cv::Mat& stitchedImg);
};

#endif //POSTPROCESSOR_CV_H
