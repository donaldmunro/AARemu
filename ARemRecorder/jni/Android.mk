HOME := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_PATH := $(HOME)/cv
OPENCV_LIB_TYPE := STATIC
OPENCV_CAMERA_MODULES := off
OPENCV_INSTALL_MODULES := on
include $(HOME)/opencv/native/jni/OpenCV.mk

LOCAL_MODULE    := cv

LOCAL_SRC_FILES := cv.cc

LOCAL_ARM_MODE := arm
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
   LOCAL_ARM_NEON := true
   LOCAL_CFLAGS += -DHAVE_NEON=1
endif

LOCAL_CPP_EXTENSION := .cc
LOCAL_CPP_FEATURES += rtti
LOCAL_CPP_FEATURES += exceptions
LOCAL_C_INCLUDES = $(HOME)/opencv/native/jni/include/

LOCAL_LDLIBS := -lstdc++
LOCAL_LDLIBS += -llog
LOCAL_LDLIBS += -lz

#TARGET_ARCH_ABI := armeabi-v7a x86

include $(BUILD_SHARED_LIBRARY)

######################################################

include $(CLEAR_VARS)

LOCAL_PATH := $(HOME)/rgba2rgb
LOCAL_MODULE    := RGBAtoRGB
LOCAL_MODULE_FILENAME := RGBAtoRGB
LOCAL_SRC_FILES := RGBAtoRGB.c
LOCAL_ARM_MODE := arm
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
   LOCAL_ARM_NEON := true
   LOCAL_CFLAGS += -DHAVE_NEON=1
endif
LOCAL_LDLIBS := -llog
#TARGET_ARCH_ABI := armeabi-v7a x86

include $(BUILD_SHARED_LIBRARY)

#####################################################

#include $(CLEAR_VARS)

#LOCAL_PATH := $(HOME)/snappy
#LOCAL_MODULE    := snappy
#LOCAL_SRC_FILES := snappy-sinksource.cc snappy-stubs-internal.cc snappy.cc
#LOCAL_ARM_MODE := arm
#ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
#   LOCAL_ARM_NEON := true
#   LOCAL_CFLAGS += -DHAVE_NEON=1
#endif
#LOCAL_LDLIBS := -llog
#TARGET_ARCH_ABI := armeabi-v7a x86

#include $(BUILD_SHARED_LIBRARY)

#########################################################

include $(CLEAR_VARS)

LOCAL_PATH := $(HOME)/framebuffer
LOCAL_MODULE    := framebuffer
LOCAL_SRC_FILES := snappy-sinksource.cc snappy-stubs-internal.cc snappy.cc framebuffer.cc
LOCAL_ARM_MODE := arm
ifeq ($(TARGET_ARCH_ABI),armeabi-v7a)
   LOCAL_ARM_NEON := true
   LOCAL_CFLAGS += -DHAVE_NEON=1
endif
#ifeq ($(TARGET_ARCH_ABI),x86)
#   LOCAL_CFLAGS += -DWORDS_BIGENDIAN=0
#endif
LOCAL_CFLAGS += -DANDROID_LOG
LOCAL_LDLIBS := -llog
#TARGET_ARCH_ABI := armeabi-v7a x86

include $(BUILD_SHARED_LIBRARY)
