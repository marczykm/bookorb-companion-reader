# BookOrbit: Unofficial companion app

An Android app for reading books from your own self-hosted [BookOrbit](https://github.com/bookorbit/bookorbit) server.

> **This is a modified, unofficial version and is not an official BookOrbit release.** It is not approved, sponsored or endorsed by the BookOrbit project. First published 2026-11-01.
>
> Powered by [BookOrbit](https://github.com/bookorbit/bookorbit). Copyright (C) 2025-2026 neon. Additional copyright is held by the respective BookOrbit contributors.

It looks and works like the BookOrbit KOReader plugin, as an Android reader. It is built with Kotlin and Jetpack Compose, and reads EPUBs with the [Readium](https://readium.org/) toolkit.

## Features

- **Sign in** to your server with its native token login. A server address typed without `http://` is tried over HTTPS, then HTTP.
- **Home**: a continue-reading card, shelves, stat widgets, and a pixel-art reading companion (15 to choose from) that reacts to your reading. Each item can be hidden, reordered and resized.
- **Library**: filter by library and read status, search, grid or list, set status and rating, and download books for offline reading.
- **Browse** series and authors.
- **Reader**
  - Font size, margins, themes (light, sepia, dark) and optional justified text.
  - A top bar with the chapter, the clock and the battery, and a bottom bar with book progress marked with chapters and an estimate of the minutes left in the chapter, learned from your own reading speed.
  - Volume keys turn pages; tapping the screen edges does too.
  - **Highlights** sync both ways with the web reader (EPUB CFI positions).
  - Progress and reading time sync to the server.
- **Works offline**: saved data and downloaded books keep working with no connection. Progress, highlights, status and rating are queued and sent in the background when the server is reachable.

## Building

You need JDK 17 and the Android SDK (platform 35).

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Create a `local.properties` with `sdk.dir=/path/to/android-sdk` if your SDK isn't found automatically.

## Testing without a server

`tools/mock_server.py` is a small stand-in for the BookOrbit API with a sample EPUB, enough to try sign-in, Home, the Library and the reader. Run it with `python3 tools/mock_server.py` and sign in from an emulator at `10.0.2.2:3000` with `demo` / `demo`. Requesting `/__offline/1` makes it behave like an unreachable server.

## Limits

- It uses BookOrbit's REST API, which isn't publicly documented. The endpoints were taken from the server's source and may change.
- Only EPUB is supported, not PDF, comics or audiobooks.
- Highlights from the web reader are placed by finding their text in the chapter, so an identical repeated sentence could land on the wrong occurrence.
- Some BookOrbit plugin features are not here, such as bookmarks and the plugin's own settings pages.

## License

AGPL-3.0-only, with the additional terms in [ADDITIONAL_TERMS.md](ADDITIONAL_TERMS.md) that apply to the BookOrbit material this project includes. See [LICENSE](LICENSE) and [NOTICE](NOTICE), which also credits foliate-js and the other components used. The in-app **About** dialog (the ⓘ button on Home) shows the required attribution.

"BookOrbit" is the name of another project. The additional terms ask that a modified version not use it as its primary name or branding without permission, so if you fork this, give your fork its own name.
