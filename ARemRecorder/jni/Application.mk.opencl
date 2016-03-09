APP_STL := gnustl_static # GNU STL
APP_PLATFORM := android-14   # Necessary otherwise wcstombs link error
#APP_ABI := all32, all64
#mips64 gives a link error - not many, if any, mips64 devices in the wild anyway
#APP_ABI := arm64-v8a armeabi armeabi-v7a mips x86 x86_64 # mips64

APP_ABI := armeabi-v7a armeabi x86
