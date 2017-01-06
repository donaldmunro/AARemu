cmake_minimum_required(VERSION 3.1)

project(ARemucc)
set(CMAKE_CXX_STANDARD 11)

FIND_PACKAGE(Boost COMPONENTS filesystem system REQUIRED)

MESSAGE(STATUS "Boost Version:" "${Boost_VERSION}")
MESSAGE(STATUS "Boost Include:" "${Boost_INCLUDE_DIRS}")
MESSAGE(STATUS "Boost Libraries:" "${Boost_FILESYSTEM_LIBRARY}" ", " "${Boost_SYSTEM_LIBRARY}")

IF (CMAKE_COMPILER_IS_GNUCXX)
   MESSAGE(STATUS "GCC Detected")
   SET(CMAKE_CXX_FLAGS "-Wall -std=c++11")
   SET(CMAKE_CXX_FLAGS_RELWITHDEBINFO "-ggdb -O2")
   SET(CMAKE_CXX_FLAGS_RELEASE "-march=native -O2 -pipe -fstack-protector --param=ssp-buffer-size=4 -D_FORTIFY_SOURCE=2")
   #   SET(CMAKE_CXX_FLAGS_DEBUG  "-O0 -g3 -ggdb")
   SET(CMAKE_CXX_FLAGS_DEBUG  "-O0 -g3 -gdwarf-4")
   ADD_DEFINITIONS( -DISGPP )
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

add_library(aremucc src/Player.cc src/AREmuFreePlayer.cc src/AREmu360Player.cc src/ImagePlayer.cc)

add_executable(aremuccex src/Sample.cc)


INCLUDE_DIRECTORIES("${OpenCV_INCLUDE_DIR}" ${Boost_INCLUDE_DIRS})
TARGET_LINK_LIBRARIES(arem ${OpenCV_LIBS} ${Boost_FILESYSTEM_LIBRARY} ${Boost_SYSTEM_LIBRARY}  ${CMAKE_THREAD_LIBS_INIT})


#install(TARGETS features RUNTIME DESTINATION bin)
