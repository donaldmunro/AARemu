#NDK_TOOLCHAIN_VERSION := clang
#NDK_TOOLCHAIN_VERSION := 4.9 
APP_STL := gnustl_static # c++_static # gnustl_static 
APP_PLATFORM := android-14   # Necessary otherwise wcstombs link error
APP_CPPFLAGS += -std=c++11
LOCAL_C_INCLUDES += ${ANDROID_NDK}/sources/cxx-stl/gnu-libstdc++/4.9/include
#APP_ABI := all32, all64
#mips64 gives a link error - not many, if any, mips64 devices in the wild anyway
APP_ABI := arm64-v8a armeabi-v7a armeabi x86 mips # x86_64 # mips64


