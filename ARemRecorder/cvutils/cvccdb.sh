LIBS=`pkg-config --libs opencv`
filename=$(basename "$1")
extension="${filename##*.}"
filename="${filename%.*}"
if [ "$filename" = "$extension" ]
then
   extension="cc"
fi

g++ -g -Wall -std=c++11 $filename.$extension -o $filename $LIBS
