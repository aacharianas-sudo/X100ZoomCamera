# X100 Zoom Camera

Experimental Android Camera2 app for Vivo X100.

## Goal

Test whether the Vivo X100 Camera2 / vendor HAL accepts zoom beyond the stock Camera app's 10x video UI limit while recording 3840×2160 at 60 fps.

## Important

This is a separate test app (`com.anas.x100zoom`). It does **not** replace or modify the Vivo system Camera app.

The first build intentionally does not fake 30x with image upscaling. It reports the Camera2 zoom range and sends direct zoom requests only up to the range exposed by the device. If the HAL caps direct zoom, a later GPU post-crop path can be added.

## First test

1. Install the debug APK.
2. Grant Camera and Microphone permissions.
3. Note the values shown at the top: camera ID, 4K60 support, zoom ratio range, stabilization modes, and physical camera IDs.
4. Test preview at 1x / 5x / 10x / 20x / 30x.
5. Record a short 4K60 clip at several zoom levels.
6. Send screenshots/results back for the next X100-specific build.

Videos are stored in `Movies/X100Zoom`.
