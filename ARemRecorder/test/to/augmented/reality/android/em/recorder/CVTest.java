package to.augmented.reality.android.em.recorder;

import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;

import static org.junit.Assert.*;

/**
 * Created by root on 28/09/15.
 */
public class CVTest
{
   final static int NV21_640x480 =  460800;

   @Test
   public void testShifted() throws Exception
   {
      File f1 = new File("cvutils/1");
      assertTrue(f1.exists());
      File f2 = new File("cvutils/2");
      assertTrue(f2.exists());
      byte[] image1 = new byte[460800];
      byte[] image2 = new byte[460800];
      FileInputStream fis = new FileInputStream(f1);
      fis.read(image1);
      fis.close();
      fis = new FileInputStream(f2);
      fis.read(image2);
      fis.close();
      int[] res = new int[2];
      CV.shifted(640, 480, image1, image2, res);
      System.out.println(res[0]);
   }
}