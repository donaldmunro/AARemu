LIBS=`pkg-config --libs opencv`
filename=$(basename "$1")
extension="${filename##*.}"
filename="${filename%.*}"
if [ "$filename" = "$extension" ]
then
   extension="cc"
fi

clang++ -std=c++1y $filename.$extension -o $filename $LIBS
