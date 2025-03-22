export ANDROID_HOME=$HOME/Android/Sdk # Adjust to your liking

#PROTOBUF_VERSION=21.7
#curl -LO https://github.com/protocolbuffers/protobuf/releases/download/v$PROTOBUF_VERSION/protobuf-all-$PROTOBUF_VERSION.tar.gz
#tar xzf protobuf-all-$PROTOBUF_VERSION.tar.gz
#cd protobuf-$PROTOBUF_VERSION
#./configure --disable-shared
#make  -j 4 # You may want to pass -j to make this run faster; see make --help
#sudo make install
#cd ..

echo "skipCodegen=true" > gradle.properties

mkdir $HOME/Android
mkdir $ANDROID_HOME
mkdir $ANDROID_HOME/cmdline-tools
wget --progress=dot:mega --no-verbose https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q -n -d $ANDROID_HOME/cmdline-tools commandlinetools-*.zip
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
# Android SDK is now ready. Now accept licenses so the build can auto-download packages
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
