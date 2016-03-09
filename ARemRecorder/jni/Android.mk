HOME := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_PATH := $(HOME)/cv
OPENCV_LIB_TYPE := STATIC
OPENCV_CAMERA_MODULES := off
OPENCV_INSTALL_MODULES := on
include $(HOME)/opencv/native/jni/OpenCV.mk

LOCAL_PATH := $(HOME)/cv

LOCAL_MODULE    := cv

LOCAL_SRC_FILES := cv.cc

LOCAL_ARM_MODE := arm

LOCAL_CPP_EXTENSION := .cc
LOCAL_CPP_FEATURES += rtti
LOCAL_CPP_FEATURES += exceptions
LOCAL_C_INCLUDES = $(HOME)/opencv/native/jni/include/

LOCAL_LDLIBS := -lstdc++
LOCAL_LDLIBS += -llog
LOCAL_LDLIBS += -lz

#LOCAL_STATIC_LIBRARIES += opencv_core
#LOCAL_STATIC_LIBRARIES += opencv_imgproc
#ifneq ($(TARGET_ARCH_ABI), arm64-v8a)
#   ifneq ($(TARGET_ARCH_ABI), armeabi)
#      LOCAL_STATIC_LIBRARIES += tbb # Intel thread building blocks
#   endif
#endif

#include $(CLEAR_VARS)
#LOCAL_MODULE := opencv_core
#LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libopencv_core.a
#include $(PREBUILT_STATIC_LIBRARY)
#include $(CLEAR_VARS)
#LOCAL_MODULE := opencv_imgproc
#LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libopencv_imgproc.a
#include $(PREBUILT_STATIC_LIBRARY)
#ifneq ($(TARGET_ARCH_ABI), arm64-v8a)
#   ifneq ($(TARGET_ARCH_ABI), armeabi)
#      include $(CLEAR_VARS)
#      LOCAL_MODULE := tbb
#      LOCAL_SRC_FILES := libs/$(TARGET_ARCH_ABI)/libtbb.a
#      include $(PREBUILT_STATIC_LIBRARY)
#   endif
#endif

include $(BUILD_SHARED_LIBRARY)

######################################################

include $(CLEAR_VARS)

LOCAL_PATH := $(HOME)/rgba2rgb
LOCAL_MODULE    := RGBAtoRGB
LOCAL_SRC_FILES := RGBAtoRGB.c
LOCAL_ARM_MODE := arm
LOCAL_LDLIBS := -llog
TARGET_ARCH_ABI := armeabi-v7a x86

include $(BUILD_SHARED_LIBRARY)
