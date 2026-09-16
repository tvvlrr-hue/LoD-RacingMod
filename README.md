# The Legend of Dragoon: Racing Minigame Lohan Mod

A mod for **The Legend of Dragoon: Severed Chains** that introduces a brand-new Racing Minigame attraction to Lohan's festival/minigame grounds.

## Overview
This mod adds an NPC attendant at the lizard race booth in Lohan (Hero Competition & Minigames area, Cut 151).
- **Interactive NPC**: Positioned behind the race counter facing Dart.
- **Alert Indicator**: Displays the native `!` overhead alert bubble when Dart approaches the booth.
- **Authentic Dialogue**: Uses the original game textbox and font layout:
  - Yellow Header (`Racing Minigame`)
  - Ticket query: *"Would you like to play the Race minigame? You can play one game per ticket."*
  - Dynamic ticket count display: `Ticket remaining <count>`
  - Interactive player choices: `No, thank you.` and `Let's try.`
- **Ticket Checking**: Verifies Dart has minigame tickets before starting.

## Repository Structure
- `src/legend/racing/`: Mod source code.
  - `RacingMinigameMod.java`: Mod entry point and event registration.
  - `LohanRaceNpc.java`: NPC entity spawning, interaction collision, state machine, and textbox management.
- `resources/`: Mod assets and localization files.
  - `racing_minigame_lohan/lang/en.lang`: English dialogue text.

## Building
Compile with Java 25 against Severed Chains dependencies (`build_racing_mod.bat`).
The compiled artifact `racing-minigame.jar` is placed in the `mods/` directory.
