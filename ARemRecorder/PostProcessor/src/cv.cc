#include <iostream>
#include <memory>

#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <opencv2/stitching.hpp>

//#define DEBUG

#ifdef DEBUG
#include <opencv2/highgui.hpp>
#endif

#include "cv.h"

namespace cvutil
{
   double psnr(cv::Mat rgba1, cv::Mat rgba2)
//--------------------------------------------------------------------------------------
   {
      double ret = 0;
      try
      {
         cv::Mat diff;
         cv::absdiff(rgba1, rgba2, diff);
         diff.convertTo(diff, CV_32F);
         diff = diff.mul(diff);
         cv::Scalar s = cv::sum(diff);
         double sse = s.val[0] + s.val[1] + s.val[2];
         if (sse <= 1e-10)
            ret = 0;
         else
         {
            double mse = sse / (double) (rgba1.channels() * rgba1.total());
            ret = 10.0 * log10((255 * 255) / mse);
         }
      }
      catch (cv::Exception &e)
      {
         std::cerr << "PSNR OpenCV Exception: " << e.what() << std::endl;
      }
      catch (std::exception &e)
      {
         std::cerr << "PSNR Exception: " << e.what() << std::endl;
      }
      return ret;

   }

   void shift(cv::Mat &grey1, cv::Mat &grey2, int &shiftx, int &shifty)
//-----------------------------------------------------------------------------------
   {
      try
      {
         int dftwidth = cv::getOptimalDFTSize(std::max(grey1.cols, grey2.cols));
         int dftheight = cv::getOptimalDFTSize(std::max(grey1.rows, grey2.rows));
         cv::Mat fft1(cv::Size(dftwidth, dftheight), CV_32F, cv::Scalar(0));
         cv::Mat fft2(cv::Size(dftwidth, dftheight), CV_32F, cv::Scalar(0));

         grey1.convertTo(fft1, CV_32F);
         //   for(int j=0; j<grey1.rows; j++)
         //      for(int i=0; i<grey1.cols; i++)
         //         fft1.at<float>(j,i) = grey1.at<unsigned char>(j,i);

         grey2.convertTo(fft2, CV_32F);
         //   for(int j=0; j<grey2.rows; j++)
         //      for(int i=0; i<grey2.cols; i++)
         //         fft2.at<float>(j,i) = grey2.at<unsigned char>(j,i);

         cv::dft(fft1, fft1, 0, grey1.rows);
         cv::dft(fft2, fft2, 0, grey2.rows);
         cv::mulSpectrums(fft1, fft2, fft1, 0, true);
         cv::idft(fft1, fft1);
         double maxVal;
         cv::Point maxLoc;
         cv::minMaxLoc(fft1, NULL, &maxVal, NULL, &maxLoc);
         shiftx = (maxLoc.x < dftwidth / 2) ? (maxLoc.x) : (maxLoc.x - dftwidth);
         shifty = (maxLoc.y < dftheight / 2) ? (maxLoc.y) : (maxLoc.y - dftheight);
      }
      catch( cv::Exception& e )
      {
         std::cerr << "shift OpenCV Exception: " << e.what() << std::endl;
      }
   }

   void greyShift(int width, int height, unsigned char *im1, unsigned char *im2, int &xshift, int &yshift)
   //-----------------------------------------------------------------------------------------------------
   {
      try
      {
         cv::Mat rgba(height, width, CV_8UC4, im1);
         cv::Mat grey1(height, width, CV_8UC1), grey2(height, width, CV_8UC1);
         cv::cvtColor(rgba, grey1, cv::COLOR_RGBA2GRAY);
         rgba = cv::Mat(height, width, CV_8UC4, im2);
         cv::cvtColor(rgba, grey2, cv::COLOR_RGBA2GRAY);
         shift(grey1, grey2, xshift, yshift);
      }
      catch( cv::Exception& e )
      {
         std::cerr << "greyShift OpenCV Exception: " << e.what() << std::endl;
      }
   }

   bool kludgeRGBA(int width, int height, unsigned char *img, int shift, bool is2Right, unsigned char *imgout)
   //-----------------------------------------------------------------------------------------------------------
   {
      try
      {
         cv::Mat rgba(height, width, CV_8UC4, img), out = cv::Mat(height, width, CV_8UC4, imgout);
         rgba.copyTo(out);
         //assert(out.isContinuous());
         if (is2Right)
            rgba(cv::Rect(0, 0, rgba.cols-shift, rgba.rows)).
                 copyTo(out(cv::Rect(shift, 0, rgba.cols-shift, rgba.rows)));
         else
            rgba(cv::Rect(shift, 0, rgba.cols-shift, rgba.rows)).
                 copyTo(out(cv::Rect(0, 0, rgba.cols-shift, rgba.rows)));
         return true;
      }
      catch( cv::Exception& e )
      {
         std::cerr << "KLUDGE_RGBA OpenCV Exception: " << e.what() << std::endl;
      }
      catch (std::exception &e)
      {
         std::cerr << "KLUDGE_RGBA Fatal Exception: " << e.what();
      }
      return false;
   }

   bool stitch(std::vector<cv::Mat>& v, unsigned char *output)
   //---------------------------------------------------------
   {
      cv::Mat res; //(image1.rows, image1.cols*2, CV_8UC4);
      cv::Stitcher::Mode mode = cv::Stitcher::Mode::SCANS;
      cv::Ptr<cv::Stitcher> stitcher = cv::Stitcher::create(mode, false);
      // stitcher->setFeaturesFinder(cv::makePtr<cv::detail::OrbFeaturesFinder>(cv::Size(3,1), 1000));
      cv::Stitcher::Status status = stitcher->stitch(v, res);
      switch (status)
      {
         case cv::Stitcher::Status::OK:
         {
            cv::Mat result(v[0].rows, v[0].cols, CV_8UC4, output);
            cv::Mat resa(v[0].rows, v[0].cols, CV_8UC4);
            cv::cvtColor(res, resa, CV_RGB2RGBA);
            if ( (res.rows != v[0].rows) || (res.cols != v[0].cols) )
               cv::resize(resa, result, result.size(), 0, 0, cv::INTER_LANCZOS4);
            else
               resa.copyTo(result);
#ifdef DEBUG
            for (int i=0; i<v.size(); i++)
               cv::imwrite(std::to_string(i) + ".png", v[i]);
            cv::imwrite("stitched.png", result);
#endif
            return true;
         }
         case cv::Stitcher::Status::ERR_NEED_MORE_IMGS:
            std::cerr << "cv::STITCH: Stitcher returned ERR_NEED_MORE_IMGS" << std::endl; break;
         case cv::Stitcher::Status::ERR_HOMOGRAPHY_EST_FAIL:
            std::cerr << "cv::STITCH: Stitcher returned ERR_HOMOGRAPHY_EST_FAIL" << std::endl; break;
         case cv::Stitcher::Status::ERR_CAMERA_PARAMS_ADJUST_FAIL:
            std::cerr << "cv::STITCH: Stitcher returned ERR_CAMERA_PARAMS_ADJUST_FAIL" << std::endl; break;
         default:
            std::cerr << "cv::STITCH: Stitcher returned unknown status " << status << std::endl; break;
      }
      return true;
   }
   
   bool stitch3(int width, int height, unsigned char *img1, unsigned char *img2, unsigned char *img3,
                unsigned char *output)
   //-------------------------------------------------------------------------------------------
   {
      try
      {
         cv::Mat rgba1(height, width, CV_8UC4, img1), image1(height, width, CV_8UC3);
         cv::cvtColor(rgba1, image1, CV_RGBA2RGB);
         cv::Mat rgba2 = cv::Mat(height, width, CV_8UC4, img2), image2(height, width, CV_8UC3);
         cv::cvtColor(rgba2, image2, CV_RGBA2RGB);
         cv::Mat rgba3(height, width, CV_8UC4, img3), image3(height, width, CV_8UC3);
         cv::cvtColor(rgba3, image3, CV_RGBA2RGB);
         std::vector<cv::Mat> v;
         v.push_back(image1);
         v.push_back(image2);
         v.push_back(image3);
         return cvutil::stitch(v, output);
      }
      catch (std::exception &e)
      {
         std::cerr << "STITCH Fatal Exception: " << e.what() << std::endl;
         return false;
      }
   }

   bool stitch3(cv::Mat& rgba1, cv::Mat& rgba2, cv::Mat& rgba3, cv::Mat& stitchedImg)
   //------------------------------------------------------------------------------
   {
      try
      {
         cv::Mat image1, image2, image3;
         cv::cvtColor(rgba1, image1, CV_RGBA2RGB);
         cv::cvtColor(rgba2, image2, CV_RGBA2RGB);
         cv::cvtColor(rgba3, image3, CV_RGBA2RGB);
         std::vector<cv::Mat> v;
         v.push_back(image1);
         v.push_back(image2);
         v.push_back(image3);
         std::unique_ptr<unsigned char> output(new unsigned char[rgba1.step * rgba1.rows]);
         if (cvutil::stitch(v, output.get()))
         {
            stitchedImg = cv::Mat(rgba1.rows, rgba1.cols, CV_8UC4, output.get());
            return true;
         }
      }
      catch( cv::Exception& e )
      {
         std::cerr << "stitch3 OpenCV Exception: " << e.what() << std::endl;
      }
      catch (std::exception &e)
      {
         std::cerr << "stitch3 Exception: " << e.what() << std::endl;
      }
      return false;
   }
}