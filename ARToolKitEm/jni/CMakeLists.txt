cmake_minimum_required(VERSION 3.4.1)

project(ARemuWrapper)

set(CMAKE_CXX_STANDARD 11)
#set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11 -fexceptions")
set(ARTOOLKIT_DIR "/ssd/Android/ARToolKit5-bin-5.3.2-Android")
set(ARTOOLKIT_INCLUDE_DIR "/ssd/Android/ARToolKit5-bin-5.3.2-Android/include")
set(ARTOOLKIT_LIB_DIR "/ssd/Android/ARToolKit5-bin-5.3.2-Android/android/libs")
set(LIBS_DIR "${CMAKE_SOURCE_DIR}/../libs")
set(SOURCE_FILES ARemController.cc ARemController.h
                 ARemVideoSource.cc ARemVideoSource.h
                 VideoSourceFactory.cc VideoSourceFactory.h
                 ARemToolKitWrapperExportedAPI.cc to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu.h

   )
find_library(log-lib log)

add_library( libc++_shared SHARED IMPORTED )
set_target_properties(libc++_shared
                      PROPERTIES IMPORTED_LOCATION
                      "${LIBS_DIR}/${ANDROID_ABI}/libc++_shared.so")

add_library( libARWrapper SHARED IMPORTED )
set_target_properties( libARWrapper
                       PROPERTIES IMPORTED_LOCATION
                       "${LIBS_DIR}/${ANDROID_ABI}/libARWrapper.so")

add_library( libcurl STATIC IMPORTED )
set_target_properties( libcurl
                       PROPERTIES IMPORTED_LOCATION
                       "${LIBS_DIR}/${ANDROID_ABI}/libcurl.a")

add_library(ARemuWrapper SHARED ${SOURCE_FILES})
include_directories("${ARTOOLKIT_INCLUDE_DIR}")
target_link_libraries( ARemuWrapper libc++_shared  libARWrapper libcurl ${log-lib} )
install(TARGETS ARemuWrapper DESTINATION "${LIBS_DIR}/${ANDROID_ABI}/")
