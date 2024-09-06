# Takes a .tar.gz file as a parameter, entitlements.plist file path and icns file path
set -e

file=$1
entitlements=$2
icns=$3

filename="$(basename -- $file)"
dir="$(dirname "$file")"

echo "****************************************************************"
echo "*** Signing and creating DMG for: ${file}"
echo "****************************************************************"
destination_folder_name=extracted_${filename}
echo "Extracting archive ${filename} to ${dir}/${destination_folder_name}"
mkdir ${dir}/${destination_folder_name}
tar -zxf $file --directory ${dir}/${destination_folder_name}
echo "Successfully extracted ${filename}"

# sign problematic binaries
for f in `find ${dir}/${destination_folder_name}/SpringToolSuite4.app -type f | grep -E ".*/fsevents\.node$"`
do
  echo "Signing binary file: ${f}"
  codesign --verbose --deep --force --timestamp --entitlements "${entitlements}" --options=runtime --keychain "${KEYCHAIN}" -s "${MACOS_CERTIFICATE_ID}" $f
done

function signExecutableInsideJar() {
  # sign libjansi.jnilib inside kotlin-compiler-embeddable.jar
  for f in `find ${dir}/${destination_folder_name}/SpringToolSuite4.app -type f | grep -E $1`
  do
    echo "Looking for '$2' files inside ${f} to sign..."
    f_name="$(basename -- $f)"
    extracted_jar_dir=extracted_${f_name}
    rm -rf $extracted_jar_dir
    mkdir $extracted_jar_dir
    echo "Extracting archive ${f}"
    unzip -q $f -d ./${extracted_jar_dir}
    for jnilib_file in `find $extracted_jar_dir -type f | grep -E "$3"`
    do
      echo "Signing binary file: ${jnilib_file}"
      codesign --verbose --deep --force --timestamp --entitlements "${entitlements}" --options=runtime --keychain "${KEYCHAIN}" -s "${MACOS_CERTIFICATE_ID}" $jnilib_file
    done
    cd $extracted_jar_dir
    zip -r -u ../$f .
    cd ..
    rm -rf $extracted_jar_dir

    echo "Signing binary file: ${f}"
    codesign --verbose --deep --force --timestamp --entitlements "${entitlements}" --options=runtime --keychain "${KEYCHAIN}" -s "${MACOS_CERTIFICATE_ID}" $f
  done
}

function signExecutableInsideJar2() {
  for f in `find $1 -type f | grep -E $2`
  do
    echo "Looking for '$3' files inside ${f} to sign..."
    f_name="$(basename -- $f)"
    extracted_jar_dir=extracted_${f_name}
    rm -rf $extracted_jar_dir
    mkdir $extracted_jar_dir
    echo "Extracting archive ${f}"
    unzip -q $f -d ./${extracted_jar_dir}
    for jnilib_file in `find $extracted_jar_dir -type f | grep -E "$4"`
    do
      echo "Signing binary file: ${jnilib_file}"
      codesign --verbose --deep --force --timestamp --entitlements "${entitlements}" --options=runtime --keychain "${KEYCHAIN}" -s "${MACOS_CERTIFICATE_ID}" $jnilib_file
    done
    cd $extracted_jar_dir
    zip -r -u ../$f .
    cd ..
    rm -rf $extracted_jar_dir

    echo "Signing binary file: ${f}"
    codesign --verbose --deep --force --timestamp --entitlements "${entitlements}" --options=runtime --keychain "${KEYCHAIN}" -s "${MACOS_CERTIFICATE_ID}" $f
  done
}

function signExecutableInsideNestedJar() {
  for f in `find $1 -type f | grep -E $2`
  do
    f_name="$(basename -- $f)"
    extracted_jar_dir=extracted_${f_name}
    rm -rf $extracted_jar_dir
    mkdir $extracted_jar_dir
    echo "Extracting archive ${f}"
    unzip -q $f -d ./${extracted_jar_dir}
    signExecutableInsideJar2 $extracted_jar_dir $3 $4 $5
    zip -r -u ../$f .
    cd ..
    rm -rf $extracted_jar_dir
    echo "Signing binary file: ${f}"
    codesign --verbose --deep --force --timestamp --entitlements "${entitlements}" --options=runtime --keychain "${KEYCHAIN}" -s "${MACOS_CERTIFICATE_ID}" $f
  done
}

# sign libjansi.jnilib inside kotlin-compiler-embeddable.jar
signExecutableInsideJar2 ${dir}/${destination_folder_name}/SpringToolSuite4.app ".*/kotlin-compiler-embeddable.*\.jar$" "libjansi.jnilib" ".*/libjansi\.jnilib$"
#for f in `find ${dir}/${destination_folder_name}/SpringToolSuite4.app -type f | grep -E ".*/kotlin-compiler-embeddable.*\.jar$"`
#do
#  echo "Looking for 'libjansi.jnilib' files inside ${f} to sign..."
#  f_name="$(basename -- $f)"
#  extracted_jar_dir=extracted_${f_name}
#  rm -rf $extracted_jar_dir
#  mkdir $extracted_jar_dir
#  echo "Extracting archive ${f}"
#  unzip -q $f -d ./${extracted_jar_dir}
#  for jnilib_file in `find $extracted_jar_dir -type f | grep -E ".*/libjansi\.jnilib$"`
#  do
#    echo "Signing binary file: ${jnilib_file}"
#    codesign --verbose --deep --force --timestamp --entitlements "${entitlements}" --options=runtime --keychain "${KEYCHAIN}" -s "${MACOS_CERTIFICATE_ID}" $jnilib_file
#  done
#  cd $extracted_jar_dir
#  zip -r -u ../$f .
#  cd ..
#  rm -rf $extracted_jar_dir
#
#  echo "Signing binary file: ${f}"
#  codesign --verbose --deep --force --timestamp --entitlements "${entitlements}" --options=runtime --keychain "${KEYCHAIN}" -s "${MACOS_CERTIFICATE_ID}" $f
#done

# sign libsnappyjava.jnilib and libsnappyjava.dylib inside snappy-java.jar
signExecutableInsideJar2 ${dir}/${destination_folder_name}/SpringToolSuite4.app ".*/snappy-java.*\.jar$" "libsnappyjava.jnilib" ".*/libsnappyjava\.(jni|dy)lib$"

# sign libjnidispatch.jnilib inside jna.jar
signExecutableInsideJar ".*/jna-\d+.*\.jar$" "libjnidispatch.jnilib.jnilib" ".*/libjnidispatch\.jnilib$"

#sign libjnidispatch.jnilib inside jna.jar which is inside org.springframework.ide.eclipse.docker.client.jar bundle
signExecutableInsideNestedJar ${dir}/${destination_folder_name}/SpringToolSuite4.app ".*/org.springframework.ide.eclipse.docker.client.*\.jar$" ".*/jna-\d+.*\.jar$" "libjnidispatch.jnilib" ".*/libjnidispatch\.jnilib$"

# Sign the app
ls -la ${dir}/${destination_folder_name}/SpringToolSuite4.app/
codesign --verbose --deep --force --timestamp --entitlements "${entitlements}" --options=runtime --keychain "${KEYCHAIN}" -s "${MACOS_CERTIFICATE_ID}" ${dir}/${destination_folder_name}/SpringToolSuite4.app

cd ${dir}/${destination_folder_name}
echo "Generating dmg-config.json..."
echo '{' >> dmg-config.json
echo '  "title": "Spring Tool Suite 4",' >> dmg-config.json
echo '  "icon": "'$icns'",' >> dmg-config.json
echo '  "contents": [' >> dmg-config.json
echo '    { "x": 192, "y": 100, "type": "file", "path": "./SpringToolSuite4.app" },' >> dmg-config.json
echo '    { "x": 448, "y": 100, "type": "link", "path": "/Applications" },' >> dmg-config.json
echo '    { "x": 1000, "y": 2000, "type": "file", "path": "'$icns'", "name": ".VolumeIcon.icns" }' >> dmg-config.json
echo '  ],' >> dmg-config.json
echo '  "format": "UDZO"' >> dmg-config.json
echo '}' >> dmg-config.json
cat ./dmg-config.json
dmg_filename=${filename%.*.*}.dmg
appdmg ./dmg-config.json ../${dmg_filename}

cd ..
rm -rf ./${destination_folder_name}
rm -f $filename

echo "Sign ${dmg_filename}"
codesign --verbose --deep --force --timestamp --keychain "${KEYCHAIN}" -s "${MACOS_CERTIFICATE_ID}" ./$dmg_filename