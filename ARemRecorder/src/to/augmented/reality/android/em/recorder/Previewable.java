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

import android.content.Context;
import android.hardware.camera2.CameraAccessException;

public interface Previewable extends Freezeable
//=============================================
{
   boolean open(int facing, int width, int height, StringBuilder errbuf) throws CameraAccessException;

   void close();

   void startPreview(boolean isFlashOn);

   void stopPreview();

   void suspendPreview();

   void restartPreview();

   boolean isPreviewing();

   void releaseFrame();

   int getPreviewWidth();

   int getPreviewHeight();

   void setPreviewWidth(int width);

   void setPreviewHeight(int height);

   String getPreviewFormat();

   int getPreviewBufferSize();

   String[] availableResolutions();

   float getFocalLen();

   float getFovx();

   float getFovy();

   PreviewData pop();

   byte[] toRGBA(Context context, byte[] frame, int previewWidth, int previewHeight, int rgbaSize, byte[] grey);



   boolean isFlashOn();

   boolean hasFlash();

   void setFlash(boolean isOnOff);
}
