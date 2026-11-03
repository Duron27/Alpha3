#!/bin/bash
set -e
cd /home/arinmal/AndroidStudioProjects/alpha3
./gradlew :androidApp:assembleRelease -q > /home/arinmal/AndroidStudioProjects/alpha3/handoff/build-new.log 2>&1
cp androidApp/build/outputs/apk/release/androidApp-release.apk ~/Desktop/Alpha3-NEW-0ab5399-40fps-benchmark.apk
echo NEW done
git stash push -u -q -m "benchmark work (restore after old build)" -- cpp/
git checkout -q 8cbbd0a
S=cpp/openmw-upstream/support
for f in $S/src/benchmark.cpp $S/buildid.cmake cpp/openmw-upstream/tools/benchcompare.py; do git show "stash@{0}^3:$f" > $f; done
(cd $S && python3 /home/arinmal/AndroidStudioProjects/alpha3/handoff/adapt_old.py)
restore() { cd /home/arinmal/AndroidStudioProjects/alpha3; git checkout -q -- .; git clean -fdq -- $S/src/benchmark.cpp $S/buildid.cmake cpp/openmw-upstream/tools/benchcompare.py; git checkout -q main; git stash pop -q; echo restored; git status --short; }
trap restore EXIT
./gradlew :androidApp:assembleRelease -q > /home/arinmal/AndroidStudioProjects/alpha3/handoff/build-old.log 2>&1
cp androidApp/build/outputs/apk/release/androidApp-release.apk ~/Desktop/Alpha3-OLD-8cbbd0a-60fps-benchmark.apk
echo OLD done
