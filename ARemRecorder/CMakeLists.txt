cmake_minimum_required(VERSION 3.4.1)
project(Recorder C CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_ANDROID_NDK "/opt/android-sdk/ndk-bundle")
if ( ${ANDROID_ABI} STREQUAL "armeabi-v7a" )
   set(ANDROID_ARM_NEON TRUE)
   set (NEON_CFLAGS "-DHAVE_NEON=1")
else()
   set (NEON_CFLAGS "")
endif()

set(OPENCV_HOME "/opt/opencv-android-sdk-dist") # Downloaded as zip
set(OPEN_CV_STATIC_LIBS_DIR "${OPENCV_HOME}/sdk/native/staticlibs/${ANDROID_ABI}")

set(LIBS_DIR, "${CMAKE_SOURCE_DIR}/libs")
find_library( log-lib log )

add_library( opencv SHARED IMPORTED )
set_property( TARGET opencv PROPERTY IMPORTED_LOCATION "${OPENCV_HOME}/sdk/native/libs/${ANDROID_ABI}/libopencv_java4.so")
set(OPENCV_EXTRALIBS "${OPEN_CV_STATIC_LIBS_DIR}/libopencv_stitching.a")
add_library( cv SHARED "c++/cv/cv.cc" "c++/cv/to_augmented_reality_android_em_recorder_CV.h" )
target_compile_options(cv PRIVATE ${NEON_CFLAGS} "-DANDROID_LOG")
target_include_directories(cv PUBLIC "${OPENCV_HOME}/sdk/native/jni/include/")
target_link_libraries( cv ${log-lib} opencv z atomic ${OPENCV_EXTRALIBS})

add_library( RGBAtoRGB SHARED "c++/rgba2rgb/RGBAtoRGB.c" )
target_compile_options( RGBAtoRGB PRIVATE ${NEON_CFLAGS} "-DANDROID_LOG")
target_link_libraries( RGBAtoRGB ${log-lib} )

set (FRAMBUFFER_SRC "c++/framebuffer")
add_library( framebuffer SHARED "${FRAMBUFFER_SRC}/snappy-sinksource.cc" "${FRAMBUFFER_SRC}/snappy-stubs-internal.cc"
                                 "${FRAMBUFFER_SRC}/snappy.cc" "${FRAMBUFFER_SRC}/framebuffer.cc"
                                 "${FRAMBUFFER_SRC}/framebuffer.hh")
target_compile_options(framebuffer PRIVATE ${NEON_CFLAGS} "-DANDROID_LOG")
target_link_libraries( framebuffer ${log-lib} )

#install(TARGETS cv RGBAtoRGB framebuffer DESTINATION "${LIBS_DIR}/${ANDROID_ABI}/")
