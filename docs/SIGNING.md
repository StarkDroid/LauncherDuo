# Signing

Release builds are signed with a private keystore that is **not** in this repository
(`keystore/` and `keystore.properties` are ignored). The published APK is signed with
the maintainer's key; its certificate SHA-256 is printed in the release notes.

## Build a signed APK

1. Create a keystore (once, and back it up — losing it means a new app identity):

   ```bash
   mkdir -p keystore
   keytool -genkeypair -v \
     -keystore keystore/launcher-duo-release.jks \
     -alias launcherduo -keyalg RSA -keysize 4096 -validity 10950
   ```

2. Write `keystore.properties` in the repository root:

   ```properties
   storeFile=keystore/launcher-duo-release.jks
   storePassword=…
   keyAlias=launcherduo
   keyPassword=…
   ```

3. Build and verify:

   ```bash
   ./gradlew test :app:assembleRelease
   "$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --print-certs \
     app/build/outputs/apk/release/app-release.apk
   ```

Without `keystore.properties` the release build still assembles, but it is unsigned —
useful for CI and for anyone who wants to sign with their own key.

Anything distributed as a GitHub release should be the output of step 3, with the
`apksigner` fingerprint pasted into the release notes so it can be checked.
