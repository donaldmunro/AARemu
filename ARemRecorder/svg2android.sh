#Usage svg2android svg-file resource-name eg svg2android file.svg open.png copies png versions to res/drawable-??dpi
function usage () {
  cat <<EOS
        Resizes and copies a svg file to multiple correctly sized png files in Android res directory.
        Must be run in the directory containing the res directory.
        Usage:  svg2android svg-file-path resource-filename  
        Example: svg2android /dir/images/Gnome-document-open.svg open.png
EOS
}

function convert() {
if [[ -d "${4}" ]]
then :
else
   mkdir -p "${4}"
fi
#svg2png -w $1 -h $2 "$3" $4/$5
inkscape -w $1 -h $2 --export-png=$4/$5 $3
if [ "$?" -ne "0" ]
  then
     echo "inkscape  -w $1 -h $2 to $4/$5 failed"
     exit 1
fi
}


[[ $# -gt 0 ]] || {
  usage
  exit 1
}

resdir=`readlink -f res`
if [[ -d "${resdir}" ]]
then :
else
   echo "Must be run in the parent directory of the res directory"
   exit 1
fi
convert 36 36 "$1" res/drawable-ldpi $2
convert 48 48 "$1" res/drawable-mdpi $2
convert 72 72 "$1" res/drawable-hdpi $2
convert 96 96 "$1" res/drawable-xhdpi $2
convert 144 144 "$1" res/drawable-xxhdpi $2
