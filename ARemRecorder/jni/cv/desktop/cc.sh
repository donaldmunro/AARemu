#g++ -c -Wall  -fpic cv.cc -I/usr/lib/jvm/java-7-jdk/include/ -I/usr/lib/jvm/java-7-jdk/include/linux/ -DDESKTOP
#LIBS=`pkg-config --libs opencv`
#g++ -shared -o libcv.so cv.o -lstdc++ $LIBS
#cp libcv.so /usr/lib/jvm/java-7-jdk/jre/lib/amd64/

rm -rf build
mkdir build
cd build
cmake ..
make
cd ..
cp lib/libcv.so /usr/lib/jvm/java-7-jdk/jre/lib/amd64/

