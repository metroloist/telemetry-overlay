# Telemetry Overlay for Android

Offline Android app prototype for synchronizing cycling video with GPX/FIT telemetry.

## Implemented in v1.0

- Open a local video with the Android system picker.
- Import GPX and Garmin FIT record messages without uploading data.
- Display speed, distance, altitude, accumulated ascent, heart rate and power.
- Manual telemetry offset from -60.0 to +60.0 seconds.
- Fine synchronization buttons with 0.1-second precision and coarse 1-second buttons.
- Linear interpolation between one-second sensor records for smooth preview.
- FIT files are recognized from their binary signature, so import also works when Android hides the filename extension.
- Export to MP4 with the approved two-row black telemetry panel burned into every frame.
- Original audio is preserved by Media3 Transformer.
- Export progress, cancellation, and Android system “Save as” destination picker.

## Build

Open this directory in Android Studio, install Android SDK 35 when prompted, and run the `app` configuration on an Android 8+ device. See `BUILD.md` for exact steps.

## Synchronization convention

Positive offset means telemetry begins later than the start of the video. At video time 00:20.0 and offset +12.4 s, the displayed telemetry sample is 7.6 seconds after the activity began.

## Export implementation

The exporter uses Media3 Transformer and a time-aware `CanvasOverlay`. Telemetry is
evaluated for the presentation timestamp of every output frame, including the selected
0.1-second offset. Encoding uses the device's Android MediaCodec implementation.
