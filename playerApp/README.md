# Oz Projection Player

The app installed on the projector (tested target: Magcubic HY300, Android 11).

What it does:
- Plays the current show full-screen on a loop, with no controls and no sound.
- Shows a test pattern with a 6-digit PIN and its address until a show is installed.
- Serves the player API on port 8737 and advertises itself as `_ozmap._tcp`, so the
  controller app finds it on the same Wi-Fi.
- Keeps playing with Wi-Fi off once a show is installed.

Install and first run:
1. In Android Studio pick the `playerApp` run configuration and the projector as the device.
2. Open the app once. Android asks nothing else; the service starts by itself.
3. To start on boot, either press Home and choose "Oz Projection Player" as the home app
   (recommended for a dedicated projector), or allow "Display over other apps" for it in
   Settings > Apps.

Until the controller app exists, you can drive it from a PC with curl:

```
curl http://<projector-ip>:8737/info
curl -X POST http://<projector-ip>:8737/pair -H "Content-Type: application/json" -d "{\"pin\":\"123456\",\"controllerName\":\"pc\"}"
```
