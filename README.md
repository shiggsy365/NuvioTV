<div align="center">

  <img src="assets/brand/app_logo_wordmark.png" alt="Nuvio" width="300" />

  <p>
    A personal Android TV-focused fork of Nuvio.
    <br />
    It keeps the upstream media experience while adding Live TV, podcasts, and more control over Continue Watching.
  </p>

  [Download this fork](https://github.com/shiggsy365/NuvioTV/releases/latest) · [Upstream NuvioTV](https://github.com/NuvioMedia/NuvioTV)

</div>

## What this fork does

This fork is built for a personal, TV-first setup. It follows upstream NuvioTV and adds a small set of opinionated features:

- **Continue Watching categories** — move titles into Soap / Reality, Comedy, Drama, or Movies in Progress. Every category keeps the normal resume, progress, next-up, and tracking behavior, and empty rows stay hidden.
- **Live TV** — play channels from a user-provided M3U playlist, with programme information from a user-provided XMLTV EPG. Live TV can be enabled or hidden from Settings.
- **Podcasts** — search for podcasts, subscribe per profile, browse episodes, resume playback, and optionally show or hide Podcasts in the main menu.
- **TV navigation refinements** — additional focus and remote-control behavior tailored to a living-room interface.
- **Fork-specific updates** — the in-app updater points to this repository's stable and beta releases instead of the upstream release feed.

All of NuvioTV's existing source, library, artwork, subtitle, playback, and tracking features remain available. This project does not include media, playlists, EPG data, or streaming sources; you provide and are responsible for the services and content you use.

## Install

Download the appropriate APK from the [latest fork release](https://github.com/shiggsy365/NuvioTV/releases/latest). Most users should choose `universal-release.apk`; architecture-specific APKs are smaller alternatives for known devices.

This fork is separate from the official Google Play version. For the upstream project and its supported distribution channels, visit [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV).

## Build from source

```bash
git clone https://github.com/shiggsy365/NuvioTV.git
cd NuvioTV
./gradlew :app:assembleFullDebug
```

NuvioTV is built with Kotlin, Jetpack Compose, TV Material 3, and Media3. Development requires Android Studio, JDK 17, and the Android SDK.

Production builds additionally require a release keystore and the `NUVIO_RELEASE_STORE_FILE`, `NUVIO_RELEASE_KEY_ALIAS`, `NUVIO_RELEASE_STORE_PASSWORD`, and `NUVIO_RELEASE_KEY_PASSWORD` environment variables.

## Upstream and contributions

This repository is a fork of [NuvioMedia/NuvioTV](https://github.com/NuvioMedia/NuvioTV). General NuvioTV bugs and improvements may belong upstream; issues specific to Live TV, podcasts, categorized Continue Watching, or this fork's releases belong here.

## License

[GNU General Public License v3.0](./LICENSE)
