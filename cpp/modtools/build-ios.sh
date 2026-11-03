#!/bin/sh
# Builds cpp/modtools (LOOT's sorting, libloot) as static libraries for iOS, on a Mac:
#   rustup target add aarch64-apple-ios aarch64-apple-ios-sim
#   cpp/modtools/build-ios.sh            -> cpp/modtools/target/ios/{iphoneos,iphonesimulator}/libalpha3_modtools.a
# Then, to use it instead of the "sort by masters" fallback: a cinterop for include/alpha3_modtools.h in
# composeApp/build.gradle.kts (like libarchive's), linking libalpha3_modtools.a, and Loot.ios.kt calling
# alpha3_loot_sort / alpha3_loot_free (the request and answer are the same JSON as on Android).
set -e
cd "$(dirname "$0")"
export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-15.0}"
for pair in aarch64-apple-ios:iphoneos aarch64-apple-ios-sim:iphonesimulator; do
    target=${pair%%:*}; sdk=${pair##*:}
    cargo build --release --locked --lib --target "$target"
    mkdir -p "target/ios/$sdk"
    cp "target/$target/release/libalpha3_modtools.a" "target/ios/$sdk/"
done
echo "Built target/ios/iphoneos/libalpha3_modtools.a and target/ios/iphonesimulator/libalpha3_modtools.a"
