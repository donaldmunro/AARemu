cmake_minimum_required(VERSION 3.3)
project(CVUtils)
set(USE_CUSTOM_OPENCV TRUE)
set(CUSTOM_OPENCV_DIR "/opt/opencv")

set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/build")
#set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}/lib")

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

set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -std=c++11")

set(SOURCE_FILES postprocess.cc)

add_executable(disp disp.cc)
add_executable(dispframe dispframe.cc)
add_executable(dispframes dispframes.cc)
add_executable(dispframes2 dispframes2.cc)
add_executable(eval eval.cc)
add_executable(filediff filediff.cc)
add_executable(fourier_shift fourier_shift.cc)
add_executable(framediff framediff.cc)
add_executable(imgsim imgsim.cc)
add_executable(templ templ.cc)

#if (NOT ("${OpenCV_INCLUDE_DIR}" STREQUAL ""))
#    target_include_directories(postprocess BEFORE PUBLIC ${OpenCV_INCLUDE_DIR} )
#endif()
target_link_libraries(disp ${OpenCV_LIBS} )
target_link_libraries(dispframe ${OpenCV_LIBS} )
target_link_libraries(dispframes ${OpenCV_LIBS} )
target_link_libraries(dispframes2 ${OpenCV_LIBS} )
target_link_libraries(eval ${OpenCV_LIBS} )
target_link_libraries(filediff ${OpenCV_LIBS} )
target_link_libraries(fourier_shift ${OpenCV_LIBS} )
target_link_libraries(framediff ${OpenCV_LIBS} )
target_link_libraries(imgsim ${OpenCV_LIBS} )
target_link_libraries(templ ${OpenCV_LIBS} )
