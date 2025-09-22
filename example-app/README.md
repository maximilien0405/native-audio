# Native Audio Example

This Vite + React application demonstrates how to use `@capgo/native-audio` with local bundled assets and remote streaming audio.

## Getting started

1. Install dependencies:
   ```bash
   npm install
   ```
2. Run the web demo:
   ```bash
   npm run dev
   ```
3. Sync native projects (optional):
   ```bash
   npm run sync
   ```
4. Launch a native shell application:
   ```bash
   npm run ios
   # or
   npm run android
   ```

## Features

- Preload bundled and remote audio assets
- Playback controls (play, pause, resume, stop, loop)
- Volume adjustments with live updates
- Display of current playback time and duration
- Remote cache clearing for streaming assets

The example references the local plugin source via `"@capgo/native-audio": "file:.."`, so any local changes to the plugin code are picked up after reinstalling dependencies.
