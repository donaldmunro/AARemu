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

import android.util.*;

import java.util.concurrent.*;

public class RGBAtoRGB
//====================
{
   final static private String TAG = RGBAtoRGB.class.getSimpleName();

   static public boolean isNativeLoaded = false;

   static
   {
      try
      {
         System.loadLibrary("RGBAtoRGB");
         isNativeLoaded = true;
      }
      catch (Exception e)
      {
         Log.e(TAG, "Error loading native class", e);
      }
   }

   public static native void nativeRGBAtoRGB(byte[] rgba, byte[] rgb);

   public static native void nativeRGBAtoRGB565(byte[] rgba, byte[] rgb);

   public static void RGBAtoRGB(byte[] rgba, byte[] rgb)
   //-------------------------------------------------
   {
      if (isNativeLoaded)
         nativeRGBAtoRGB(rgba, rgb);
      else
      {
         ExecutorService pool = Executors.newFixedThreadPool(3);
         int i = 0;
         Future<?>[] futures = new Future[3];
         futures[i++] = pool.submit(new StripeConvertor(0, rgba, rgb));
         futures[i++] = pool.submit(new StripeConvertor(1, rgba, rgb));
         futures[i++] = pool.submit(new StripeConvertor(2, rgba, rgb));
         pool.shutdown();
         while (true)
         {
            int doneCount = 0;
            for (int j=0; j<3; j++)
            {
               if (futures[j].isDone())
                  doneCount++;
               else
               {
                  try
                  {
                     futures[j].get(100, TimeUnit.MILLISECONDS);
                  }
                  catch (InterruptedException e)
                  {
                     pool.shutdownNow();
                     return;
                  }
                  catch (Exception e)
                  {
                     continue;
                  }
               }
            }
            if (doneCount >= 3)
               break;
         }
      }
   }

   public static void RGBAtoRGB565(byte[] rgba, byte[] rgb)
   //------------------------------------------------------------
   {
      if (isNativeLoaded)
         nativeRGBAtoRGB565(rgba, rgb);
      else
      {
         ExecutorService pool = Executors.newFixedThreadPool(3);
         int i = 0;
         Future<?>[] futures = new Future[3];
         futures[i++] = pool.submit(new RGB565Convertor(0, rgba, rgb));
         futures[i++] = pool.submit(new RGB565Convertor(1, rgba, rgb));
         pool.shutdown();
         while (true)
         {
            int doneCount = 0;
            for (int j=0; j<2; j++)
            {
               if (futures[j].isDone())
                  doneCount++;
               else
               {
                  try
                  {
                     futures[j].get(100, TimeUnit.MILLISECONDS);
                  }
                  catch (InterruptedException e)
                  {
                     pool.shutdownNow();
                     return;
                  }
                  catch (Exception e)
                  {
                     continue;
                  }
               }
            }
            if (doneCount >= 2)
               break;
         }
      }

   }

   final static private int RGBA_STRIDE = 4;
   final static private int RGB_STRIDE = 3;

   static class StripeConvertor implements Runnable
   //==============================================
   {
      private final int stripe;
      private final byte[] rgb, rgba;

      StripeConvertor(int stripe, byte[] rgba, byte[] rgb)
      //--------------------------------------------------
      {
         this.stripe = stripe;
         this.rgba = rgba;
         this.rgb = rgb;
      }

      @Override
      public void run()
      //---------------
      {
         for (int i=stripe, j=stripe; i<rgba.length; i+= RGBA_STRIDE)
         {
            rgb[j] = rgba[i];
            j += RGB_STRIDE;
         }
      }
   }

   private static class RGB565Convertor implements Runnable
   //======================================================
   {
      private final int stripe;
      private final byte[] rgb, rgba;

      public RGB565Convertor(int stripe, byte[] rgba, byte[] rgb)
      //---------------------------------------------------------
      {
         this.stripe = stripe;
         this.rgba = rgba;
         this.rgb = rgb;

      }

      @Override
      public void run()
      //---------------
      {
         int half = rgba.length >> 1;
         int start =0, jstart=0, end =-1;
         if (stripe == 0)
         {
            start = 0;
            jstart = 0;
            end = half;
         }
         else
         {
            start = half;
            jstart = half >> 1;
            end = rgba.length;
         }
         for (int i=start, j=jstart; i<end; i+=4)
         {
            if ((i+2)<end)
            {
               short word = (short) (((short)(rgba[i] << 11)) | ((short)(rgba[i + 1] << 5)) | ((short) (rgba[i + 2])));
               rgb[j++] = (byte) (word >> 8);
               rgb[j++] = (byte) (word & 0XFF00);
            }
         }
      }
   }
}
