# TV Remote

A minimal Android remote for an LG OLED65C14LB (C1, webOS 6). Two volume buttons and three inputs: Live TV, Google OTT and PS5. Nothing else.

## Install on the Pixel

1. On the Pixel, open the latest release of this repo and tap `TV-Remote.apk`.
2. When Chrome asks, allow it to install unknown apps (Settings opens; switch on "Allow from this source", then go back).
3. Tap Install. Play Protect may warn that it does not recognise the developer: choose "Install anyway".

Later builds install over the top and keep the pairing.

## Pair with the TV (once)

1. TV: All Settings > General (or Connection) > Mobile Connection Management > LG Connect Apps: On. If the menu differs, search the TV's settings for "LG Connect Apps".
2. Phone and TV on the same Wi-Fi.
3. Open TV Remote. The TV appears under "Find your TV"; tap it. If it does not appear, type the TV's IP address (TV: Settings > Network > Wi-Fi > Advanced).
4. The TV shows a prompt: choose Accept with the LG remote.

Done. The app reconnects by itself from then on.

## Power

The power button sits above the first input. Black means the TV is on, grey means off; the other controls dim while it is off.

Turning off works out of the box. Turning on a TV that is fully off uses Wake-on-LAN, so on the TV switch on **Turn on via Wi-Fi** (on webOS 6: All Settings > General > Devices > External Devices, or search settings for "Turn on via Wi-Fi"). Hugin learns the TV's network address the first time it connects while the TV is on.

## Inputs

The app reads the TV's own input names and matches them: "PS5" or "PlayStation" for PS5, "Google", "Chromecast" or "OTT" for Google OTT. Live TV uses the tuner. If a match is wrong or missing, open Settings and pick the right HDMI port.

## How it works

- Talks to the TV's local webOS remote interface (SSAP over WebSocket, `wss://<tv>:3001`, falling back to `ws://<tv>:3000` only before first pairing).
- Protocol details follow [aiowebostv](https://github.com/home-assistant-libs/aiowebostv), the maintained webOS library behind Home Assistant. There is no maintained Java/Kotlin library, so the small subset needed is implemented in `tv/WebOsClient.kt`.
- The pairing key is encrypted with an Android Keystore key. The TV's certificate is pinned on first pairing so nothing else on the network can pose as the TV.
- Builds run on GitHub Actions on every push to `main` and publish a signed APK as a release. The signing key is stored as the repo secrets `SIGNING_KEYSTORE_B64` and `SIGNING_PASSWORD`, never in the code, so every build is signed the same way and updates install over the top.
