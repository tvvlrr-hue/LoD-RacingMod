package legend.racing;

import legend.core.MathHelper;
import legend.core.gpu.Bpp;
import legend.core.gte.MV;
import legend.core.renderer.MeshObj;
import legend.core.renderer.Obj;
import legend.core.renderer.QuadBuilder;
import legend.core.renderer.QueuedModelStandard;
import legend.game.EngineStates;
import legend.game.Text;
import legend.game.scripting.ScriptState;
import legend.game.submap.RetailSubmap;
import legend.game.submap.SMap;
import legend.game.submap.SubmapObject;
import legend.game.submap.SubmapObject210;
import legend.game.types.BackgroundType;
import legend.game.types.LodString;
import legend.game.types.Model124;
import legend.game.types.Textbox4c;
import legend.game.types.TextboxChar08;
import legend.game.types.TextboxState;
import legend.game.types.TextboxText84;
import legend.game.types.TextboxTextState;
import legend.game.types.TextboxType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3f;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static legend.core.GameEngine.GTE;
import static legend.core.GameEngine.PLATFORM;
import static legend.core.GameEngine.RENDERER;
import static legend.game.EngineStates.currentEngineState_8004dd04;
import static legend.game.Graphics.worldToScreenMatrix_800c3548;
import static legend.game.Scus94491BpeSegment_800b.gameState_800babc8;
import static legend.game.Text.calculateAppropriateTextboxBounds;
import static legend.game.Text.clearTextbox;
import static legend.game.Text.clearTextboxText;
import static legend.game.Text.textboxes_800be358;
import static legend.game.Text.textboxText_800bdf38;
import static legend.game.sound.Audio.playMenuSound;
import static legend.lodmod.LodMod.INPUT_ACTION_SMAP_INTERACT;

public class LohanRaceManager {
  private static final Logger LOGGER = LogManager.getFormatterLogger(LohanRaceManager.class);

  public enum RaceState {
    INACTIVE,
    COUNTDOWN,
    RACING,
    LAP_TRANSITION,
    FINISHED
  }

  public static class Waypoint {
    public final float x, y, z;
    public final boolean isHurdle;

    public Waypoint(float x, float y, float z, boolean isHurdle) {
      this.x = x;
      this.y = y;
      this.z = z;
      this.isHurdle = isHurdle;
    }

    public Waypoint(float x, float y, float z) {
      this(x, y, z, false);
    }
  }

  public static class Racer {
    public final int id;
    public final boolean isPlayer;
    public final int laneIndex; // 0 = left, 1 = center, 2 = right
    public float currentSpeed;
    public float baseSpeed;
    public float boostTimer;
    public float slowTimer;
    public float pathProgress; // floating point index along waypoints
    public final Vector3f pos = new Vector3f();
    public final Vector3f rot = new Vector3f();
    public boolean isJumping;
    public float jumpProgress;
    public boolean jumpSucceeded;
    public int lastHurdleIndex = -1;

    public Racer(int id, boolean isPlayer, int laneIndex, float baseSpeed) {
      this.id = id;
      this.isPlayer = isPlayer;
      this.laneIndex = laneIndex;
      this.baseSpeed = baseSpeed;
      this.currentSpeed = baseSpeed;
    }
  }

  // Active race state
  private static RaceState state = RaceState.INACTIVE;
  private static int currentCut = 151;
  private static int currentLap = 1;
  private static final int TOTAL_LAPS = 3;
  private static int countdownTicks = 0;
  private static int finishTicks = 0;
  private static int feedbackTicks = 0;
  private static String jumpFeedbackText = "";
  private static boolean lastInteractPressed = false;
  private static boolean alertSoundPlayed = false;

  // The 3 official racing creature sobj indices in Severed Chains:
  // Submap object 8 (file 264) = NPC 1 (Lane 0 - Left)
  // Submap object 9 (file 297) = Player (Lane 1 - Center)
  // Submap object 10 (file 330) = NPC 2 (Lane 2 - Right)
  private static final int NPC1_SOBJ = 8;
  private static final int PLAYER_SOBJ = 9;
  private static final int NPC2_SOBJ = 10;

  // Racers
  private static final Racer npcRacer1 = new Racer(0, false, 0, 3.40f);
  private static final Racer playerRacer = new Racer(1, true, 1, 3.50f);
  private static final Racer npcRacer2 = new Racer(2, false, 2, 3.45f);
  private static final Racer[] racers = new Racer[]{npcRacer1, playerRacer, npcRacer2};

  // Dart restoration
  private static final Vector3f dartSavedPos = new Vector3f(145.0f, -4.0f, -845.0f);
  private static final Vector3f dartSavedRot = new Vector3f(0.0f, 0.0f, 0.0f);

  // Player arrow mesh
  private static Obj playerArrowObj = null;
  private static final MV arrowTransforms = new MV();

  // Cached reflection method for direct camera positioning
  private static Method setCameraPosMethod = null;

  // =========================================================================
  // OFFICIAL PRE-CALCULATED RETAIL TRACK LANES (FROM DRGN21.BIN COLLISION DATA)
  // =========================================================================

  // Cut 151: 9 waypoints per lane along the upper arena balcony (left to right)
  // Hurdles at indices 3 and 6 (the log obstacles circled in red)
  private static final Waypoint[][] CUT_151_LANES = new Waypoint[][]{
    // Lane 0 (NPC 1 - Left / Inner Lane)
    new Waypoint[]{
      new Waypoint(132.4f, -145.0f, 535.9f),
      new Waypoint(189.3f, -153.0f, 505.5f),
      new Waypoint(258.6f, -168.0f, 478.7f),
      new Waypoint(320.4f, -188.0f, 373.4f, true),  // Hurdle 1
      new Waypoint(360.5f, -200.0f, 253.1f),
      new Waypoint(415.7f, -210.0f, 172.8f),
      new Waypoint(464.8f, -214.0f, 44.9f, true),   // Hurdle 2
      new Waypoint(494.3f, -190.0f, -63.0f),
      new Waypoint(519.2f, -170.0f, -242.2f)
    },
    // Lane 1 (Player - Center Lane)
    new Waypoint[]{
      new Waypoint(140.0f, -145.0f, 550.0f),
      new Waypoint(196.0f, -153.0f, 520.0f),
      new Waypoint(270.0f, -168.0f, 490.0f),
      new Waypoint(335.0f, -188.0f, 380.0f, true),  // Hurdle 1
      new Waypoint(375.0f, -200.0f, 260.0f),
      new Waypoint(430.0f, -210.0f, 180.0f),
      new Waypoint(480.0f, -214.0f, 50.0f, true),   // Hurdle 2
      new Waypoint(510.0f, -190.0f, -60.0f),
      new Waypoint(535.0f, -170.0f, -240.0f)
    },
    // Lane 2 (NPC 2 - Right / Outer Lane)
    new Waypoint[]{
      new Waypoint(147.6f, -145.0f, 564.1f),
      new Waypoint(202.7f, -153.0f, 534.5f),
      new Waypoint(281.4f, -168.0f, 501.3f),
      new Waypoint(349.6f, -188.0f, 386.6f, true),  // Hurdle 1
      new Waypoint(389.5f, -200.0f, 266.9f),
      new Waypoint(444.3f, -210.0f, 187.2f),
      new Waypoint(495.2f, -214.0f, 55.1f, true),   // Hurdle 2
      new Waypoint(525.7f, -190.0f, -57.0f),
      new Waypoint(550.8f, -170.0f, -237.8f)
    }
  };

  // Cut 149: 8 waypoints per lane (market balcony, exits to 150)
  // Hurdles at indices 2 and 5
  private static final Waypoint[][] CUT_149_LANES = new Waypoint[][]{
    // Lane 0 (NPC 1 - Left)
    new Waypoint[]{
      new Waypoint(117.9f, -205.0f, 93.8f),
      new Waypoint(83.3f, -210.0f, 43.4f),
      new Waypoint(-55.3f, -217.0f, 9.0f, true),    // Hurdle 3
      new Waypoint(-166.7f, -216.0f, -51.6f),
      new Waypoint(-296.8f, -215.0f, -111.6f),
      new Waypoint(-385.4f, -171.0f, -164.2f, true),// Hurdle 4
      new Waypoint(-460.6f, -135.0f, -269.4f),
      new Waypoint(-522.1f, -100.0f, -301.8f)
    },
    // Lane 1 (Player - Center)
    new Waypoint[]{
      new Waypoint(130.0f, -205.0f, 85.0f),
      new Waypoint(90.0f, -210.0f, 30.0f),
      new Waypoint(-50.0f, -217.0f, -5.0f, true),   // Hurdle 3
      new Waypoint(-160.0f, -216.0f, -65.0f),
      new Waypoint(-290.0f, -215.0f, -125.0f),
      new Waypoint(-375.0f, -171.0f, -175.0f, true),// Hurdle 4
      new Waypoint(-450.0f, -135.0f, -280.0f),
      new Waypoint(-515.0f, -100.0f, -315.0f)
    },
    // Lane 2 (NPC 2 - Right)
    new Waypoint[]{
      new Waypoint(142.1f, -205.0f, 76.2f),
      new Waypoint(96.7f, -210.0f, 16.6f),
      new Waypoint(-44.7f, -217.0f, -19.0f, true),  // Hurdle 3
      new Waypoint(-153.3f, -216.0f, -78.4f),
      new Waypoint(-283.2f, -215.0f, -138.4f),
      new Waypoint(-364.6f, -171.0f, -185.8f, true),// Hurdle 4
      new Waypoint(-439.4f, -135.0f, -290.6f),
      new Waypoint(-507.9f, -100.0f, -328.2f)
    }
  };

  // Cut 150: 8 waypoints per lane (residential balcony, exits back to 151)
  // Hurdles at indices 2 and 5
  private static final Waypoint[][] CUT_150_LANES = new Waypoint[][]{
    // Lane 0 (NPC 1 - Left)
    new Waypoint[]{
      new Waypoint(-147.9f, -144.0f, -299.8f),
      new Waypoint(-79.4f, -152.0f, -290.0f),
      new Waypoint(-29.7f, -158.0f, -294.2f, true), // Hurdle 5
      new Waypoint(39.5f, -125.0f, -330.0f),
      new Waypoint(123.2f, -112.0f, -299.7f),
      new Waypoint(271.8f, -118.0f, -279.9f, true), // Hurdle 6
      new Waypoint(411.6f, -122.0f, -264.9f),
      new Waypoint(481.7f, -130.0f, -256.9f)
    },
    // Lane 1 (Player - Center)
    new Waypoint[]{
      new Waypoint(-150.0f, -144.0f, -285.0f),
      new Waypoint(-80.0f, -152.0f, -275.0f),
      new Waypoint(-25.0f, -158.0f, -280.0f, true),  // Hurdle 5
      new Waypoint(40.0f, -125.0f, -315.0f),
      new Waypoint(120.0f, -112.0f, -285.0f),
      new Waypoint(270.0f, -118.0f, -265.0f, true),  // Hurdle 6
      new Waypoint(410.0f, -122.0f, -250.0f),
      new Waypoint(480.0f, -130.0f, -242.0f)
    },
    // Lane 2 (NPC 2 - Right)
    new Waypoint[]{
      new Waypoint(-152.1f, -144.0f, -270.2f),
      new Waypoint(-80.6f, -152.0f, -260.0f),
      new Waypoint(-20.3f, -158.0f, -265.8f, true),  // Hurdle 5
      new Waypoint(40.5f, -125.0f, -300.0f),
      new Waypoint(116.8f, -112.0f, -270.3f),
      new Waypoint(268.2f, -118.0f, -250.1f, true),  // Hurdle 6
      new Waypoint(408.4f, -122.0f, -235.1f),
      new Waypoint(478.3f, -130.0f, -227.1f)
    }
  };

  public static boolean isRaceActive() {
    return state != RaceState.INACTIVE;
  }

  public static void startRace(final SMap smap) {
    LOGGER.info("LohanRaceManager: Starting Lohan Arena Race!");
    state = RaceState.COUNTDOWN;
    currentCut = 151;
    currentLap = 1;
    countdownTicks = 120; // 4 seconds total (3, 2, 1, GO!)
    finishTicks = 0;
    feedbackTicks = 0;
    lastInteractPressed = false;
    alertSoundPlayed = false;

    // Reset racers to starting grid (waypoint 0 of Cut 151)
    for (final Racer r : racers) {
      r.pathProgress = 0.0f;
      r.currentSpeed = r.baseSpeed;
      r.boostTimer = 0;
      r.slowTimer = 0;
      r.isJumping = false;
      r.jumpProgress = 0;
      r.lastHurdleIndex = -1;
    }

    // Save Dart position, hide Dart, attach camera to Dart and lock Dart to player racer
    if (smap.sobjs_800c6880 != null && smap.sobjs_800c6880.length > 0 && smap.sobjs_800c6880[0] != null) {
      final SubmapObject210 dartSobj = smap.sobjs_800c6880[0].innerStruct_00;
      dartSavedPos.set(dartSobj.model_00.coord2_14.coord.transfer);
      dartSavedRot.set(dartSobj.model_00.coord2_14.transforms.rotate);
      dartSobj.hidden_128 = true;
      dartSobj.cameraAttached_178 = true;
      smap.sobjs_800c6880[0].pause();
    }

    assignRacerSobjs(smap);

    // Position contestants at starting line and focus camera
    for (final Racer r : racers) {
      final Waypoint[] laneWaypoints = getLaneWaypoints(r.laneIndex);
      updateRacerPose(r, laneWaypoints);
    }
    syncRacersToSobjs(smap);
    focusCamera(smap, playerRacer.pos);
  }

  public static void onSubmapLoad(final SMap smap, final RetailSubmap retail, final List<SubmapObject> objects) {
    if (!isRaceActive()) {
      return;
    }

    currentCut = retail.cut;
    LOGGER.info("LohanRaceManager: Loaded submap cut %d during race.", currentCut);

    assignRacerSobjs(smap);

    // Reset all racers to start of this new cut
    for (final Racer r : racers) {
      r.pathProgress = 0.0f;
      r.lastHurdleIndex = -1;
      r.isJumping = false;
      r.jumpProgress = 0;
      final Waypoint[] laneWaypoints = getLaneWaypoints(r.laneIndex);
      updateRacerPose(r, laneWaypoints);
    }
    alertSoundPlayed = false;

    syncRacersToSobjs(smap);
    focusCamera(smap, playerRacer.pos);

    if (state == RaceState.LAP_TRANSITION) {
      state = RaceState.RACING;
    }
  }

  private static void assignRacerSobjs(final SMap smap) {
    final int sobjCount = smap.sobjs_800c6880 != null ? smap.sobjs_800c6880.length : 0;

    // Take complete control of the 3 creature sobjs (8, 9, 10):
    // Pause their scripts and cancel all retail interpolations
    for (int idx : new int[]{NPC1_SOBJ, PLAYER_SOBJ, NPC2_SOBJ}) {
      if (idx >= 0 && idx < sobjCount && smap.sobjs_800c6880[idx] != null) {
        final ScriptState<SubmapObject210> sstate = smap.sobjs_800c6880[idx];
        sstate.pause();

        final SubmapObject210 sobj = sstate.innerStruct_00;
        sobj.hidden_128 = false;
        sobj.finishInterpolatedMovement();
        sobj.finishInterpolatedRotationX();
        sobj.finishInterpolatedRotationY();
        sobj.finishInterpolatedRotationZ();
        sobj.interpMovementTicksTotal = 0;
        sobj.interpMovementTicks = 0;
        sobj.interpRotationTicksTotalY = 0;
        sobj.interpRotationTicksY = 0;
        sobj.rotationFrames_188 = 0;
        sobj.movementType_170 = 0;
        sobj.movementTicks_144 = 0;
      }
    }
  }

  public static void onRender() {
    if (!isRaceActive()) {
      return;
    }

    try {
      if (state == RaceState.COUNTDOWN) {
        updateCountdown();
      } else if (state == RaceState.RACING) {
        updateRace();
      } else if (state == RaceState.FINISHED) {
        updateFinished();
      }

      // Render HUD and Arrow
      renderRaceHUD();
      renderPlayerArrow();
    } catch (Throwable t) {
      LOGGER.error("LohanRaceManager onRender error", t);
    }
  }

  private static void updateCountdown() {
    countdownTicks--;

    // Audio chimes for countdown
    if (countdownTicks == 90 || countdownTicks == 60 || countdownTicks == 30) {
      playMenuSound(0); // Subtle tick chime
    } else if (countdownTicks == 0) {
      playMenuSound(1); // GO chime!
      state = RaceState.RACING;
      LOGGER.info("LohanRaceManager: GO! Race started.");
    }

    // Keep all contestants lined up at the starting line and camera locked on
    if (currentEngineState_8004dd04 instanceof final SMap smap) {
      for (final Racer r : racers) {
        final Waypoint[] laneWaypoints = getLaneWaypoints(r.laneIndex);
        updateRacerPose(r, laneWaypoints);
      }
      syncRacersToSobjs(smap);
      focusCamera(smap, playerRacer.pos);
    }
  }

  private static void updateRace() {
    if (!(currentEngineState_8004dd04 instanceof final SMap smap)) return;

    final Waypoint[] playerWaypoints = getLaneWaypoints(playerRacer.laneIndex);
    if (playerWaypoints.length < 2) return;

    // Check player input for jumping (edge-triggered)
    final boolean interactPressed = PLATFORM.isActionPressed(INPUT_ACTION_SMAP_INTERACT.get());
    final boolean actionJustPressed = interactPressed && !lastInteractPressed;
    lastInteractPressed = interactPressed;

    // Update each racer
    for (final Racer r : racers) {
      final Waypoint[] waypoints = getLaneWaypoints(r.laneIndex);

      // Speed adjustments (boost / slow)
      if (r.boostTimer > 0) {
        r.boostTimer--;
        r.currentSpeed = r.baseSpeed * 1.40f;
      } else if (r.slowTimer > 0) {
        r.slowTimer--;
        r.currentSpeed = r.baseSpeed * 0.50f;
      } else {
        r.currentSpeed = r.baseSpeed;
      }

      // Hurdle detection
      final int upcomingHurdle = findUpcomingHurdle(r, waypoints);
      if (r.isPlayer && upcomingHurdle != -1) {
        final float dist = (float) upcomingHurdle - r.pathProgress;
        final boolean inApproachZone = dist < 1.4f && dist > 0.05f && r.lastHurdleIndex != upcomingHurdle;

        // Visual ! alert above player creature (silent and clean)
        setAlertIndicator(smap, PLAYER_SOBJ, inApproachZone);

        // Player jump attempt: anytime the alert is active, pressing interact registers a successful jump!
        if (actionJustPressed && !r.isJumping && dist < 1.4f && dist >= -0.15f && r.lastHurdleIndex != upcomingHurdle) {
          r.lastHurdleIndex = upcomingHurdle;
          executeJump(r, true);
        }
      } else if (!r.isPlayer && upcomingHurdle != -1 && !r.isJumping) {
        // NPC hurdle jumping logic
        final float dist = (float) upcomingHurdle - r.pathProgress;
        if (dist < 0.60f && dist > 0.10f && r.lastHurdleIndex != upcomingHurdle) {
          r.lastHurdleIndex = upcomingHurdle;
          final boolean success = Math.random() < (r.id == 0 ? 0.82 : 0.76);
          executeJump(r, success);
        }
      }

      // Missed hurdle timeout (stumble) - only triggers if player completely missed hitting interact
      if (upcomingHurdle != -1 && !r.isJumping && r.lastHurdleIndex != upcomingHurdle) {
        final float dist = (float) upcomingHurdle - r.pathProgress;
        if (dist <= 0.05f && dist >= -0.25f) {
          r.lastHurdleIndex = upcomingHurdle;
          executeJump(r, false); // Miss penalty
        }
      }

      // Jumping arc update
      if (r.isJumping) {
        r.jumpProgress += 0.055f;
        if (r.jumpProgress >= 1.0f) {
          r.isJumping = false;
          r.jumpProgress = 0.0f;
        }
      }

      // Distance step along waypoints
      final int curWp = Math.max(0, Math.min(waypoints.length - 2, (int) Math.floor(r.pathProgress)));
      final Waypoint w0 = waypoints[curWp];
      final Waypoint w1 = waypoints[curWp + 1];
      final float segDist = Math.max(1.0f, (float) Math.hypot(w1.x - w0.x, w1.z - w0.z));
      final float step = r.currentSpeed / segDist;
      r.pathProgress += step;

      // Update 3D position and rotation
      updateRacerPose(r, waypoints);

      // Apply vertical jump arc
      if (r.isJumping) {
        final float jumpArc = (float) Math.sin(r.jumpProgress * Math.PI);
        r.pos.y -= (r.jumpSucceeded ? 22.0f : 10.0f) * jumpArc;
      }
    }

    // Sync all positions and animations to sobjs
    syncRacersToSobjs(smap);

    // Camera continuously tracks player racer
    focusCamera(smap, playerRacer.pos);

    // Scene transition when player reaches the end of current cut track
    if (playerRacer.pathProgress >= playerWaypoints.length - 1) {
      transitionToNextScene(smap);
    }
  }

  private static void executeJump(final Racer r, final boolean goodTiming) {
    r.isJumping = true;
    r.jumpProgress = 0.0f;
    r.jumpSucceeded = goodTiming;

    if (goodTiming) {
      r.boostTimer = 45; // Speed boost
      r.slowTimer = 0;
      if (r.isPlayer) {
        playMenuSound(1); // Normal LoD interaction chime!
        jumpFeedbackText = "PERFECT JUMP!";
        feedbackTicks = 40;
        if (currentEngineState_8004dd04 instanceof final SMap smap) {
          setAlertIndicator(smap, PLAYER_SOBJ, false);
        }
      }
    } else {
      r.slowTimer = 50; // Speed penalty / stumble
      r.boostTimer = 0;
      if (r.isPlayer) {
        jumpFeedbackText = "MISSED!";
        feedbackTicks = 40;
        if (currentEngineState_8004dd04 instanceof final SMap smap) {
          setAlertIndicator(smap, PLAYER_SOBJ, false);
        }
      }
    }
  }

  private static void syncRacersToSobjs(final SMap smap) {
    if (smap.sobjs_800c6880 == null) return;

    for (final Racer r : racers) {
      final int sobjIdx = r.isPlayer ? PLAYER_SOBJ : (r.id == 0 ? NPC1_SOBJ : NPC2_SOBJ);
      if (sobjIdx < smap.sobjs_800c6880.length && smap.sobjs_800c6880[sobjIdx] != null) {
        final SubmapObject210 sobj = smap.sobjs_800c6880[sobjIdx].innerStruct_00;
        sobj.finishInterpolatedMovement();
        sobj.finishInterpolatedRotationX();
        sobj.finishInterpolatedRotationY();
        sobj.finishInterpolatedRotationZ();
        sobj.interpMovementTicksTotal = 0;
        sobj.interpRotationTicksTotalY = 0;
        sobj.rotationFrames_188 = 0;
        sobj.hidden_128 = false;

        sobj.model_00.coord2_14.coord.transfer.set(r.pos);
        sobj.model_00.coord2_14.transforms.rotate.set(r.rot);
        sobj.animIndex_132 = (state == RaceState.COUNTDOWN) ? 0 : (r.isJumping ? 3 : (r.slowTimer > 0 ? 5 : 2));
      }
    }
  }

  private static void focusCamera(final SMap smap, final Vector3f targetPos) {
    // 1. Lock Dart to targetPos and ensure camera is attached to Dart
    if (smap.sobjs_800c6880 != null && smap.sobjs_800c6880.length > 0 && smap.sobjs_800c6880[0] != null) {
      final ScriptState<SubmapObject210> dartState = smap.sobjs_800c6880[0];
      final SubmapObject210 dartSobj = dartState.innerStruct_00;
      dartSobj.hidden_128 = true;
      dartSobj.cameraAttached_178 = true;
      dartSobj.model_00.coord2_14.coord.transfer.set(targetPos);
    }

    // 2. Direct camera method call via reflection with current worldToScreenMatrix
    try {
      GTE.setTransforms(worldToScreenMatrix_800c3548);
      if (setCameraPosMethod == null) {
        setCameraPosMethod = SMap.class.getDeclaredMethod("setCameraPos", int.class, Vector3f.class);
        setCameraPosMethod.setAccessible(true);
      }
      setCameraPosMethod.invoke(smap, 1, targetPos);
    } catch (Throwable ignored) {
    }
  }

  private static void transitionToNextScene(final SMap smap) {
    // Scene cycle: 151 (booth/arena) -> 149 (market) -> 150 (residential) -> 151
    int nextCut;
    int nextScene;

    if (currentCut == 151) {
      nextCut = 149;
      nextScene = 18;
    } else if (currentCut == 149) {
      nextCut = 150;
      nextScene = 60;
    } else {
      nextCut = 151;
      nextScene = 0;
      currentLap++;
      LOGGER.info("LohanRaceManager: Completed circuit! Advancing to lap %d of %d.", currentLap, TOTAL_LAPS);

      if (currentLap > TOTAL_LAPS) {
        finishRace();
        return;
      }
    }

    state = RaceState.LAP_TRANSITION;
    LOGGER.info("LohanRaceManager: Transitioning from cut %d to cut %d (scene %d)...", currentCut, nextCut, nextScene);
    smap.mapTransition(nextCut, nextScene);
  }

  private static void finishRace() {
    state = RaceState.FINISHED;
    finishTicks = 180; // ~6 seconds celebration
    playMenuSound(1);
    LOGGER.info("LohanRaceManager: Race finished! Player placement: %d", getPlayerPlacement());
  }

  private static void updateFinished() {
    finishTicks--;
    if (finishTicks <= 0) {
      returnToVendor();
    }
  }

  private static void returnToVendor() {
    state = RaceState.INACTIVE;
    if (!(currentEngineState_8004dd04 instanceof final SMap smap)) return;

    LOGGER.info("LohanRaceManager: Returning to Cut 151 vendor booth.");

    // Warp back to Cut 151 at vendor
    smap.mapTransition(151, 0);

    // Restore Dart
    if (smap.sobjs_800c6880 != null && smap.sobjs_800c6880.length > 0 && smap.sobjs_800c6880[0] != null) {
      final ScriptState<SubmapObject210> dartState = smap.sobjs_800c6880[0];
      final SubmapObject210 dart = dartState.innerStruct_00;
      dart.hidden_128 = false;
      dart.cameraAttached_178 = true;
      dart.model_00.coord2_14.coord.transfer.set(dartSavedPos);
      dart.model_00.coord2_14.transforms.rotate.set(dartSavedRot);
      dartState.resume();
      if (dartState.ticker_04 != null) {
        dartState.ticker_04.accept(dartState, dart);
      }
    }

    // Award reward if won 1st place!
    if (getPlayerPlacement() == 1) {
      if (gameState_800babc8 != null && gameState_800babc8.scriptData_08 != null) {
        gameState_800babc8.scriptData_08[27] = Math.min(99, gameState_800babc8.scriptData_08[27] + 3);
        LOGGER.info("LohanRaceManager: Player won 1st place! Awarded 3 tickets (total=%d).", gameState_800babc8.scriptData_08[27]);
      }
    }
  }

  private static Waypoint[] getLaneWaypoints(final int laneIndex) {
    final Waypoint[][] lanes = switch (currentCut) {
      case 149 -> CUT_149_LANES;
      case 150 -> CUT_150_LANES;
      default -> CUT_151_LANES;
    };
    final int safeLane = Math.max(0, Math.min(lanes.length - 1, laneIndex));
    return lanes[safeLane];
  }

  private static int findUpcomingHurdle(final Racer r, final Waypoint[] waypoints) {
    if (waypoints.length == 0) return -1;
    final int currentIdx = Math.max(0, (int) Math.floor(r.pathProgress));
    for (int i = currentIdx; i < Math.min(waypoints.length, currentIdx + 3); i++) {
      if (waypoints[i].isHurdle) {
        return i;
      }
    }
    return -1;
  }

  private static void updateRacerPose(final Racer r, final Waypoint[] waypoints) {
    if (waypoints.length < 2) return;
    final int idx = Math.max(0, Math.min(waypoints.length - 2, (int) Math.floor(r.pathProgress)));
    final float t = Math.max(0.0f, Math.min(1.0f, r.pathProgress - idx));

    final Waypoint p0 = waypoints[idx];
    final Waypoint p1 = waypoints[idx + 1];

    r.pos.x = p0.x + (p1.x - p0.x) * t;
    r.pos.y = p0.y + (p1.y - p0.y) * t;
    r.pos.z = p0.z + (p1.z - p0.z) * t;

    // Face forward along the track trajectory using Severed Chains standard
    final float dx = p1.x - p0.x;
    final float dz = p1.z - p0.z;
    r.rot.y = MathHelper.positiveAtan2(dz, dx);
  }

  private static void setAlertIndicator(final SMap smap, final int sobjIndex, final boolean show) {
    if (smap != null && smap.sobjs_800c6880 != null && sobjIndex < smap.sobjs_800c6880.length && smap.sobjs_800c6880[sobjIndex] != null) {
      final SubmapObject210 sobj = smap.sobjs_800c6880[sobjIndex].innerStruct_00;
      sobj.showAlertIndicator_194 = show;
      sobj.alertIndicatorOffsetY_198 = -45;
    }
  }

  public static int getPlayerPlacement() {
    int placement = 1;
    if (npcRacer1.pathProgress > playerRacer.pathProgress) placement++;
    if (npcRacer2.pathProgress > playerRacer.pathProgress) placement++;
    return placement;
  }

  private static void renderPlayerArrow() {
    // Render Dart's blue indicator arrow hovering directly above the player's creature
    if (playerArrowObj == null) {
      playerArrowObj = new QuadBuilder("RacePlayerArrow")
        .vramPos(960, 256)
        .bpp(Bpp.BITS_4)
        .clut(976, 464)
        .uv(0, 0)
        .size(18, 18)
        .uvSize(16, 16)
        .build();
    }

    if (!(currentEngineState_8004dd04 instanceof SMap)) return;

    arrowTransforms.transfer.set(playerRacer.pos.x, playerRacer.pos.y - 48.0f, playerRacer.pos.z);
    RENDERER.queueOrthoModel(playerArrowObj, arrowTransforms, QueuedModelStandard.class)
      .colour(0.2f, 0.7f, 1.0f); // Bright blue / cyan arrow
  }

  private static void renderRaceHUD() {
    if (state == RaceState.COUNTDOWN) {
      final int count = (countdownTicks / 30);
      final String countText = count >= 3 ? "  READY... 3  " : (count == 2 ? "  READY... 2  " : (count == 1 ? "  READY... 1  " : "     GO!    "));
      openRaceHUDTextbox(countText, 135, 40, 16, 1);
    } else if (state == RaceState.RACING) {
      final int placement = getPlayerPlacement();
      final String placeStr = placement == 1 ? "1st" : (placement == 2 ? "2nd" : "3rd");
      final String lapStr = currentLap >= TOTAL_LAPS ? "FINAL LAP!" : ("LAP " + currentLap + "/" + TOTAL_LAPS);

      String hudText = lapStr + " | " + placeStr;
      if (feedbackTicks > 0) {
        feedbackTicks--;
        hudText += "\n" + jumpFeedbackText;
      }
      openRaceHUDTextbox(hudText, 120, 20, 18, feedbackTicks > 0 ? 2 : 1);
    } else if (state == RaceState.FINISHED) {
      final int finalPlacement = getPlayerPlacement();
      final String result = finalPlacement == 1 ? "★ 1st PLACE! VICTORY! ★\n+3 TICKETS WON!" : ("FINISH! " + finalPlacement + (finalPlacement == 2 ? "nd" : "rd") + " PLACE\nBETTER LUCK NEXT TIME!");
      openRaceHUDTextbox(result, 100, 40, 24, 2);
    } else {
      safelyClearHUDTextbox();
    }
  }

  private static String lastHudText = "";

  private static void openRaceHUDTextbox(final String text, final int x, final int y, final int chars, final int lines) {
    if (text.equals(lastHudText)) {
      return;
    }
    lastHudText = text;
    safelyClearHUDTextbox();

    final Textbox4c textbox = textboxes_800be358[2];
    final TextboxText84 textboxText = textboxText_800bdf38[2];

    textbox.backgroundType_04 = BackgroundType.NORMAL;
    textbox.renderBorder_06 = true;
    textbox.flags_08 = Textbox4c.RENDER_BACKGROUND | Textbox4c.NO_ANIMATE_OUT;
    textbox.state_00 = TextboxState._6;
    textbox.x_14 = x;
    textbox.y_16 = y;
    textbox.chars_18 = chars + 1;
    textbox.lines_1a = lines + 1;
    textbox.width_1c = textbox.chars_18 * 9 / 2;
    textbox.height_1e = textbox.lines_1a * 6;
    textbox.oldW = 0;
    textbox.oldH = 0;

    textboxText.type_04 = TextboxType.SIMPLE.id;
    textboxText.flags_08 = TextboxText84.NO_INPUT;
    textboxText.str_24 = new LodString(text);
    textboxText.chars_1c = textbox.chars_18 - 1;
    textboxText.lines_1e = lines;
    textboxText.chars_58 = new TextboxChar08[textboxText.chars_1c * (textboxText.lines_1e + 1)];
    Arrays.setAll(textboxText.chars_58, i -> new TextboxChar08());
    textboxText.charIndex_30 = 0;
    textboxText.charX_34 = 0;
    textboxText.charY_36 = 0;
    textboxText.state_00 = TextboxTextState.PROCESS_TEXT_4;

    calculateAppropriateTextboxBounds(2, x, y);

    while (textboxText.charIndex_30 < textboxText.str_24.length()
      && textboxText.state_00 != TextboxTextState.CLOSE_TEXTBOX_15) {
      Text.processTextboxCharacter(2);
    }
    textboxText.state_00 = TextboxTextState.CLOSE_BATTLE_NO_INPUT_16;
  }

  private static void safelyClearHUDTextbox() {
    try {
      lastHudText = "";
      clearTextbox(2);
      clearTextboxText(2);

      final TextboxText84 tbText = textboxText_800bdf38[2];
      tbText.state_00 = TextboxTextState.UNINITIALIZED_0;
      tbText.chars_58 = null;

      final Textbox4c tb = textboxes_800be358[2];
      tb.state_00 = TextboxState.UNINITIALIZED_0;
      tb.flags_08 = 0;
      tb.width_1c = 0;
      tb.height_1e = 0;
    } catch (Exception ignored) {
    }
  }
}
