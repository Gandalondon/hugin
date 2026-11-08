# Hugin

A minimal Android remote for an LG OLED65C14LB (C1, webOS 6). Two volume buttons and three inputs: Live TV, Google OTT and PS5. Nothing else.

## Install on the Pixel

1. On the Pixel, open the latest release of this repo and tap `Hugin.apk`.
2. When Chrome asks, allow it to install unknown apps (Settings opens; switch on "Allow from this source", then go back).
3. Tap Install. Play Protect may warn that it does not recognise the developer: choose "Install anyway".

Later builds install over the top and keep the pairing.

## Pair with the TV (once)

1. TV: All Settings > General (or Connection) > Mobile Connection Management > LG Connect Apps: On. If the menu differs, search the TV's settings for "LG Connect Apps".
2. Phone and TV on the same Wi-Fi.
3. Open Hugin. The TV appears under "Find your TV"; tap it. If it does not appear, type the TV's IP address (TV: Settings > Network > Wi-Fi > Advanced).
4. The TV shows a prompt: choose Accept with the LG remote.

Done. The app reconnects by itself from then on.

## Power

The power button is the first of the four tiles in the top row. It stays grey; while the TV is switching it turns black with a spinner. When the TV is off, the inputs and volume dim.

Turning off works out of the box. Turning on a TV that is fully off uses Wake-on-LAN, so on the TV switch on **Turn on via Wi-Fi** (on webOS 6: All Settings > General > Devices > External Devices, or search settings for "Turn on via Wi-Fi"). Hugin learns the TV's network address the first time it connects while the TV is on.

## Inputs

The app reads the TV's own input names and matches them: "PS5" or "PlayStation" for PS5, "Google", "Chromecast" or "OTT" for Google OTT. Live TV uses the tuner. If a match is wrong or missing, open Settings and pick the right HDMI port.

## How it works

- Talks to the TV's local webOS remote interface (SSAP over WebSocket, `wss://<tv>:3001`, falling back to `ws://<tv>:3000` only before first pairing).
- Protocol details follow [aiowebostv](https://github.com/home-assistant-libs/aiowebostv), the maintained webOS library behind Home Assistant. There is no maintained Java/Kotlin library, so the small subset needed is implemented in `tv/WebOsClient.kt`.
- The pairing key is encrypted with an Android Keystore key. The TV's certificate is pinned on first pairing so nothing else on the network can pose as the TV.
- Builds run on GitHub Actions on every push to `main` and publish a signed APK as a release. The signing key is stored as the repo secrets `SIGNING_KEYSTORE_B64` and `SIGNING_PASSWORD`, never in the code, so every build is signed the same way and updates install over the top.

## More than one Wi-Fi network

Hugin remembers every address the TV has answered at, reconnects by itself when the phone changes network, and finds the same TV again without pairing.

At home the TV is on HemDAO (a Google Wifi mesh plugged into the main router, Bifrost). HemDAO is its own network, so from Bifrost's Wi-Fi the TV is hidden. To reach it from Bifrost, HemDAO forwards the remote's port to the TV: Google Home app > Wi-Fi > Settings > Advanced networking > Port management > Add > the TV > port 3001, TCP. Hugin then finds the TV through HemDAO's address on Bifrost by itself (it checks the TV's certificate, so nothing else can pose as it). Turning the TV on from Bifrost does not work; everything else does.

## Mute

Hold minus to mute: the minus turns into a muted speaker. Tap plus to unmute (it does not change the volume). Tapping minus still turns it down; holding minus no longer repeats.

