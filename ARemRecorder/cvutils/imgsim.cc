#include <iostream>
#include <opencv2/core/core.hpp>
#include <opencv2/core/mat.hpp>
#include <opencv2/imgproc/imgproc.hpp>
#include <fstream>
#include <iomanip>

#include <opencv2/highgui/highgui.hpp>

using namespace std;

double PSNR(int width, int height, uchar *img1, uchar *img2);
double MSSIM(int width, int height, uchar *img1, uchar *img2);
double getMSSIM(int width, int height, uchar *img1, uchar *img2);
double sum(cv::Mat M, std::string message);
double mean(cv::Mat M, std::string message);

int main(int argc, char **argv)
{
   uchar buf1[460800], buf2[460800];
   ifstream imgf1(argv[1]), imgf2(argv[2]);
   imgf1.read((char *) buf1, 460800);
   imgf2.read((char *) buf2, 460800);
   double psnr = PSNR(640, 480, buf1, buf2);
   double  mssim = MSSIM(640, 480, buf1, buf2);
   double  mssim2 = getMSSIM(640, 480, buf1, buf2);
   cout << " PSNR = " << psnr << " MSSIM = " << mssim << " MSSIM2 = " << mssim2 << std::endl;
//   char c = (char)cvWaitKey(300000);
   return 0;
}

double PSNR(int width, int height, uchar *img1, uchar *img2)
//--------------------------------------------------------------------------------------
{
   cv::Mat nv21(height+height/2, width, CV_8UC1, img1);
   cv::Mat rgba1(height, width, CV_8UC4), rgba2(height, width, CV_8UC4);
   cv::cvtColor(nv21, rgba1, CV_YUV2RGBA_NV21);
   nv21 = cv::Mat(height+height/2, width, CV_8UC1, img2);
   cv::cvtColor(nv21, rgba2, CV_YUV2RGBA_NV21);

//   cv::namedWindow("tst1", CV_WINDOW_AUTOSIZE);
//   cv::namedWindow("tst2", CV_WINDOW_AUTOSIZE);
//   cvMoveWindow("tst1", 400       , 0);
//   cvMoveWindow("tst2", 900       , 0);
//   cv::imshow("tst1", rgba1);
//   cv::imshow("tst2", rgba2);

   cv::Mat diff;
   cv::absdiff(rgba1, rgba2, diff);       // |I1 - I2|
   diff.convertTo(diff, CV_32F);  // cannot make a square on 8 bits
   diff = diff.mul(diff);           // |I1 - I2|^2
   cv::Scalar s = cv::sum(diff);        // sum elements per channel
   double sse = s.val[0] + s.val[1] + s.val[2]; // sum channels
   if( sse <= 1e-10) // for small values return zero
      return 0;
   else
   {
      double mse  = sse / (double)(rgba1.channels() * rgba1.total());
      return  10.0 * log10((255 * 255) / mse);
   }
}

double MSSIM(int width, int height, uchar *img1, uchar *img2)
//-----------------------------------------------------------
{
   const double C1 = 6.5025, C2 = 58.5225;
   int d     = CV_32F;
   cv::Mat nv21(height+height/2, width, CV_8UC1, img1);
   cv::Mat rgba1(height, width, CV_8UC4), rgba2(height, width, CV_8UC4);
   cv::cvtColor(nv21, rgba1, CV_YUV2RGBA_NV21);
   nv21 = cv::Mat(height+height/2, width, CV_8UC1, img2);
   cv::cvtColor(nv21, rgba2, CV_YUV2RGBA_NV21);

   rgba1.convertTo(rgba1, d);           // cannot calculate on one byte large values
   rgba2.convertTo(rgba2, d);

   cv::Mat rgba2_2 = rgba2.mul(rgba2);
   cv::Mat rgba1_2 = rgba1.mul(rgba1);
   cv::Mat rgba1_rgba2 = rgba1.mul(rgba2);

   cv::Mat mu1, mu2;
   cv::GaussianBlur(rgba1, mu1, cv::Size(11, 11), 1.5);
   cv::GaussianBlur(rgba2, mu2, cv::Size(11, 11), 1.5);

   cv::Mat mu1_2   =   mu1.mul(mu1);
   cv::Mat mu2_2   =   mu2.mul(mu2);
   cv::Mat mu1_mu2 =   mu1.mul(mu2);

   cv::Mat sigma1_2, sigma2_2, sigma12;

   cv::GaussianBlur(rgba1_2, sigma1_2, cv::Size(11, 11), 1.5);
   sigma1_2 -= mu1_2;

   cv::GaussianBlur(rgba2_2, sigma2_2, cv::Size(11, 11), 1.5);
   sigma2_2 -= mu2_2;

   cv::GaussianBlur(rgba1_rgba2, sigma12, cv::Size(11, 11), 1.5);
   sigma12 -= mu1_mu2;

   ///////////////////////////////// FORMULA ////////////////////////////////
   cv::Mat t1, t2, t3;

   t1 = 2 * mu1_mu2 + C1;
   t2 = 2 * sigma12 + C2;
   t3 = t1.mul(t2);              // t3 = ((2*mu1_mu2 + C1).*(2*sigma12 + C2))

   t1 = mu1_2 + mu2_2 + C1;
   t2 = sigma1_2 + sigma2_2 + C2;
   t1 = t1.mul(t2);               // t1 =((mu1_2 + mu2_2 + C1).*(sigma1_2 + sigma2_2 + C2))

   cv::Mat ssim_map;
   divide(t3, t1, ssim_map);      // ssim_map =  t3./t1;

   cv::Scalar mssim = mean( ssim_map ); // mssim = average of ssim map
   return (mssim.val[0] + mssim.val[1] + mssim.val[2]) / 3;
}

double getMSSIM(int width, int height, uchar *img1, uchar *img2)
{
   cv::Mat nv21(height+height/2, width, CV_8UC1, img1);
   cv::Mat rgba1(height, width, CV_8UC4), rgba2(height, width, CV_8UC4);
   cv::cvtColor(nv21, rgba1, CV_YUV2RGBA_NV21);
   nv21 = cv::Mat(height+height/2, width, CV_8UC1, img2);
   cv::cvtColor(nv21, rgba2, CV_YUV2RGBA_NV21);    
   
   const double C1 = 6.5025, C2 = 58.5225;
    /***************************** INITS **********************************/
    int d     = CV_32F;

    cv::Mat I1, I2;
    rgba1.convertTo(I1, d);           // cannot calculate on one byte large values
    rgba2.convertTo(I2, d);    
    
    cv::Mat I2_2   = I2.mul(I2);        // I2^2
    cv::Mat I1_2   = I1.mul(I1);        // I1^2
    cv::Mat I1_I2  = I1.mul(I2);        // I1 * I2

    /*************************** END INITS **********************************/

    cv::Mat mu1, mu2;   // PRELIMINARY COMPUTING
    GaussianBlur(I1, mu1, cv::Size(11, 11), 1.5);
    GaussianBlur(I2, mu2, cv::Size(11, 11), 1.5);

    cv::Mat mu1_2   =   mu1.mul(mu1);
    cv::Mat mu2_2   =   mu2.mul(mu2);
    cv::Mat mu1_mu2 =   mu1.mul(mu2);

    cv::Mat sigma1_2, sigma2_2, sigma12;

    GaussianBlur(I1_2, sigma1_2, cv::Size(11, 11), 1.5);
    sigma1_2 -= mu1_2;

    GaussianBlur(I2_2, sigma2_2, cv::Size(11, 11), 1.5);
    sigma2_2 -= mu2_2;

    GaussianBlur(I1_I2, sigma12, cv::Size(11, 11), 1.5);
    sigma12 -= mu1_mu2;    
    
    ///////////////////////////////// FORMULA ////////////////////////////////
    cv::Mat t1, t2, t3;

    t1 = 2 * mu1_mu2 + C1;        
    t2 = 2 * sigma12 + C2;
    t3 = t1.mul(t2);              // t3 = ((2*mu1_mu2 + C1).*(2*sigma12 + C2))            

    t1 = mu1_2 + mu2_2 + C1;
    t2 = sigma1_2 + sigma2_2 + C2;
    t1 = t1.mul(t2);               // t1 =((mu1_2 + mu2_2 + C1).*(sigma1_2 + sigma2_2 + C2))        

    cv::Mat ssim_map;
    divide(t3, t1, ssim_map);      // ssim_map =  t3./t1;        

    cv::Scalar mssim = mean( ssim_map ); // mssim = average of ssim map
    return (mssim.val[0] + mssim.val[1] + mssim.val[2]) / 3;
    //return mssim;
}

double mean(cv::Mat M, std::string message)
{
   cv::Scalar avg = mean(M);
   std::cout << message << " " << std::fixed << std::setw(11) << std::setprecision(5) << avg << " " << 
                cv::sum(M) << " " << (avg.val[0] + avg.val[1] + avg.val[2]) / 3 << std::endl;
   return (avg.val[0] + avg.val[1] + avg.val[2]) / 3;
}

double sum(cv::Mat M, std::string message)
{
   double sum=0;
   for(int i = 0; i < M.rows; i++)
   {
      const double* Mi = M.ptr<double>(i);
      for(int j = 0; j < M.cols; j++)
         sum += std::max(Mi[j], 0.);
   }
   std::cout << message << sum << std::endl;

/*   cv::MatConstIterator<cv::Vec3f> it = m.begin();
   while ( it != m.end() ) 
   {
      sum + = (*it)[0];
      it++;
   }*/
}

