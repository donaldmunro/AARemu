#NDK="/opt/android-ndk"
NDK="/opt/android-ndk-r11/"
$NDK/ndk-build
FILES=$(find opencv/native/libs/ -name "libopencv_java3.so")
for FROM in $FILES
do
   TO=$(echo $FROM | awk -F"/" '{print "../libs/"$4"/"$5}')
   echo "cp "$FROM" "$TO
   cp "$FROM" "$TO"
done
