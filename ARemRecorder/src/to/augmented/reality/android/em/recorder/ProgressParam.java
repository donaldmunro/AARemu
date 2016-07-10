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

public class ProgressParam
//=========================
{
   float bearing, targetBearing;
   float[] color = new float[3];
   int progress;
   String status;
   boolean isToast = false, isBearingsOnly = true;
   int toastDuration;

   public void set(float bearing, float targetBearing, float[] color, int progress)
   //--------------------------------------------------------------------
   {
      this.bearing = bearing;
      this.targetBearing = targetBearing;
      if (color != null)
         System.arraycopy(color, 0, this.color, 0, 3);
      else
         this.color[0] = Float.MIN_VALUE;
      isBearingsOnly = true;
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
      isBearingsOnly = false;
   }
}
