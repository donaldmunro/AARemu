ARem
====

ARem is a software tool enabling simulation of Augmented Reality
by allowing an AR developer to record a 360 degree view of a
location using the devices camera and orientation sensors (this
functionality is provided by the ARemRecorder app). The ARCamera
class which provides an impostor of the Android camera class
can then be used to preview the recorded scene instead of the live
camera preview provided by the Android Camera class. The ARCamera
preview callback is analogous to the standard Camera preview
callback except that the preview bytes provided in the callback
are extracted from a file created by the recorder application
based on the current bearing returned by the orientation
sensor(s). These preview bytes are passed to the development code
via the same preview callback as provided by the standard Camera
classes and can thus be processed by Computer Vision algorithms
before being displayed by the client application. The frames are
stored as individual video frames in RGBA, RGB or RGB565 format
and not as video so the preview can be accessed in both
clockwise and anti-clockwise directions.

The tool is aimed at developers of outdoor mobile AR application
as it allows the developer to record one or more 360 degree
panoramas of a given location and then debug and test the AR
application in the comfort of a office or home without having to
make extensive changes to the programming
code.
