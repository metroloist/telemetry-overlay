# Building the Android app

1. Install the latest stable Android Studio.
2. Choose **Open** and select the `TelemetryOverlay` directory.
3. Allow Android Studio to install Android SDK 35 and synchronize Gradle dependencies.
4. Connect an Android 8+ phone with USB debugging enabled.
5. Select the `app` run configuration and press **Run**, or use **Build > Build APK(s)**.
6. The debug APK is produced under `app/build/outputs/apk/debug/app-debug.apk`.

The app performs export locally. Keep it in the foreground until the export completes.

## Free cloud build with GitHub Actions

1. Create a new private GitHub repository.
2. Upload the *contents* of the `TelemetryOverlay` directory to the repository root.
3. Open the repository's **Actions** tab and choose **Build installable APK**.
4. Press **Run workflow** and wait for the green check mark.
5. Open the completed run and download the `TelemetryOverlay-APK` artifact.
6. Unzip the downloaded artifact on the phone and install `app-debug.apk`.

Android may ask permission to install apps from the browser or file manager. The generated
debug APK is signed automatically and can be installed without Google Play.
