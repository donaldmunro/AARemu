/*
* Copyright (C) 2014 Donald Munro.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package to.augmented.reality.android.em.recorder;

import android.util.Log;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.nio.ByteBuffer;

public class CV
//=============
{
   static public boolean isNativeLoaded = false;

   static
   {
      try
      {
         System.loadLibrary("opencv_java3");
         System.loadLibrary("cv");
         isNativeLoaded = true;
      }
      catch (Exception e)
      {
         Log.e("CV", "Error loading native library libcv.so for " + System.getProperty("os.arch"), e);
      }
   }

   public static native double PSNR(int width, int height, ByteBuffer image1, ByteBuffer image2) throws NativeCVException;

   public static native double PSNR(int width, int height, byte[] image1, byte[] image2) throws NativeCVException;

   public static native double MSSIM(int width, int height, ByteBuffer image1, ByteBuffer image2) throws NativeCVException;

   public static native double MSSIM(int width, int height, byte[] image1, byte[] image2) throws NativeCVException;

   public static native void SHIFT(int width, int height, ByteBuffer image1, ByteBuffer image2, int[] result) throws NativeCVException;

   public static native void TOGREY_SHIFT(int width, int height, ByteBuffer image1, ByteBuffer image2, int[] result) throws NativeCVException;

   public static native void SHIFT(int width, int height, byte[] image1, byte[] image2, int[] result) throws NativeCVException;

   public static native void TOGREY_SHIFT(int width, int height, byte[] image1, byte[] image2, int[] result) throws NativeCVException;

   public static native boolean STITCH3(int width, int height, ByteBuffer image1, ByteBuffer image2, ByteBuffer image3,
                                        ByteBuffer result) throws NativeCVException;

   public static native boolean STITCH3(int width, int height, byte[] image1, byte[] image2, byte[] image3,
                                        byte[] result) throws NativeCVException;

   public static native boolean KLUDGE_NV21(int width, int height, ByteBuffer image, int shift, boolean isRight,
                                            ByteBuffer imageOut)  throws NativeCVException;

   public static native boolean KLUDGE_NV21(int width, int height, byte[] image, int shift, boolean isRight,
                                            byte[] imageOut)  throws NativeCVException;

   public static native boolean KLUDGE_RGBA(int width, int height, ByteBuffer image, int shift, boolean isRight,
                                            ByteBuffer imageOut)  throws NativeCVException;

   public static native boolean KLUDGE_RGBA(int width, int height, byte[] image, int shift, boolean isRight,
                                            byte[] imageOut) throws NativeCVException;

   public static native boolean GPUSTATUS(StringBuilder status);

   static public void shifted(int width, int height, byte[] image1, byte[] image2, int[] result)
   //-------------------------------------------------------------------------------------------
   {
      Mat nv21 = new Mat(height+height/2, width, CvType.CV_8UC1);
      nv21.put(0, 0, image1);
      Mat grey1 = new Mat(height, width, CvType.CV_8UC1);
      Imgproc.cvtColor(nv21, grey1, Imgproc.COLOR_YUV2GRAY_420);
      Mat grey2 = new Mat(height, width, CvType.CV_8UC1);
      nv21.put(0, 0, image2);
      Imgproc.cvtColor(nv21, grey2, Imgproc.COLOR_YUV2GRAY_420);

      int dftwidth = Core.getOptimalDFTSize(Math.max(grey1.cols(), grey2.cols()));
      int dftheight = Core.getOptimalDFTSize(Math.max(grey1.rows(), grey2.rows()));
      Mat fft1 = new Mat(new Size(dftwidth, dftheight), CvType.CV_32F, new Scalar(0));
      Mat fft2 = new Mat(new Size(dftwidth, dftheight), CvType.CV_32F, new Scalar(0));

      grey1.convertTo(fft1, CvType.CV_32F);
      grey2.convertTo(fft2, CvType.CV_32F);
//      for(int j=0; j<grey1.rows(); j++)
//         for(int i=0; i<grey1.cols(); i++)
//            fft1.put(j,i, grey1.get(j,i));
//
//      for(int j=0; j<grey2.rows(); j++)
//         for(int i=0; i<grey2.cols(); i++)
//            fft2.put(j,i, grey2.get(j, i));

      Core.dft(fft1, fft1, 0, grey1.rows());
      Core.dft(fft2, fft2, 0, grey2.rows());
      Core.mulSpectrums(fft1, fft2, fft1, 0, true);
      Core.idft(fft1, fft1);

      Core.MinMaxLocResult max = Core.minMaxLoc(fft1);
      //double maxVal = max.maxVal;
      Point maxLoc = max.maxLoc;
      result[0] = (int) ((maxLoc.x < dftwidth/2) ? (maxLoc.x) : (maxLoc.x - dftwidth));
      result[1] = (int) ((maxLoc.y < dftheight/2) ? (maxLoc.y) : (maxLoc.y - dftheight));
   }

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
}
