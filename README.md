<b>NEWS:</b> 
* Project name changed from ARem to AARemu (Android Augmented Reality Emulator) to prevent 
any confusion between this project and Augmented Reality Environment Modeling which shares the acronym. 

* 08-Jul-2016 - Updated to version 2 which is close to being a complete rewrite.

Description
===========
 AARemu is a software tool enabling simulation of Augmented Reality
 by allowing an AR developer to record either an interactive 360 degree view 
or a non-interactive free form view of a location using the devices camera and 
orientation/location sensors (this functionality is provided by the ARemRecorder app). 
For developers wanting to process the raw sensor data instead of using 
the provided 'cooked' orientation data , it is also possible to record raw sensor data 
for orientation sensors such as the Rotation Vector, Accelerometer, 
Linear Accelerometer, Gravity and Magnetic

The ARCamera and ARCameraDevice.class which provides a mock of the Android 
Camera and Camera2 classes respectively can then be used to preview the recorded 
scene instead of the live camera preview provided by the Android Camera class. 
The  preview callbacks are analogous to the standard Camera preview
 callback except that the preview bytes provided in the callback
 are extracted from a file created by the recorder application. In the 360 
degree interactive version the frame displayed is based on the current bearing 
returned by the (live) orientation  sensor(s). These preview bytes are passed 
to the development code via the same preview callback as provided by the 
standard Camera/Camera2 classes and can thus be processed by Computer 
Vision algorithms before being displayed by the client application. 
The frames are stored as individual video frames in RGBA format
 and not as video so the preview can be accessed in both
 clockwise and anti-clockwise directions.

Components
==========


AARemu comprises the following components (or modules in Android Studio parlance)

ARemRecorder
-------------------

<h3>Description and Usage</h3>
Used to record an interactive  360 degree view or a free form non-interactive view at a given location 
(binary available on Google Play).

The recorder displays the camera output in full screen mode with a interface drawer on the left border
of the display which can be dragged out. To start recording drag the drawer out and click either the 
360 degree ![alt tag](https://github.com/donaldmunro/AARemu/blob/master/ARemRecorder/res/drawable-mdpi/three60.png?raw=true) or the free form  ![alt tag](https://github.com/donaldmunro/AARemu/blob/master/ARemRecorder/res/drawable-mdpi/frame.png?raw=true) recording button. At start of recording the user is asked to 
provide several parameters which are described below.

<b>Recording Name</b>: The name is used to create a directory using /sdcard/Documents/ARRecorder 
as a base. If a recording by the name provided already exists then the user is prompted as to whether to 
overwrite or not.

The <b>resolution</b> can be selected in a spinner which provides all of the resolutions
supported by the device. 

For 360 recordings the <b>recording increment</b> specifies the bearing increment between which 
frames are saved, however the value may be overridden by the post-processing step if there are
too many errors for the specified increment (eg saving every 1 degree could be incremented to
1.5).


The <b>Rotation sensor</b> specifies which orientation sensor fusion method to use for calculating the 
device orientation and bearing for cooked orientation data. The sensor fusion
code is based on work done by Alexander Pacha for his Masters thesis (see Credits below), although
the vanilla Rotation Vector provided by Android which used a Kalman filter can also be used (in some
cases it may provide better results due to manufacturers knowledge of idiosyncrasies of the hardware
sensors used). The vanilla rotation vector is also the default sensor.

<b>Max Size</b> specifies the maximum size the recording can grow to (suffixed by 'G' for gigabytes,
'M' for megabytes or 'K' for kilobytes - no suffix means bytes).

<b>Record Sensor</b> a a multi-select spinner which may be used to select raw orientation sensors to 
record data for.

<b>The debug</b> checkbox is mostly used when developing the recording application and results in
intermediate files 

The <b>Flash On</b> checkbox specifies switching the camera flash on in flashlight mode
for recording in the dark (only visible if the device supports a flash).

The <b>Camera2 API</b> specifies using the new Camera2 API.

 
When recording keeping the device at a constant vertical angle and rotating slowly and smoothly is important
for accurate recording. 

<h3>Issues, Caveats and Recommendations</h3>
The two main issues for the 360 degree recording process are the stability of the compass bearing sensors 
and ensuring that the frame that gets saved for a given bearing is the correct frame for that bearing. 
The recorder uses a postprocessing step to attempt to minimize errors b y filtering bad frames. 
On some occasions there may still be a discontinuity between the start and end frame which may be 
addressed in a future version by finding features in the first  frame and then trying to match them in 
the final one. In the meantime The DisplayFrames app allows the insertion of single frames to
overwrite the problematic ones.

If the bearings seem suspect before starting recording then it may improve if the sensors are "calibrated"
by waving the device in a figure of eight as seen in this [video](https://www.youtube.com/watch?v=sP3d00Hr14o).
OTOH this may be an old wives tale, YMMV. If the bearings suddenly deviate by a large amount during the 
recording then it may be best to cancel the recording (press the recording button again) and start again.

ARemu
-----
<h3>Description and Usage</h3>
This module provides the ARCamera and ARCamera2 mock classes as well as  supporting classes and is thus the primary module
to be used by AR applications wishing to emulate an AR environment. In addition to the Camera API methods,
ARCamera also provides supplementary API methods specific to playing back recordings such as setting the recording files. The
render mode can also be set to dirty where frames are previewed only when the bearing changes, or continuous
where the preview continuously receives frames. In the latter case the frame rate specified in the
Camera.Parameters returned by ARCamera.getParameters is respected. When creating applications it is possible to
emulate the C/C++ preprocessor #ifdef statements in Java by using the Just in Time (JIT) feature of the Java
runtime to optimise out unused code (unfortunately unlike for C, it does not reduce code size, just speed):

<code>
static final boolean EMULATION = true;<br>
if (EMULATION)<br>
&nbsp;&nbsp;&nbsp;cameraEm.startPreview();<br>
else<br>
&nbsp;&nbsp;&nbsp;camera.startPreview();<br>
</code>
In the code above the unused branch will be optimised out.

If mocking is not required then it is also possible to directly control the emulation using as both camera classes 
implement the ARCameraInterface interface which bypasses the mocking resulting in code that is easier to read.
The JIT feature mentioned above can then be used to choose emulation or live code.

The <i>ARSEnsorManager</i> class mocks the Android <i>SensorManager</i> class and provides replay
access to recorded raw orientation sensor data.

Common
------
Provides various classes shared across different modules such as the orientation fusion implementation and
various OpenGL and math helper classes.

ARemSample
----------
This is a sample application which can be used to play back a recording made with ARemRecorder. It also
makes use of the ARemu review supplementary API which allows 360 degree playback without having to move
the device. Will also be available on Google Play.

DisplayFrames
-------------
This is another sample application which can also be used to play back a recording made with ARemRecorder but
provides an option to set a start degree and single step forwards or backwards through the frames. As mentioned 
previously, it can also be used to overwrite problematic frames.

AROpenCVemu
-----------
Provides an implementation of a CameraBridgeViewBase OpenCV4Android camera preview UI view class which uses
ARCamera instead of the Android Camera class. This could have been included in ARemu, however this
would have resulted in a dependency on OpenCV for ARem.

ARemOpenCVSample
----------------
Provides a sample using the CameraBridgeViewBase derived OpenCV4Android camera preview UI view class
implemented in AROpenCVemu.

OpenCV
------
OpenCV4Android as used by the OpenCV modules.

Credits
=======

ADraweLayout which is used to provide a sliding drawer UI is copyright DoubleTwist and is licensed under the
Apache license. See their [Github site](https://github.com/doubletwist/adrawerlayoutlib).

MultiSpinner (https://android-arsenal.com/details/1/1839) provides a multi-select spinner (MIT license).

<p>
    Much of the sensor code is based on  <a href="https://bitbucket.org/apacha/sensor-fusion-demo">work</a> done by
    Alexander Pacha for his <a href="http://my-it.at/media/MasterThesis-Pacha.pdf">Masters thesis</a>. The fused
    Gyroscope/Accelerometer/Magnet sensor is based on code by Kaleb Kircher
    (See his <a href="http://www.kircherelectronics.com/blog/index.php/11-android/sensors/16-android-gyroscope-fusion">blog</a>).
</p>

