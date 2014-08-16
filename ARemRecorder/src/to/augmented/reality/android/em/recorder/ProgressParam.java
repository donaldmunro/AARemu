
package to.augmented.reality.android.em.recorder;


public class ProgressParam
//=========================
{
   float bearing, targetBearing;
   float[] color = new float[3];
   int progress;
   String status;
   boolean isToast = false;
   int toastDuration;

   public void set(float bearing, float targetBearing, float[] color) { this.set(bearing, targetBearing, color, -1); }

   public void set(float bearing, float targetBearing, float[] color, int progress)
   //--------------------------------------------------------------------
   {
      this.bearing = bearing;
      this.targetBearing = targetBearing;
      if (color != null)
         System.arraycopy(color, 0, this.color, 0, 3);
      else
         this.color[0] = Float.MIN_VALUE;
      this.progress = progress;
   }

   public void setStatus(String status, int progress, boolean mustToast, int toastDuration)
   //--------------------------------------------------------------------------------------
   {
      this.bearing = -1;
      this.status = status;
      this.isToast = mustToast;
      this.toastDuration = toastDuration;
      this.progress = progress;
   }
}
