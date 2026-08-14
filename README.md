# X100 Zoom Camera — V2

Experimental **video-only** Camera2 app for Vivo X100.

## Interface

V2 uses a Vivo-style video layout: full preview, 4K/60 indicator, horizontal zoom buttons, recording timer, and a large red record button. Photo/Portrait/Night modes are intentionally omitted.

## Lens policy

- **0.6×** → ultrawide physical camera
- **1× / 2×** → main physical camera
- **3× and above** → telephoto physical camera

The app opens the logical rear camera, reads its physical camera IDs, inspects their focal lengths, and maps the shortest focal length to ultrawide and the longest focal length to telephoto.

## Why 20× and 30× are different in V2

The first probe proved that the logical X100 Camera2 HAL clamps direct zoom requests to **10×**. V2 therefore does not send a 20× or 30× request to the logical camera.

Instead, when the telephoto lens is selected, V2 treats 3× as the tele lens native point:

- 3× UI = tele sensor at 1× crop
- 10× UI = tele sensor at ~3.33× crop
- 20× UI = tele sensor at ~6.67× crop
- 30× UI = tele sensor at 10× crop

That keeps the Camera2 request inside the proven 10× HAL range while testing whether physical-camera routing provides the intended telephoto field of view.

## Experimental limitation

Physical-camera output routing is OEM-dependent. If Vivo rejects a physical route, the app falls back to the logical camera rather than crashing and shows that in the on-screen status label.

Lens changes while recording currently rebuild the Camera2 session. If the physical route works on the X100, the next stage can replace that short transition with a seamless dual-sensor/GPU encoder pipeline.

Videos are saved to `Movies/X100Zoom`.
