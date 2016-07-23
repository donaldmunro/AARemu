cmake_minimum_required(VERSION 3.1)

project(aremucc)
set(CMAKE_CXX_STANDARD 11)

IF (CMAKE_COMPILER_IS_GNUCXX)
   MESSAGE(STATUS "GCC Detected")
   SET(CMAKE_CXX_FLAGS "-Wall -std=c++11")
   SET(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-ggdb -O2")
   SET(CMAKE_CXX_FLAGS_RELEASE "-march=native -O2 -pipe -fstack-protector --param=ssp-buffer-size=4 -D_FORTIFY_SOURCE=2")
   #   SET(CMAKE_CXX_FLAGS_DEBUG  "-O0 -g3 -ggdb")
   SET(CMAKE_CXX_FLAGS_DEBUG  "-O0 -g3 -gdwarf-4")
   ADD_DEFINITIONS( -DISGPP )
ELSEIF(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
   MESSAGE(STATUS "Clang Detected")
   SET(CMAKE_CXX_FLAGS "-Wall -std=c++11")
   SET(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-ggdb -O2")
   SET(CMAKE_CXX_FLAGS_RELEASE "-march=native -O2 -pipe -fstack-protector --param=ssp-buffer-size=4 -D_FORTIFY_SOURCE=2")
   #   SET(CMAKE_CXX_FLAGS_DEBUG  "-O0 -g3 -ggdb")
   SET(CMAKE_CXX_FLAGS_DEBUG  "-O0 -g3 -gdwarf-4")
   ADD_DEFINITIONS( -DISCLANG )
ENDIF()

if (UNIX)
    add_definitions(-DLINUX)
endif (UNIX)

set(CMAKE_THREAD_PREFER_PTHREAD TRUE)
find_package( Threads REQUIRED )
MESSAGE(STATUS "Threads Lib: " ${CMAKE_THREAD_LIBS_INIT})
MESSAGE(STATUS "Using PTHreads?: "  ${CMAKE_USE_PTHREADS_INIT})

if (CMAKE_USE_PTHREADS_INIT)
   add_definitions(-DPTHREADS)
endif (CMAKE_USE_PTHREADS_INIT)

if(EXISTS "/etc/issue")
   file(READ "/etc/issue" ETC_OS)
else()
   set(ETC_OS "")
endif()
MESSAGE(STATUS "Issue: " "${ETC_OS}")
string(REGEX MATCH "Arch|Ubuntu" DIST ${ETC_OS})
set(USE_CUSTOM_OPENCV FALSE)
if(DIST STREQUAL "Ubuntu")
   set(CUSTOM_OPENCV_DIR "/opt/opencv-ubuntu")
   #elseif(DIST STREQUAL "Arch")
else()
   set(CUSTOM_OPENCV_DIR "/opt/opencv")
endif()

set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/build")
#set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/lib")

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

MESSAGE(STATUS "OpenCV Dir:" "${OpenCV_INSTALL_PATH}")
MESSAGE(STATUS "OpenCV Version:" "${OpenCV_VERSION_MAJOR}.${OpenCV_VERSION_MINOR}")
MESSAGE(STATUS "OpenCV include directory: " "${OpenCV_INCLUDE_DIR}")
MESSAGE(STATUS "OpenCV libs: " "${OpenCV_LIBS}")


add_executable(sample360 src/sample360.cc src/sample360.h src/Player.h src/Player.cc
src/AREmu360Player.h src/AREmu360Player.cc src/KeyBuffer.h src/readerwriterqueue.h src/util.h src/util.cpp)
TARGET_LINK_LIBRARIES(sample360 ${OpenCV_LIBS} ${CMAKE_THREAD_LIBS_INIT})
add_executable(samplefree src/samplefree.cc src/samplefree.h src/Player.h src/Player.cc
src/AREmuFreePlayer.h src/AREmuFreePlayer.cc src/readerwriterqueue.h src/Latch.h src/util.h src/util.cpp)
TARGET_LINK_LIBRARIES(samplefree ${OpenCV_LIBS} ${CMAKE_THREAD_LIBS_INIT})
INCLUDE_DIRECTORIES("${OpenCV_INCLUDE_DIR}")

#install(TARGETS features RUNTIME DESTINATION bin)
