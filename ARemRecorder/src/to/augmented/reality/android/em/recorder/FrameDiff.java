package to.augmented.reality.android.em.recorder;

import android.util.Log;

import java.nio.ByteBuffer;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

public class FrameDiff
//====================
{
   static public boolean isNativeLoaded = false;

   static
   {
      try
      {
         System.loadLibrary("framediff");
         isNativeLoaded = true;
      }
      catch (Exception e)
      {
         Log.e("FrameDiff", "Error loading native library framediff.so for " + System.getProperty("os.arch"), e);
      }
   }

   public static native double PSNR(int width, int height, ByteBuffer image1, ByteBuffer image2);

   public static native double MSSIM(int width, int height, ByteBuffer image1, ByteBuffer image2);

   static public double psnr(int width, int height, byte[] image1, byte[] image2)
   //--------------------------------------------------------------
   {
      Mat nv21 = new Mat(height+height/2, width, CvType.CV_8UC1);
      nv21.put(0, 0, image1);
      Mat rgba1 = new Mat(height, width, CvType.CV_8UC4);
      Imgproc.cvtColor(nv21, rgba1, Imgproc.COLOR_YUV2RGBA_NV21);
      Mat rgba2 = new Mat(height, width, CvType.CV_8UC4);
      nv21.put(0, 0, image2);
      Imgproc.cvtColor(nv21, rgba2, Imgproc.COLOR_YUV2RGBA_NV21);
      Mat diff = new Mat();
      Core.absdiff(rgba1, rgba2, diff);
      diff.convertTo(diff, CvType.CV_32F);
      diff = diff.mul(diff);
      Scalar scalar = Core.sumElems(diff);
      double sse = scalar.val[0] + scalar.val[1] + scalar.val[2];
      if( sse <= 1e-10)
         return 0;
      else
      {
         double mse  = sse / (double)(rgba1.channels() * rgba1.total());
         return  10.0 * Math.log10((255 * 255) / mse);
      }
   }

   static public double mssim(int width, int height, byte[] image1, byte[] image2)
   //----------------------------------------------------------------------
   {
      Mat nv21 = new Mat(height+height/2, width, CvType.CV_8UC1);
      nv21.put(0, 0, image1);
      Mat rgba1 = new Mat(height, width, CvType.CV_8UC4);
      Imgproc.cvtColor(nv21, rgba1, Imgproc.COLOR_YUV2RGBA_NV21);
      Mat rgba2 = new Mat(height, width, CvType.CV_8UC4);
      nv21.put(0, 0, image2);
      Imgproc.cvtColor(nv21, rgba2, Imgproc.COLOR_YUV2RGBA_NV21);

      int d     = CvType.CV_32F;

      Mat I1 = new Mat(), I2 = new Mat();
      rgba1.convertTo(I1, d);
      rgba2.convertTo(I2, d);

      Mat rgba2_2 = I2.mul(I2);
      Mat rgba1_2 = I1.mul(I1);
      Mat rgba1_rgba2 = I1.mul(I2);

      Mat mu1 = new Mat(), mu2 = new Mat();
      Size eleven = new Size(11, 11);
      Imgproc.GaussianBlur(I1, mu1, eleven, 1.5);
      Imgproc.GaussianBlur(I2, mu2, eleven, 1.5);

      Mat mu1_2   =   mu1.mul(mu1);
      Mat mu2_2   =   mu2.mul(mu2);
      Mat mu1_mu2 =   mu1.mul(mu2);

      Mat sigma1_2 = new Mat(), sigma2_2 = new Mat(), sigma12 = new Mat();

      Imgproc.GaussianBlur(rgba1_2, sigma1_2, eleven, 1.5);
      Core.subtract(sigma1_2, mu1_2, sigma1_2);

      Imgproc.GaussianBlur(rgba2_2, sigma2_2, eleven, 1.5);
      Core.subtract(sigma2_2, mu2_2, sigma2_2);

      Imgproc.GaussianBlur(rgba1_rgba2, sigma12, eleven, 1.5);
      Core.subtract(sigma12, mu1_mu2, sigma12);

      ///////////////////////////////// FORMULA ////////////////////////////////
      Mat t1 = new Mat(), t2 = new Mat(), t3 = new Mat(), t = new Mat();
      Scalar two = new Scalar(2, 2, 2, 2), C1 = new Scalar(6.5025, 6.5025, 6.5025, 6.5025),
             C2 = new Scalar(58.5225, 58.5225, 58.5225, 58.5225);;

//      t1 = 2 * mu1_mu2 + C1;
      Core.multiply(mu1_mu2, two, t);
      Core.add(t, C1, t1);

//      t2 = 2 * sigma12 + C2;
      Core.multiply(sigma12, two, t);
      Core.add(t, C2, t2);

//      t3 = t1.mul(t2);              // t3 = ((2*mu1_mu2 + C1).*(2*sigma12 + C2))
      t3 = t1.mul(t2);

//      t1 = mu1_2 + mu2_2 + C1;
      Core.add(mu1_2, mu2_2, t);
      Core.add(t, C1, t1);

//      t2 = sigma1_2 + sigma2_2 + C2;
      Core.add(sigma1_2, sigma2_2, t);
      Core.add(t, C2, t2);

      t1 = t1.mul(t2);               // t1 =((mu1_2 + mu2_2 + C1).*(sigma1_2 + sigma2_2 + C2))

      Mat ssim_map = new Mat();
      Core.divide(t3, t1, ssim_map);      // ssim_map =  t3./t1;

      Scalar mssim = Core.mean(ssim_map ); // mssim = average of ssim map
      return (mssim.val[0] + mssim.val[1] + mssim.val[2]) / 3;
   }


/*
   static private double mean(Mat M, String message)
   {
      Scalar avg = Core.mean(M);
      Scalar sum = Core.sumElems(M);
      double single = (avg.val[0] + avg.val[1] + avg.val[2]) / 3;
      Log.i(message, String.format("%s [%.5f, %.5f, %.5f, %.5f] [%.5f, %.5f, %.5f, %.5f] %.5f", message,
                                   avg.val[0], avg.val[1], avg.val[2], avg.val[3],
                                   sum.val[0], sum.val[1], sum.val[2], sum.val[3], single));
      return single;
   }
*/
}
