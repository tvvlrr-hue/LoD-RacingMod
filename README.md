# The Legend of Dragoon: Racing Minigame Lohan Mod

A mod for **The Legend of Dragoon: Severed Chains** that introduces a brand-new Racing Minigame attraction to Lohan's festival/minigame grounds.

## Overview

This mod adds a fully playable creature racing minigame to the lizard race booth in Lohan (Hero Competition & Minigames area). Dart can speak to an NPC attendant at the booth to spend a ticket and enter a 3-lap race against two AI opponents on the existing Lohan race track.

### The Minigame

You race as one of the three lizard-like creatures that normally run laps around Lohan's track, now competing against two AI racers in a timed 3-lap circuit.

**The Circuit** spans three connected cuts of the Lohan map:

| Scene | Location | Obstacles |
|---|---|---|
| **Scene 1** | Cut 151 — Upper starting straight, ramp descent | None |
| **Scene 2** | Cut 150 — Water channel, wooden bridge ascent/descent | Water gap jump, 3 log hurdles |
| **Scene 3** | Cut 149 — Doorway passage catwalk | Gap jump over open doorway |

Each lap completes a full loop through all three scenes. After 3 laps the race ends, the result is displayed, and the track returns to normal.

**Controls:**
- **Cross / Jump button** — Jump over hurdles and gaps. A `!` indicator appears above the player racer when approaching a jump point.
- No other input is required — forward movement is automatic.

**Winning & Losing:**
- Final placement (1st, 2nd, or 3rd) is shown at the end.
- Prize items or consequences are not yet implemented (future work).

### NPC Attendant

- Positioned behind the race counter in Cut 151, facing Dart.
- Uses the original game's textbox and font layout:
  - Yellow header: `Racing Minigame`
  - Prompt: *"Would you like to play the Race minigame? You can play one game per ticket."*
  - Ticket count display: `Ticket remaining <count>`
  - Player choices: `No, thank you.` / `Let's try.`
- Verifies Dart has at least one minigame ticket before starting.

---

## Repository Structure

```
src/legend/racing/
  RacingMinigameMod.java    — Mod entry point and event hook registration
  LohanRaceNpc.java         — NPC spawning, interaction, dialogue state machine
  LohanRaceManager.java     — Full race logic: waypoints, AI, jumping, camera,
                              scene transitions, depth/rendering fixes, HUD

resources/
  racing_minigame_lohan/lang/en.lang   — English dialogue strings
```

---

## Building

Requires Java 25 and the Severed Chains mod SDK.

```bat
build_racing_mod.bat
```

The compiled artifact `mods/racing-minigame.jar` is placed in the `mods/` directory alongside the game executable.
