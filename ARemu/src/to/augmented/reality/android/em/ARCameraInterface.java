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

package to.augmented.reality.android.em;

import android.location.LocationListener;
import android.util.Size;
import to.augmented.reality.android.common.sensor.orientation.OrientationListenable;

import java.io.File;

public interface ARCameraInterface extends Latcheable
//===================================================
{
   void setFrameRate(int fps);

   int getFrameRate();

   void setRenderMode(int renderMode);

   int getRenderMode();

   RecordingType getRecordingType();

   File getHeaderFile();

   File getFramesFile();

   File getOrientationFile();

   File getLocationFile();

   int getPreviewBufferSize();

   String getHeader(String k);

   String getHeader(String k, String def);

   int getHeaderInt(String k, int def);

   float getHeaderFloat(String k, float def);

   Size getPreviewSize();

   int getPreviewWidth();

   int getPreviewHeight();

   void setRepeat(boolean isRepeat);

   void setFreePreviewListener(FreePreviewListenable progress);

   void setPreviewCallback(Object callback);

   void setPreviewCallbackWithBuffer(Object callback);

   void setLocationListener(LocationListener locationListener);

   void setBearingListener(BearingListener bearingListener);

   void setOrientationListener(OrientationListenable listener);

   void addCallbackBuffer(byte[] buffer);

   void setARSensorManager(ARSensorManager sensorManager);

   void startPreview();
}
