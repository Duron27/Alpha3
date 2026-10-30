#!/bin/bash
# Builds the iOS app for devices and uploads it to App Store Connect, where it shows up in TestFlight once Apple has
# processed it. Used by the iosTestFlight job in .gitlab-ci.yml; works the same on any Mac with Xcode.
#
# Signing is Xcode's automatic signing with an App Store Connect API key (Users and Access > Integrations > App Store
# Connect API, role Admin or App Manager): xcodebuild makes or reuses the distribution certificate (cloud managed) and
# the App Store profile itself, so nothing but the key has to be on the machine.
#
# Environment:
#   ASC_KEY_ID         API key ID
#   ASC_ISSUER_ID      issuer ID (same page)
#   ASC_KEY_P8         path to the AuthKey_<id>.p8 file, or ASC_KEY_P8_BASE64 with its contents in base64
#   APP_VERSION        CFBundleShortVersionString, e.g. 3.0.1 (default: the version in Config.xcconfig)
#   BUILD_NUMBER       CFBundleVersion, must grow with every upload (default: seconds since 2026-01-01, in minutes)
#   TEAM_ID            Apple developer team (default J7W2R8H393)
#   SKIP_UPLOAD=1      archive and export an .ipa into build/ios-release without uploading it
set -euo pipefail

repo="$(cd "$(dirname "$0")/../.." && pwd)"
out="$repo/build/ios-release"
team="${TEAM_ID:-J7W2R8H393}"

: "${ASC_KEY_ID:?set ASC_KEY_ID}"
: "${ASC_ISSUER_ID:?set ASC_ISSUER_ID}"
mkdir -p "$out"
if [ -z "${ASC_KEY_P8:-}" ]; then
    : "${ASC_KEY_P8_BASE64:?set ASC_KEY_P8 or ASC_KEY_P8_BASE64}"
    ASC_KEY_P8="$out/AuthKey_$ASC_KEY_ID.p8"
    echo "$ASC_KEY_P8_BASE64" | base64 --decode > "$ASC_KEY_P8"
    chmod 600 "$ASC_KEY_P8"
fi
auth=(-allowProvisioningUpdates
      -authenticationKeyPath "$ASC_KEY_P8"
      -authenticationKeyID "$ASC_KEY_ID"
      -authenticationKeyIssuerID "$ASC_ISSUER_ID")

version="${APP_VERSION:-$(sed -n 's/^MARKETING_VERSION=//p' "$repo/iosApp/Configuration/Config.xcconfig")}"
version="${version#v}"
build="${BUILD_NUMBER:-$(( ($(date +%s) - 1767225600) / 60 ))}"
echo "Alpha3 iOS $version ($build), team $team"

# Device libraries (vcpkg, OpenMW, frameworks into iosApp/EmbeddedLibsDevice). The Xcode build phase would start this
# too, but on its own it shows its output and fails here rather than inside xcodebuild.
cd "$repo"
./gradlew :composeApp:buildNativeIos -PnativeIosPlatform=ios --no-configuration-cache

archive="$out/Alpha3.xcarchive"
rm -rf "$archive" "$out/export"
xcodebuild archive \
    -project iosApp/iosApp.xcodeproj \
    -scheme iosApp \
    -configuration Release \
    -destination generic/platform=iOS \
    -archivePath "$archive" \
    "${auth[@]}" \
    DEVELOPMENT_TEAM="$team" \
    CODE_SIGN_STYLE=Automatic \
    MARKETING_VERSION="$version" \
    CURRENT_PROJECT_VERSION="$build"

destination=upload
[ "${SKIP_UPLOAD:-0}" = 1 ] && destination=export
cat > "$out/ExportOptions.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
 <key>method</key><string>app-store-connect</string>
 <key>destination</key><string>$destination</string>
 <key>teamID</key><string>$team</string>
 <key>signingStyle</key><string>automatic</string>
 <key>uploadSymbols</key><true/>
 <key>manageAppVersionAndBuildNumber</key><false/>
 <key>testFlightInternalTestingOnly</key><false/>
</dict></plist>
EOF

xcodebuild -exportArchive \
    -archivePath "$archive" \
    -exportPath "$out/export" \
    -exportOptionsPlist "$out/ExportOptions.plist" \
    "${auth[@]}"

if [ "$destination" = upload ]; then
    echo "Uploaded $version ($build): it appears in App Store Connect > TestFlight once Apple has processed it"
else
    echo "Exported: $(ls "$out"/export/*.ipa)"
fi
