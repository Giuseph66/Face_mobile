export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64

./gradlew --offline :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

adb_cmd=/home/jesus/Android/Sdk/platform-tools/adb

"$adb_cmd" -s RXCW50039TX install -r --user 0 app/build/outputs/apk/debug/app-debug.apk

"$adb_cmd" -s RXCW50039TX shell am start --user 0 \
  -n br.com.sonolencia.faceencoder/.MainActivity