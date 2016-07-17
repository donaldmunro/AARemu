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

package to.augmented.reality.android.em.sample;

/*
Hard coded parameters. Implementing them in the UI is left as an exercise for the reader.
 */
public class EmulationControls
//============================
{
   // true emulates Camera 2 API (requires >= Lollipop), false emulates old Camera API
   final static public boolean USE_CAMERA2_API = false;

   // true uses code which emulates API calls with mock objects (where possible),
   // false uses the ARCameraInterface shortcuts.
   final static public boolean IS_EMULATE_CAMERA = false;

   //true skips repeated frames, false shows all frames (for RecordingType.FREE recordings).
   final static public boolean DIRTY_VIDEO = false;

   //Frames per second. If DIRTY_VIDEO is false then FPS > 0 plays back at the specified frame rate
   // while FPS <= 0 attempts to play back at the original rate using stored timestamps.
   final static public int FPS = 0;

   // true to continue repeating RecordingType.FREE recordings
   final static public boolean REPEAT = true;

   // true to test raw sensor input (assumes file sensordata.raw exists and contains Rotation Vector data)
   public static final boolean USE_RAW_ROTATION_VEC = true;
}
