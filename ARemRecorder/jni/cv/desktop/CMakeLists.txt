cmake_minimum_required(VERSION 3.3)
project(jni_desktop_emulator)
set(USE_CUSTOM_OPENCV TRUE)
set(CUSTOM_OPENCV_DIR "/ssd/opencv")

#set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/build")
set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/lib")

#set(OpenCV_STATIC ON)
if(USE_CUSTOM_OPENCV)
#To find OpenCV at a non system prefix (system prefix /usr/lib on Linux) eg
#when the system is still on 2 and you need/want to use 3.
    set(OpenCV_FIND_QUIETLY false)
    set (OpenCV_DIR "${CUSTOM_OPENCV_DIR}")
    find_package( OpenCV REQUIRED
                  HINTS "${CMAKE_PREFIX_PATH}${CUSTOM_OPENCV_DIR}")
    if ("${OpenCV_INCLUDE_DIR}" STREQUAL "")
        set(OpenCV_INCLUDE_DIR "${CUSTOM_OPENCV_DIR}/include/")
    endif()
else()
#Find OpenCV at system location
    find_package( OpenCV REQUIRED)
#    if ("${OpenCV_INCLUDE_DIR}" STREQUAL "")
#        set(OpenCV_INCLUDE_DIR "/usr/include/") #WARNING: Unix specific
#    endif()
endif()

MESSAGE(STATUS "OpenCV Version:" "${OpenCV_VERSION_MAJOR}.${OpenCV_VERSION_MINOR}")
MESSAGE(STATUS "OpenCV include directory: " "${OpenCV_INCLUDE_DIR}")
MESSAGE(STATUS "OpenCV libs: " "${OpenCV_LIBS}")

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11 -fpic -I/usr/lib/jvm/java-7-jdk/include/ -I/usr/lib/jvm/java-7-jdk/include/linux/ -DDESKTOP")

set(SOURCE_FILES cv.cc)

add_library(cv SHARED ${SOURCE_FILES})
if (NOT ("${OpenCV_INCLUDE_DIR}" STREQUAL ""))
    target_include_directories(cv BEFORE PUBLIC ${OpenCV_INCLUDE_DIR} )
endif()
target_link_libraries( cv ${OpenCV_LIBS} )
