# Mimi — Teman Kecilmu

An original, mobile-friendly virtual cat game. Indonesian interface, no dependencies, no ads, and no in-app purchases. Inspired by the virtual-pet genre; not affiliated with Outfit7 or Talking Tom.

## Play

https://syahpurbachtiar-oss.github.io/game/mimi/

- Four rooms: lounge, kitchen, bathroom, bedroom.
- Feed, pet, play, bathe, brush, and sleep; needs change over time.
- Microphone talk-back: up to 4 seconds, local playback at a higher pitch. Requires HTTPS and browser permission. Text-to-speech fallback depends on available device voices.
- Two mini-games: fish catching and a 6-pair memory game, with coin rewards.
- Accessory shop, level rewards, daily gift and daily care missions.
- Automatic localStorage save, with capped offline need changes.
- Offline cache and optional home-screen installation on supported browsers.

## Implementation

`index.html`, `style.css`, and `app.js` implement the UI. `engine.js` holds independently testable game state and rewards. `pet.svg` is the original editable character source (inlined in the HTML for animation). `sw.js` is scoped to this directory and uses only Mimi-specific caches.

No build step. Serve this folder on any static HTTPS host. GitHub Pages publishes it as the `mimi/` subdirectory of the existing `game` repository, leaving the existing root game intact.

## Data and privacy

Progress is local to the current browser/device under `mimi-pet-v1`. Microphone data is neither uploaded nor persisted. All currency is fictional. No analytics, account, API key, paid service, or external asset request is used. Browser storage deletion resets progress.

Artwork and interface are original SVG/CSS. Sound effects are synthesized with Web Audio.

## Verification

Run `node --test tests/engine.test.cjs` for save recovery, feeding, sleep, offline needs, shop payments, daily rewards, and level progression. A separate DOM smoke simulation was also used for room navigation, care, both mini-games, text fallback, and persistence. Microphone permission/playback and actual device rendering require a real supported browser.
