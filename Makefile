ANDROID_LIB = ${HOME}/Software/ccnx/android/CCNx-Android-Lib/
JAVASRC = ${HOME}/Software/ccnx/javasrc/

all:
	ant debug

setup:
	mkdir -p libs
	ln -s ${JAVASRC}/ccn.jar libs/ccn.jar
	ln -s ${ANDROID_LIB}/bin/classes.jar libs/ccnx-android-lib.jar
	android update project --name CCNx-Android-FileProxy -p . -t android-7

clean:
	ant clean
	rm -f -r libs/ 
	rm -f  *.properties build.xml proguard.cfg
