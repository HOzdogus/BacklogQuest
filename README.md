# Backlog Quest for Android

A native Android version of Backlog Quest: your backlog, the device-then-game picker with the two-for-two rotation rule, XP and levels, trophies and stats.

## How it gets your data

The app downloads `backlog-data.json` from this repo (the root of the `main` branch). That file holds your imported HowLongToBeat history, your wishlist, the unmarked catalog, what's on your drives, and your games list as it was in the web version.

- On a fresh install, the app copies your games and devices from the file.
- After that, your games list lives on your phone. Tap **Setup → Sync now** to refresh history, wishlist and catalog whenever the file changes.
- Updating `backlog-data.json` doesn't start a new app build.

## Auto-fill

Auto-fill uses RAWG, a free game database. Get a free API key at rawg.io/apidocs and paste it into **Setup → Auto-fill**.

## Building

GitHub Actions builds the APK on every push and publishes it on the Releases page.
