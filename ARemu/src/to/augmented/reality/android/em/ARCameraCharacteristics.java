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

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Range;
import android.util.Size;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ARCameraCharacteristics
//-----------------------------------
{
   CameraCharacteristics characteristics;

   Size size;

   public ARCameraCharacteristics(int width, int height, CameraCharacteristics characteristics)
   //-------------------------------------------------------------------------------------------
   {
      size = new Size(width, height);
      SCALER_STREAM_CONFIGURATION_MAP.m.put("android.scaler.streamConfigurationMap", new ARStreamConfigurationMap(size));
      if (characteristics != null)
      {
         this.characteristics = characteristics;
         Integer level = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
         INFO_SUPPORTED_HARDWARE_LEVEL.m.put("android.info.supportedHardwareLevel", level);
      }
   }

   public <T> T get(CameraCharacteristics.Key<T> key) { return characteristics.get(key); }

   public <T> T get(ARCameraCharacteristics.Key<T> key) { return key.m.get(key.name); }

   public List<CameraCharacteristics.Key<?>> getKeys() {return characteristics.getKeys();}

   public List<CaptureRequest.Key<?>> getAvailableCaptureRequestKeys() {return characteristics.getAvailableCaptureRequestKeys();}

   public List<CaptureResult.Key<?>> getAvailableCaptureResultKeys() {return characteristics.getAvailableCaptureResultKeys();}

   static public final class Key<T>
   {
      String name;
      Map<String, T> m = new HashMap<>();

      public Key(String name, T v) { this.name = name; m.put(name, v); }
   }

   static public final Key<android.util.Range<Integer>[]> CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES =
         new Key<android.util.Range<Integer>[]>("android.control.aeAvailableTargetFpsRanges",
                                                new Range[] { new Range(24,24), new Range(30,30) });

   static public final Key<Integer> LENS_FACING = new Key<Integer>("android.lens.facing", CameraCharacteristics.LENS_FACING_BACK);

   static public final Key<Integer> INFO_SUPPORTED_HARDWARE_LEVEL =
         new Key<Integer>("android.info.supportedHardwareLevel", CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL);

   static public final Key<int[]> SCALER_AVAILABLE_FORMATS =
         new Key<int[]>("android.scaler.availableFormats", new int[] { ARImageFormat.RGBA });

   static public final Key<ARStreamConfigurationMap> SCALER_STREAM_CONFIGURATION_MAP =
         new Key<ARStreamConfigurationMap>("android.scaler.streamConfigurationMap", null);


   public final class ARStreamConfigurationMap
   //=======================================
   {
      Size[] sizes;

      public ARStreamConfigurationMap(Size size)
      {
         sizes = new Size[1];
         sizes[0] = size;
      }

      public final int[] getOutputFormats() { return new int[] { ARImageFormat.RGBA };}

      public Size[] getOutputSizes(final int format) { return sizes; }

      public <T> Size[] getOutputSizes(Class<T> klass) { return sizes; }


   }

}
