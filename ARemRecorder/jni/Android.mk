LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE    := RGBAtoRGB
LOCAL_SRC_FILES := RGBAtoRGB.c
LOCAL_ARM_MODE := arm
LOCAL_LDLIBS := -llog
TARGET_ARCH_ABI := armeabi-v7a x86

include $(BUILD_SHARED_LIBRARY)
