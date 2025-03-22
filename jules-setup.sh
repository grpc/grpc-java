export ANDROID_HOME=$HOME/Android/Sdk # Adjust to your liking
mkdir $HOME/Android
mkdir $ANDROID_HOME
mkdir $ANDROID_HOME/cmdline-tools
wget --progress=dot:mega --no-verbose https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -d $ANDROID_HOME/cmdline-tools commandlinetools-*.zip
mv $ANDROID_HOME/cmdline-tools/cmdline-tools $ANDROID_HOME/cmdline-tools/latest
# Android SDK is now ready. Now accept licenses so the build can auto-download packages
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
