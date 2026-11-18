#!/bin/bash
set -e
cd "$(dirname "$0")"

export JAVA_HOME=/Users/sunli/Library/Java/JavaVirtualMachines/azul-23.0.1/Contents/Home

./gradlew :app:assembleDebug

APK=app/build/outputs/apk/debug/app-debug.apk
echo
echo "打包完成: $(pwd)/$APK"
ls -lh "$APK"
