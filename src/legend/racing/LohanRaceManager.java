package legend.racing;

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

  // Cut 151: 17 waypoints per lane (starts near booth, up ramp to balcony, exits to 149)
  // Hurdles at indices 7 and 9 (balcony logs)
  private static final Waypoint[][] CUT_151_LANES = new Waypoint[][]{
    // Lane 0 (NPC 1 - Left)
    new Waypoint[]{
      new Waypoint(123.5f, -29.2f, -975.5f),
      new Waypoint(161.8f, -25.8f, -923.2f),
      new Waypoint(215.0f, -45.8f, -838.2f),
      new Waypoint(260.8f, -59.5f, -728.8f),
      new Waypoint(295.0f, -75.2f, -581.8f),
      new Waypoint(315.0f, -99.2f, -453.8f),
      new Waypoint(317.0f, -122.0f, -326.0f),
      new Waypoint(292.2f, -153.8f, -148.2f, true), // Hurdle 1
      new Waypoint(255.0f, -153.0f, -15.2f),
      new Waypoint(204.8f, -139.0f, 110.0f, true),  // Hurdle 2
      new Waypoint(125.0f, -115.2f, 231.2f),
      new Waypoint(58.8f, -50.5f, 497.0f),
      new Waypoint(-45.0f, -13.0f, 586.0f),
      new Waypoint(-203.0f, 13.8f, 681.5f),
      new Waypoint(-297.5f, 19.8f, 723.2f),
      new Waypoint(-393.2f, 18.2f, 747.2f),
      new Waypoint(-539.8f, 15.8f, 778.0f)
    },
    // Lane 1 (Player - Center)
    new Waypoint[]{
      new Waypoint(136.0f, -33.0f, -995.5f),
      new Waypoint(181.5f, -29.5f, -935.5f),
      new Waypoint(235.8f, -48.8f, -847.8f),
      new Waypoint(281.5f, -62.0f, -734.5f),
      new Waypoint(318.2f, -79.8f, -585.2f),
      new Waypoint(336.8f, -103.0f, -455.0f),
      new Waypoint(337.2f, -125.8f, -324.8f),
      new Waypoint(313.8f, -156.5f, -144.5f, true), // Hurdle 1
      new Waypoint(274.2f, -157.8f, -6.2f),
      new Waypoint(223.8f, -142.2f, 120.2f, true),  // Hurdle 2
      new Waypoint(142.5f, -118.5f, 245.2f),
      new Waypoint(74.8f, -53.0f, 514.5f),
      new Waypoint(-31.2f, -16.5f, 603.2f),
      new Waypoint(-191.2f, 13.0f, 703.2f),
      new Waypoint(-292.2f, 19.0f, 746.5f),
      new Waypoint(-388.8f, 17.5f, 767.0f),
      new Waypoint(-535.5f, 15.2f, 796.0f)
    },
    // Lane 2 (NPC 2 - Right)
    new Waypoint[]{
      new Waypoint(156.0f, -36.8f, -1008.2f),
      new Waypoint(201.0f, -32.8f, -947.5f),
      new Waypoint(257.2f, -51.8f, -857.8f),
      new Waypoint(303.0f, -64.8f, -740.2f),
      new Waypoint(342.5f, -84.2f, -588.5f),
      new Waypoint(358.5f, -106.5f, -456.2f),
      new Waypoint(358.8f, -129.8f, -323.5f),
      new Waypoint(336.0f, -159.5f, -140.8f, true), // Hurdle 1
      new Waypoint(294.8f, -163.0f, 3.2f),
      new Waypoint(244.8f, -145.8f, 131.0f, true),  // Hurdle 2
      new Waypoint(160.0f, -121.8f, 259.0f),
      new Waypoint(90.2f, -55.8f, 531.5f),
      new Waypoint(-18.0f, -20.0f, 620.0f),
      new Waypoint(-179.0f, 12.5f, 725.5f),
      new Waypoint(-286.8f, 18.2f, 768.8f),
      new Waypoint(-384.0f, 16.8f, 787.2f),
      new Waypoint(-531.0f, 14.5f, 814.5f)
    }
  };

  // Cut 149: 8 waypoints per lane (market balcony, exits to 150)
  // Hurdles at indices 4 and 6
  private static final Waypoint[][] CUT_149_LANES = new Waypoint[][]{
    // Lane 0
    new Waypoint[]{
      new Waypoint(449.5f, -29.0f, 67.2f),
      new Waypoint(301.0f, -49.2f, 90.8f),
      new Waypoint(201.5f, -91.2f, 91.0f),
      new Waypoint(55.5f, -157.2f, 86.2f),
      new Waypoint(-105.5f, -160.5f, 18.0f, true),  // Hurdle 3
      new Waypoint(-242.0f, -163.5f, -42.5f),
      new Waypoint(-300.8f, -137.2f, -104.8f, true),// Hurdle 4
      new Waypoint(-415.5f, -75.0f, -208.5f)
    },
    // Lane 1 (Player)
    new Waypoint[]{
      new Waypoint(448.2f, -26.0f, 51.0f),
      new Waypoint(300.2f, -48.2f, 67.5f),
      new Waypoint(203.0f, -90.2f, 72.5f),
      new Waypoint(61.0f, -154.5f, 67.8f),
      new Waypoint(-97.5f, -160.5f, 0.2f, true),   // Hurdle 3
      new Waypoint(-229.5f, -163.5f, -59.2f),
      new Waypoint(-289.2f, -137.2f, -117.2f, true),// Hurdle 4
      new Waypoint(-401.8f, -75.0f, -222.8f)
    },
    // Lane 2
    new Waypoint[]{
      new Waypoint(447.2f, -22.8f, 34.5f),
      new Waypoint(292.5f, -50.0f, 34.2f),
      new Waypoint(204.2f, -89.2f, 54.2f),
      new Waypoint(66.2f, -151.5f, 48.5f),
      new Waypoint(-89.5f, -160.5f, -17.8f, true),  // Hurdle 3
      new Waypoint(-217.0f, -163.5f, -76.0f),
      new Waypoint(-276.5f, -137.2f, -130.8f, true),// Hurdle 4
      new Waypoint(-386.5f, -75.0f, -238.2f)
    }
  };

  // Cut 150: 10 waypoints per lane (residential balcony, exits back to 151)
  // Hurdles at indices 3 and 6
  private static final Waypoint[][] CUT_150_LANES = new Waypoint[][]{
    // Lane 0
    new Waypoint[]{
      new Waypoint(-259.2f, -50.0f, -20.8f),
      new Waypoint(-144.5f, -38.0f, -108.0f),
      new Waypoint(-66.2f, -61.5f, -146.2f),
      new Waypoint(26.2f, -103.8f, -200.2f, true),  // Hurdle 5
      new Waypoint(100.8f, -114.2f, -237.5f),
      new Waypoint(155.0f, -79.8f, -252.5f),
      new Waypoint(246.5f, -29.5f, -262.8f, true),  // Hurdle 6
      new Waypoint(328.0f, -33.5f, -263.0f),
      new Waypoint(395.0f, -46.0f, -243.5f),
      new Waypoint(462.0f, -93.0f, -209.0f)
    },
    // Lane 1 (Player)
    new Waypoint[]{
      new Waypoint(-275.0f, -52.0f, -27.0f),
      new Waypoint(-159.2f, -42.5f, -127.5f),
      new Waypoint(-72.2f, -63.2f, -162.0f),
      new Waypoint(20.8f, -105.8f, -216.0f, true),  // Hurdle 5
      new Waypoint(96.0f, -116.0f, -252.5f),
      new Waypoint(148.2f, -84.5f, -261.5f),
      new Waypoint(245.0f, -30.5f, -279.8f, true),  // Hurdle 6
      new Waypoint(328.5f, -34.2f, -275.0f),
      new Waypoint(399.2f, -48.0f, -261.0f),
      new Waypoint(469.8f, -96.0f, -227.0f)
    },
    // Lane 2
    new Waypoint[]{
      new Waypoint(-290.0f, -54.8f, -43.8f),
      new Waypoint(-174.2f, -44.5f, -136.5f),
      new Waypoint(-78.5f, -65.5f, -178.0f),
      new Waypoint(15.2f, -108.0f, -233.0f, true),  // Hurdle 5
      new Waypoint(91.2f, -118.2f, -267.8f),
      new Waypoint(144.0f, -86.8f, -277.2f),
      new Waypoint(243.5f, -31.2f, -296.8f, true),  // Hurdle 6
      new Waypoint(322.2f, -35.5f, -293.2f),
      new Waypoint(403.2f, -49.8f, -278.8f),
      new Waypoint(477.8f, -99.0f, -245.2f)
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
      playMenuSound(0); // Tick chime!
    } else if (countdownTicks == 0) {
      playMenuSound(1); // GO chime!
      state = RaceState.RACING;
      LOGGER.info("LohanRaceManager: GO! Race started.");
    }

    // Keep all contestants in place and camera locked onto starting line
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
        r.currentSpeed = r.baseSpeed * 1.45f;
      } else if (r.slowTimer > 0) {
        r.slowTimer--;
        r.currentSpeed = r.baseSpeed * 0.45f;
      } else {
        r.currentSpeed = r.baseSpeed;
      }

      // Hurdle detection
      final int upcomingHurdle = findUpcomingHurdle(r, waypoints);
      if (r.isPlayer && upcomingHurdle != -1) {
        final float dist = (float) upcomingHurdle - r.pathProgress;
        final boolean inApproachZone = dist < 1.4f && dist > 0.08f;

        // Sound queue when alert indicator pops up
        if (inApproachZone && !alertSoundPlayed) {
          playMenuSound(2); // Classic alert chime!
          alertSoundPlayed = true;
        } else if (!inApproachZone && alertSoundPlayed && dist <= 0.08f) {
          alertSoundPlayed = false;
        }

        // Show yellow "!" alert indicator above player creature
        setAlertIndicator(smap, PLAYER_SOBJ, inApproachZone);

        // Player jump attempt
        if (actionJustPressed && !r.isJumping && dist < 1.3f && dist > -0.2f) {
          executeJump(r, dist < 0.95f && dist > 0.20f);
        }
      } else if (!r.isPlayer && upcomingHurdle != -1 && !r.isJumping) {
        // NPC hurdle jumping logic
        final float dist = (float) upcomingHurdle - r.pathProgress;
        if (dist < 0.55f && dist > 0.15f && r.lastHurdleIndex != upcomingHurdle) {
          final boolean success = Math.random() < (r.id == 0 ? 0.82 : 0.74);
          executeJump(r, success);
        }
      }

      // Missed hurdle timeout (stumble)
      if (upcomingHurdle != -1 && !r.isJumping && r.lastHurdleIndex != upcomingHurdle) {
        final float dist = (float) upcomingHurdle - r.pathProgress;
        if (dist <= 0.08f && dist >= -0.25f) {
          executeJump(r, false); // Stumble penalty
        }
      }

      // Jumping arc update
      if (r.isJumping) {
        r.jumpProgress += 0.065f;
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
        r.pos.y -= (r.jumpSucceeded ? 35.0f : 15.0f) * jumpArc;
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
      r.slowTimer = 55; // Speed penalty / stumble
      r.boostTimer = 0;
      if (r.isPlayer) {
        playMenuSound(40); // Miss / buzzer sound!
        jumpFeedbackText = "TOO SLOW!";
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
        sobj.animIndex_132 = r.isJumping ? (r.jumpSucceeded ? 3 : 5) : 1;
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

    // Face forward along the track trajectory (+ PI for TMD model forward orientation)
    final float dx = p1.x - p0.x;
    final float dz = p1.z - p0.z;
    r.rot.y = (float) Math.atan2(dx, dz) + (float) Math.PI;
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
