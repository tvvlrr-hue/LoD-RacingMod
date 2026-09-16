package legend.racing;

import legend.core.gpu.Bpp;
import legend.core.gte.MV;
import legend.core.renderer.MeshObj;
import legend.core.renderer.Obj;
import legend.core.renderer.QuadBuilder;
import legend.core.renderer.QueuedModelStandard;
import legend.game.Text;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static legend.core.GameEngine.PLATFORM;
import static legend.core.GameEngine.RENDERER;
import static legend.game.EngineStates.currentEngineState_8004dd04;
import static legend.game.Scus94491BpeSegment_800b.gameState_800babc8;
import static legend.game.Text.calculateAppropriateTextboxBounds;
import static legend.game.Text.clearTextbox;
import static legend.game.Text.clearTextboxText;
import static legend.game.Text.textboxes_800be358;
import static legend.game.Text.textboxText_800bdf38;
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
  }

  public static class Racer {
    public final int id;
    public final boolean isPlayer;
    public float currentSpeed;
    public float baseSpeed;
    public float boostTimer;
    public float slowTimer;
    public float pathProgress; // float index along waypoints
    public final Vector3f pos = new Vector3f();
    public final Vector3f rot = new Vector3f();
    public boolean isJumping;
    public float jumpProgress;
    public boolean jumpSucceeded;
    public int lastHurdleIndex = -1;

    public Racer(int id, boolean isPlayer, float baseSpeed) {
      this.id = id;
      this.isPlayer = isPlayer;
      this.baseSpeed = baseSpeed;
      this.currentSpeed = baseSpeed;
    }
  }

  // Active state
  private static RaceState state = RaceState.INACTIVE;
  private static int currentCut = 151;
  private static int currentLap = 1;
  private static final int TOTAL_LAPS = 3;
  private static int countdownTicks = 0;
  private static int finishTicks = 0;
  private static int feedbackTicks = 0;
  private static String jumpFeedbackText = "";
  private static boolean lastInteractPressed = false;

  // Racers
  private static final Racer playerRacer = new Racer(0, true, 4.2f);
  private static final Racer npcRacer1 = new Racer(1, false, 4.05f);
  private static final Racer npcRacer2 = new Racer(2, false, 4.15f);
  private static final Racer[] racers = new Racer[]{playerRacer, npcRacer1, npcRacer2};

  // Dart restoration
  private static final Vector3f dartSavedPos = new Vector3f(145.0f, -4.0f, -845.0f);
  private static final Vector3f dartSavedRot = new Vector3f(0.0f, 0.0f, 0.0f);

  // Sobj indices in current cut for the 3 racers
  private static int playerSobjIndex = 8;
  private static int npc1SobjIndex = 9;
  private static int npc2SobjIndex = 10;

  // Custom arrow mesh for player indicator
  private static Obj playerArrowObj = null;
  private static final MV arrowTransforms = new MV();

  // Waypoints for each cut
  private static final List<Waypoint> CUT_151_WAYPOINTS = new ArrayList<>();
  private static final List<Waypoint> CUT_149_WAYPOINTS = new ArrayList<>();
  private static final List<Waypoint> CUT_150_WAYPOINTS = new ArrayList<>();

  static {
    // Cut 151: East Balcony (running clockwise from north entry to west exit)
    CUT_151_WAYPOINTS.add(new Waypoint(535.0f, -170.0f, -240.0f, false));
    CUT_151_WAYPOINTS.add(new Waypoint(510.0f, -190.0f, -60.0f, false));
    CUT_151_WAYPOINTS.add(new Waypoint(480.0f, -214.0f, 50.0f, true));  // Hurdle 1 (Circled in red)
    CUT_151_WAYPOINTS.add(new Waypoint(430.0f, -210.0f, 180.0f, false));
    CUT_151_WAYPOINTS.add(new Waypoint(375.0f, -200.0f, 260.0f, false));
    CUT_151_WAYPOINTS.add(new Waypoint(335.0f, -188.0f, 380.0f, true));  // Hurdle 2 (Circled in red)
    CUT_151_WAYPOINTS.add(new Waypoint(270.0f, -168.0f, 490.0f, false));
    CUT_151_WAYPOINTS.add(new Waypoint(196.0f, -153.0f, 520.0f, false));
    CUT_151_WAYPOINTS.add(new Waypoint(140.0f, -145.0f, 550.0f, false));

    // Cut 149: West Balcony (entering from 151, running to north 150)
    CUT_149_WAYPOINTS.add(new Waypoint(130.0f, -205.0f, 85.0f, false));
    CUT_149_WAYPOINTS.add(new Waypoint(90.0f, -210.0f, 30.0f, false));
    CUT_149_WAYPOINTS.add(new Waypoint(-50.0f, -217.0f, -5.0f, true));   // Hurdle 3
    CUT_149_WAYPOINTS.add(new Waypoint(-160.0f, -216.0f, -65.0f, false));
    CUT_149_WAYPOINTS.add(new Waypoint(-290.0f, -215.0f, -125.0f, false));
    CUT_149_WAYPOINTS.add(new Waypoint(-375.0f, -171.0f, -175.0f, true)); // Hurdle 4
    CUT_149_WAYPOINTS.add(new Waypoint(-450.0f, -135.0f, -280.0f, false));
    CUT_149_WAYPOINTS.add(new Waypoint(-515.0f, -100.0f, -315.0f, false));

    // Cut 150: North Balcony (entering from 149, running to east 151)
    CUT_150_WAYPOINTS.add(new Waypoint(-150.0f, -144.0f, -285.0f, false));
    CUT_150_WAYPOINTS.add(new Waypoint(-80.0f, -152.0f, -275.0f, false));
    CUT_150_WAYPOINTS.add(new Waypoint(-25.0f, -158.0f, -280.0f, true));  // Hurdle 5
    CUT_150_WAYPOINTS.add(new Waypoint(40.0f, -125.0f, -315.0f, false));
    CUT_150_WAYPOINTS.add(new Waypoint(120.0f, -112.0f, -285.0f, false));
    CUT_150_WAYPOINTS.add(new Waypoint(270.0f, -118.0f, -265.0f, true));  // Hurdle 6
    CUT_150_WAYPOINTS.add(new Waypoint(410.0f, -122.0f, -250.0f, false));
    CUT_150_WAYPOINTS.add(new Waypoint(480.0f, -130.0f, -242.0f, false));
  }

  public static boolean isRaceActive() {
    return state != RaceState.INACTIVE;
  }

  public static void startRace(final SMap smap) {
    LOGGER.info("LohanRaceManager: Starting Lohan Arena Race!");
    state = RaceState.COUNTDOWN;
    currentCut = 151;
    currentLap = 1;
    countdownTicks = 90; // 3 second countdown (3, 2, 1, GO!)
    finishTicks = 0;
    feedbackTicks = 0;
    lastInteractPressed = false;

    // Reset racers
    for (int i = 0; i < racers.length; i++) {
      final Racer r = racers[i];
      r.pathProgress = i * -0.4f; // Stagger starting positions slightly
      r.currentSpeed = r.baseSpeed;
      r.boostTimer = 0;
      r.slowTimer = 0;
      r.isJumping = false;
      r.jumpProgress = 0;
      r.lastHurdleIndex = -1;
    }

    // Save Dart position and pause/hide Dart
    if (smap.sobjs_800c6880 != null && smap.sobjs_800c6880.length > 0 && smap.sobjs_800c6880[0] != null) {
      final SubmapObject210 dartSobj = smap.sobjs_800c6880[0].innerStruct_00;
      dartSavedPos.set(dartSobj.model_00.coord2_14.coord.transfer);
      dartSavedRot.set(dartSobj.model_00.coord2_14.transforms.rotate);
      dartSobj.hidden_128 = true;
      smap.sobjs_800c6880[0].pause();
    }

    assignRacerSobjs(smap);
  }

  public static void onSubmapLoad(final SMap smap, final RetailSubmap retail, final List<SubmapObject> objects) {
    if (!isRaceActive()) {
      return;
    }

    currentCut = retail.cut;
    LOGGER.info("LohanRaceManager: Loaded submap cut %d during race.", currentCut);

    // Hide Dart if present
    if (smap.sobjs_800c6880 != null && smap.sobjs_800c6880.length > 0 && smap.sobjs_800c6880[0] != null) {
      smap.sobjs_800c6880[0].innerStruct_00.hidden_128 = true;
      smap.sobjs_800c6880[0].pause();
    }

    assignRacerSobjs(smap);

    // Reset path progress to start of this scene for all racers
    for (final Racer r : racers) {
      r.pathProgress = Math.max(0.0f, r.pathProgress % 1.0f);
      r.lastHurdleIndex = -1;
      r.isJumping = false;
    }

    if (state == RaceState.LAP_TRANSITION) {
      state = RaceState.RACING;
    }
  }

  private static void assignRacerSobjs(final SMap smap) {
    // Find creature objects in current cut (usually sobjs 7..12)
    final int sobjCount = smap.sobjs_800c6880 != null ? smap.sobjs_800c6880.length : 0;
    playerSobjIndex = Math.min(8, Math.max(1, sobjCount - 3));
    npc1SobjIndex = Math.min(playerSobjIndex + 1, sobjCount - 1);
    npc2SobjIndex = Math.min(playerSobjIndex + 2, sobjCount - 1);

    // Pause retail creature scripts so our physics/control take effect
    for (int idx : new int[]{playerSobjIndex, npc1SobjIndex, npc2SobjIndex}) {
      if (idx >= 0 && idx < sobjCount && smap.sobjs_800c6880[idx] != null) {
        smap.sobjs_800c6880[idx].pause();
        smap.sobjs_800c6880[idx].innerStruct_00.hidden_128 = false;
      }
    }
  }

  public static void onRender() {
    if (!isRaceActive()) {
      return;
    }

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
  }

  private static void updateCountdown() {
    countdownTicks--;
    if (countdownTicks <= 0) {
      state = RaceState.RACING;
      LOGGER.info("LohanRaceManager: GO! Race started.");
    }
  }

  private static void updateRace() {
    if (!(currentEngineState_8004dd04 instanceof final SMap smap)) return;

    final List<Waypoint> currentWaypoints = getCurrentWaypoints();
    if (currentWaypoints.isEmpty()) return;

    // Check player input for jumping (edge-triggered)
    final boolean interactPressed = PLATFORM.isActionPressed(INPUT_ACTION_SMAP_INTERACT.get());
    final boolean actionJustPressed = interactPressed && !lastInteractPressed;
    lastInteractPressed = interactPressed;

    // Update each racer
    for (final Racer r : racers) {
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

      // Check upcoming hurdle
      final int upcomingHurdle = findUpcomingHurdle(r, currentWaypoints);
      if (r.isPlayer && upcomingHurdle != -1) {
        final float dist = hurdleDistance(r, upcomingHurdle);
        // Prompt yellow "!" alert when approaching obstacle
        setAlertIndicator(smap, playerSobjIndex, dist < 1.2f && dist > 0.05f);

        // Player jump attempt
        if (actionJustPressed && !r.isJumping && dist < 1.1f && dist > -0.2f) {
          executeJump(r, dist < 0.75f && dist > 0.15f);
        }
      } else if (!r.isPlayer && upcomingHurdle != -1 && !r.isJumping) {
        // NPC hurdle handling
        final float dist = hurdleDistance(r, upcomingHurdle);
        if (dist < 0.4f && dist > 0.1f && r.lastHurdleIndex != upcomingHurdle) {
          // NPC chance of success (NPC 1 ~80%, NPC 2 ~70%)
          final boolean success = Math.random() < (r.id == 1 ? 0.80 : 0.70);
          executeJump(r, success);
        }
      }

      // If missed hurdle and didn't jump in time
      if (upcomingHurdle != -1 && !r.isJumping && r.lastHurdleIndex != upcomingHurdle) {
        final float dist = hurdleDistance(r, upcomingHurdle);
        if (dist <= 0.05f && dist >= -0.2f) {
          executeJump(r, false); // Stumble / penalty
        }
      }

      // Update jumping arc
      if (r.isJumping) {
        r.jumpProgress += 0.07f;
        if (r.jumpProgress >= 1.0f) {
          r.isJumping = false;
          r.jumpProgress = 0.0f;
        }
      }

      // Advance path progress along track
      final float step = (r.currentSpeed / 100.0f);
      r.pathProgress += step;

      // Update 3D position & rotation from waypoints
      interpolateWaypointPosition(r, currentWaypoints);

      // Apply jump arc to Y
      if (r.isJumping) {
        final float jumpArc = (float) Math.sin(r.jumpProgress * Math.PI);
        r.pos.y -= (r.jumpSucceeded ? 35.0f : 15.0f) * jumpArc;
      }

      // Sync to Severed Chains sobj
      final int sobjIdx = r.isPlayer ? playerSobjIndex : (r.id == 1 ? npc1SobjIndex : npc2SobjIndex);
      if (smap.sobjs_800c6880 != null && sobjIdx < smap.sobjs_800c6880.length && smap.sobjs_800c6880[sobjIdx] != null) {
        final SubmapObject210 sobj = smap.sobjs_800c6880[sobjIdx].innerStruct_00;
        sobj.model_00.coord2_14.coord.transfer.set(r.pos);
        sobj.model_00.coord2_14.transforms.rotate.set(r.rot);
        sobj.animIndex_132 = r.isJumping ? (r.jumpSucceeded ? 3 : 5) : 1; // 1=run, 3=jump, 5=stumble
      }
    }

    // Check if player reached the end of the current scene's waypoints
    if (playerRacer.pathProgress >= currentWaypoints.size() - 1) {
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
        jumpFeedbackText = "PERFECT JUMP!";
        feedbackTicks = 40;
      }
    } else {
      r.slowTimer = 55; // Speed penalty / stumble
      r.boostTimer = 0;
      if (r.isPlayer) {
        jumpFeedbackText = "TOO SLOW!";
        feedbackTicks = 40;
      }
    }
  }

  private static void transitionToNextScene(final SMap smap) {
    // Scene cycle: 151 -> 149 -> 150 -> 151
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
      // Completed full circuit around arena!
      currentLap++;
      LOGGER.info("LohanRaceManager: Completed circuit! Advancing to lap %d of %d.", currentLap, TOTAL_LAPS);

      if (currentLap > TOTAL_LAPS) {
        finishRace();
        return;
      }
    }

    state = RaceState.LAP_TRANSITION;
    LOGGER.info("LohanRaceManager: Transitioning from cut %d to cut %d...", currentCut, nextCut);
    smap.mapTransition(nextCut, nextScene);
  }

  private static void finishRace() {
    state = RaceState.FINISHED;
    finishTicks = 180; // ~6 seconds of finish celebration
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
      final SubmapObject210 dart = smap.sobjs_800c6880[0].innerStruct_00;
      dart.hidden_128 = false;
      dart.model_00.coord2_14.coord.transfer.set(dartSavedPos);
      dart.model_00.coord2_14.transforms.rotate.set(dartSavedRot);
      smap.sobjs_800c6880[0].resume();
    }

    // Award reward if won 1st place!
    if (getPlayerPlacement() == 1) {
      if (gameState_800babc8 != null && gameState_800babc8.scriptData_08 != null) {
        gameState_800babc8.scriptData_08[27] = Math.min(99, gameState_800babc8.scriptData_08[27] + 3);
        LOGGER.info("LohanRaceManager: Player won 1st place! Awarded 3 tickets (total=%d).", gameState_800babc8.scriptData_08[27]);
      }
    }
  }

  private static List<Waypoint> getCurrentWaypoints() {
    return switch (currentCut) {
      case 149 -> CUT_149_WAYPOINTS;
      case 150 -> CUT_150_WAYPOINTS;
      default -> CUT_151_WAYPOINTS;
    };
  }

  private static int findUpcomingHurdle(final Racer r, final List<Waypoint> waypoints) {
    final int currentIdx = (int) Math.floor(r.pathProgress);
    for (int i = currentIdx; i < Math.min(waypoints.size(), currentIdx + 3); i++) {
      if (waypoints.get(i).isHurdle) {
        return i;
      }
    }
    return -1;
  }

  private static float hurdleDistance(final Racer r, final int hurdleIndex) {
    return (float) hurdleIndex - r.pathProgress;
  }

  private static void interpolateWaypointPosition(final Racer r, final List<Waypoint> waypoints) {
    final int idx = Math.max(0, Math.min(waypoints.size() - 2, (int) Math.floor(r.pathProgress)));
    final float t = Math.max(0.0f, Math.min(1.0f, r.pathProgress - idx));

    final Waypoint p0 = waypoints.get(idx);
    final Waypoint p1 = waypoints.get(idx + 1);

    r.pos.x = p0.x + (p1.x - p0.x) * t;
    r.pos.y = p0.y + (p1.y - p0.y) * t;
    r.pos.z = p0.z + (p1.z - p0.z) * t;

    // Face towards next point
    final float dx = p1.x - p0.x;
    final float dz = p1.z - p0.z;
    r.rot.y = (float) Math.atan2(dx, dz);
  }

  private static void setAlertIndicator(final SMap smap, final int sobjIndex, final boolean show) {
    if (smap.sobjs_800c6880 != null && sobjIndex < smap.sobjs_800c6880.length && smap.sobjs_800c6880[sobjIndex] != null) {
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
    // Build HUD text using textboxes_800be358[2] (separate from vendor textboxes 0 and 1)
    if (state == RaceState.COUNTDOWN) {
      final int secs = (countdownTicks / 30) + 1;
      final String countText = secs > 1 ? ("  READY... " + (secs - 1)) : "     GO!    ";
      openRaceHUDTextbox(countText, 140, 40, 14, 1);
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

  private static void openRaceHUDTextbox(final String text, final int x, final int y, final int chars, final int lines) {
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
