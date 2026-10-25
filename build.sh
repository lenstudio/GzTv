#!/bin/sh
# GzTv 构建脚本（aapt2 + javac + d8 + zipalign + apksigner，无 Gradle）
# 依赖见 README.md「前置准备」
set -e
cd "$(dirname "$0")"

SDK=${SDK:-$HOME/android/android-sdk}
BT=$SDK/build-tools/34.0.0
AJ=$SDK/platforms/android-33/android.jar
export JAVA_HOME=${JAVA_HOME:-$HOME/android/jdk17/Contents/Home}
export PATH=$JAVA_HOME/bin:$PATH

# 签名密钥：优先用脚本同目录的 release.keystore + release.keystore.pass（不入库），
# 否则回退 boot-to-tv debug.keystore；可用 KS/KSPASS/KSALIAS 环境变量覆盖
if [ -z "$KS" ]; then
    if [ -f release.keystore ] && [ -f release.keystore.pass ]; then
        KS=release.keystore
        KSPASS=$(cat release.keystore.pass)
        KSALIAS=${KSALIAS:-gztv}
    else
        KS=$HOME/android/boot-to-tv/debug.keystore
        KSPASS=android
        KSALIAS=boottotv
    fi
fi

rm -rf classes dexout unsigned.apk aligned.apk GzTv.apk
mkdir -p classes dexout

# 1. 链接生成基础 APK（无 res/ 无 assets，只有 manifest）
#    注意 --min-sdk/--target-sdk 必须显式指定（否则部分设备安装报 -26）
$BT/aapt2 link -o unsigned.apk -I $AJ --manifest AndroidManifest.xml \
    --min-sdk-version 21 --target-sdk-version 30

# 2. 编译 Java（无 support 库，纯 framework API）
$JAVA_HOME/bin/javac -source 8 -target 8 -classpath $AJ -d classes HomeActivity.java

# 3. dex 打包（min-api 21）
$JAVA_HOME/bin/java -cp $BT/lib/d8.jar com.android.tools.r8.D8 --min-api 21 --output dexout $(find classes -name '*.class')

# 4. 合包、对齐、签名
(cd dexout && zip -q -j ../unsigned.apk classes.dex)
$BT/zipalign -f 4 unsigned.apk aligned.apk
$BT/apksigner sign --ks "$KS" --ks-pass pass:$KSPASS --ks-key-alias $KSALIAS --out GzTv.apk aligned.apk

ls -la GzTv.apk
echo "done. 安装: adb install -r GzTv.apk；设为 HOME: adb shell cmd package set-home-activity com.ts.launcher/.HomeActivity"
