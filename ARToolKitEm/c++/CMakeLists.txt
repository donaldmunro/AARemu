cmake_minimum_required(VERSION 3.4.1)

project(ARemuWrapper CXX)
set(CMAKE_VERBOSE_MAKEFILE ON)

set(CMAKE_CXX_STANDARD 14)
if ( ${ANDROID_ABI} STREQUAL "armeabi-v7a" )
   set(ANDROID_ARM_NEON TRUE)
   set (NEON_CFLAGS "-DHAVE_NEON=1")
else()
   set (NEON_CFLAGS "")
endif()
set(LIBS "")
#set(LIBS_DIR "../libs")
set(ARTOOLKIT_INCLUDE_DIR "${CMAKE_SOURCE_DIR}/include")
set(ARTOOLKIT_LIBS "${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libARWrapper.so")
list(APPEND LIBS "${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libARWrapper.so")
list(APPEND LIBS "${CMAKE_SOURCE_DIR}/staticlibs/${ANDROID_ABI}/libcurl.a")
set(ARTOOLKIT_STATIC_LIBS "${CMAKE_SOURCE_DIR}/staticlibs/${ANDROID_ABI}/libcurl.a")
set(SOURCE_FILES ARemController.cc ARemController.h
                 ARemVideoSource.cc ARemVideoSource.h
                 VideoSourceFactory.cc VideoSourceFactory.h
                 ARemToolKitWrapperExportedAPI.cc
                 to_augmented_reality_android_em_artoolkitem_NativeInterfaceEmu.h
   )
find_library(log-lib log)
MESSAGE(STATUS "Android ABI: " ${ANDROID_ABI})
MESSAGE(STATUS "LIBS: " ${LIBS})

#add_library( libc++_shared SHARED IMPORTED )
#set_target_properties(libc++_shared
#                      PROPERTIES IMPORTED_LOCATION
#                      "${LIBS_DIR}/${ANDROID_ABI}/libc++_shared.so")

#add_library( libARWrapper SHARED IMPORTED )
#set_target_properties( libARWrapper
#                       PROPERTIES IMPORTED_LOCATION
#                       "${LIBS_DIR}/${ANDROID_ABI}/libARWrapper.so")

#add_library( libcurl STATIC IMPORTED )
#set_target_properties( libcurl
#                       PROPERTIES IMPORTED_LOCATION
#                       "${LIBS_DIR}/${ANDROID_ABI}/libcurl.a")

add_library(ARemuWrapper SHARED ${SOURCE_FILES})
target_include_directories(ARemuWrapper PRIVATE ${ARTOOLKIT_INCLUDE_DIR})
target_link_libraries( ARemuWrapper PRIVATE ${log-lib} ${ARTOOLKIT_LIBS} ${ARTOOLKIT_STATIC_LIBS} )

install(TARGETS ARemuWrapper DESTINATION "../libs/${ANDROID_ABI}/")
