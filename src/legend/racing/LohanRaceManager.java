package legend.racing;

import legend.core.MathHelper;
import legend.core.gte.MV;
import legend.game.EngineStates;
import legend.game.Text;
import legend.game.scripting.ScriptState;
import legend.game.modding.coremod.CoreMod;
import legend.game.submap.IndicatorMode;
import legend.game.submap.RetailSubmap;
import legend.game.submap.SMap;
import legend.game.submap.SubmapObject;
import legend.game.submap.SubmapObject210;
import legend.game.submap.TriangleIndicator140;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static legend.core.GameEngine.CONFIG;
import static legend.core.GameEngine.GTE;
import static legend.core.GameEngine.PLATFORM;
import static legend.game.EngineStates.currentEngineState_8004dd04;
import static legend.game.FullScreenEffects.startFadeEffect;
import static legend.game.Graphics.GsGetLs;
import static legend.game.Graphics.PushMatrix;
import static legend.game.Graphics.PopMatrix;
import static legend.game.Graphics.worldToScreenMatrix_800c3548;
import static legend.game.Models.loadModelStandardAnimation;
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
    START_FADING_OUT,
    COUNTDOWN,
    RACING,
    LAP_TRANSITION,
    FINISH_IDLE,
    FINISH_FADING_OUT
  }

  public static class Waypoint {
    public final float x, y, z;
    public final boolean isHurdle;
    public final boolean isFinishLine;

    public Waypoint(float x, float y, float z, boolean isHurdle, boolean isFinishLine) {
      this.x = x;
      this.y = y;
      this.z = z;
      this.isHurdle = isHurdle;
      this.isFinishLine = isFinishLine;
    }

    public Waypoint(float x, float y, float z, boolean isHurdle) {
      this(x, y, z, isHurdle, false);
    }

    public Waypoint(float x, float y, float z) {
      this(x, y, z, false, false);
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
    public float sceneEntryProgress; // progress gap persisted across scenes
    public final Vector3f pos = new Vector3f();
    public final Vector3f rot = new Vector3f();
    public float groundY;
    public boolean isJumping;
    public boolean jumpQueued;
    public float jumpProgress;
    public boolean jumpSucceeded;
    public int lastHurdleIndex = -1;
    public int currentAnimIndex = -1;
    public float jumpArcHeight = 22.0f;
    public float jumpSpeed = 0.075f;
    public float jumpStartProgress = 0.0f;
    public float jumpEndProgress = 1.0f;

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
  private static int startFadeTicks = 0;
  private static int countdownTicks = 0;
  private static int finishTicks = 0;
  private static int finishFadeTicks = 0;
  private static int feedbackTicks = 0;
  private static String jumpFeedbackText = "";
  private static boolean lastInteractPressed = false;
  private static boolean alertSoundPlayed = false;
  private static int lastAlertHurdleIndex = -1;
  private static boolean pendingDartRestore = false;
  private static boolean isFirstPass = true;
  private static boolean lapCountedThisPass = false;
  private static IndicatorMode savedIndicatorMode = null;

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

  // Cached reflection methods and fields
  private static Method setCameraPosMethod = null;
  private static Method renderTriangleIndicatorsMethod = null;
  private static Field triangleIndicatorsField = null;
  private static Field inputPressedField = null;
  private static Field inputRepeatField = null;
  private static Field inputHeldField = null;

  // =========================================================================
  // TRACK WAYPOINTS FROM DRGN21.BIN COLLISION VERTEX DATA
  // Track flows: Cut 151 (top-right) -> Cut 149 (bottom) -> Cut 150 (top-left) -> Cut 151
  // =========================================================================

  // =========================================================================
  // TRACK WAYPOINTS FROM DRGN21.BIN COLLISION VERTEX DATA
  // Track flows:
  // Scene 1: Cut 151 (Initial start mid-track, after logs -> run to exit, NO hurdles)
  // Scene 2: Cut 150 (Bottom scene: right -> water jump -> left exit)
  // Scene 3: Cut 149 (Doorway scene: left -> doorway gap jump -> log stacks jump -> right exit)
  // Scene 4: Cut 151 (Full track: top-left -> log 1 jump -> log 2 jump -> lap count at start line -> exit)
  // =========================================================================

  // Scene 1: Cut 151 (Initial Start) - Starts directly at the GREEN LINE banner.
  // Racers line up side-by-side at the green line banner and sprint down the ramp into Cut 150. NO hurdles.
  private static final Waypoint[][] CUT_151_START_LANES = new Waypoint[][]{
    // Lane 0 (NPC 1 - Inner Lane)
    new Waypoint[]{
      new Waypoint( 383.3f,  -79.3f,  -740.0f),  // Green Line Banner
      new Waypoint( 324.0f,  -61.0f,  -885.3f),  // Ramp lower mid
      new Waypoint( 252.8f,  -35.0f, -1000.3f),  // Ramp lower
      new Waypoint( 193.0f,  -38.5f, -1080.8f),  // Ramp foot
      new Waypoint( 163.0f,  -40.0f, -1120.0f)   // Exit bottom into Cut 150
    },
    // Lane 1 (Player - Center Lane)
    new Waypoint[]{
      new Waypoint( 425.5f,  -84.6f,  -751.6f),  // Green Line Banner
      new Waypoint( 366.0f,  -66.9f,  -904.5f),  // Ramp lower mid
      new Waypoint( 292.0f,  -42.0f, -1024.5f),  // Ramp lower
      new Waypoint( 232.5f,  -46.0f, -1105.3f),  // Ramp foot
      new Waypoint( 202.0f,  -48.0f, -1145.0f)   // Exit bottom into Cut 150
    },
    // Lane 2 (NPC 2 - Outer Lane)
    new Waypoint[]{
      new Waypoint( 469.8f,  -90.0f,  -763.8f),  // Green Line Banner
      new Waypoint( 408.0f,  -72.3f,  -923.5f),  // Ramp lower mid
      new Waypoint( 331.0f,  -49.0f, -1048.8f),  // Ramp lower
      new Waypoint( 273.0f,  -53.5f, -1130.5f),  // Ramp foot
      new Waypoint( 244.0f,  -55.0f, -1170.0f)   // Exit bottom into Cut 150
    }
  };

  // Scene 2: Cut 150 (Bottom Scene: Water Jump) - Enters right platform, leaps water to wooden bridge, runs over elevated arched bridge past table to left exit.
  // Takeoff at index 1 (Platform deck edge before water) -> leaps across water to landing at index 2 (bridge ramp foot).
  private static final Waypoint[][] CUT_150_LANES = new Waypoint[][]{
    // Lane 0 (NPC 1 - Inner Lane)
    new Waypoint[]{
      new Waypoint( 388.6f,  -61.5f, -349.4f),        // Station 0: Platform enter
      new Waypoint( 286.7f,  -46.8f, -378.3f, true),  // Station 1: Platform takeoff before water (Green Takeoff)
      new Waypoint(  46.4f, -112.6f, -342.9f),        // Station 2: Bridge ramp foot landing across water (Green Landing)
      new Waypoint(   8.4f, -136.0f, -324.5f),        // Station 3: Bridge ramp lower ascending
      new Waypoint( -30.2f, -157.1f, -313.6f),        // Station 4: Bridge ramp mid
      new Waypoint( -65.6f, -163.0f, -296.7f),        // Station 5: Bridge ramp upper approach
      new Waypoint(-100.6f, -151.0f, -279.7f),        // Station 6: Approach to bridge crest
      new Waypoint(-132.0f, -141.1f, -264.9f),        // Station 7: Bridge crest
      new Waypoint(-161.2f, -138.0f, -251.4f),        // Station 8: Bridge crest descent start
      new Waypoint(-185.9f, -126.0f, -235.6f),        // Station 9: Bridge arch mid descent
      new Waypoint(-212.7f, -106.0f, -224.7f),        // Station 10: Bridge lower descent
      new Waypoint(-246.4f,  -94.0f, -222.9f),        // Station 11: Bridge ramp lower
      new Waypoint(-282.4f,  -88.0f, -215.7f),        // Station 12: Bottom of bridge ramp
      new Waypoint(-325.7f,  -65.0f, -177.4f),        // Station 13: Transition from ramp onto deck
      new Waypoint(-370.8f,  -52.1f, -131.6f),        // Station 14: Deck past table
      new Waypoint(-522.4f,  -68.0f,  -15.0f)         // Station 15: Exit left into Cut 149
    },
    // Lane 1 (Player - Center Lane)
    new Waypoint[]{
      new Waypoint( 380.2f,  -61.5f, -317.5f),        // Station 0: Platform enter
      new Waypoint( 286.1f,  -46.8f, -342.3f, true),  // Station 1: Platform takeoff before water (Green Takeoff)
      new Waypoint(  48.6f, -112.6f, -323.0f),        // Station 2: Bridge ramp foot landing across water (Green Landing)
      new Waypoint(  12.0f, -136.0f, -312.0f),        // Station 3: Bridge ramp lower ascending
      new Waypoint( -25.6f, -157.1f, -301.4f),        // Station 4: Bridge ramp mid
      new Waypoint( -60.0f, -163.0f, -285.0f),        // Station 5: Bridge ramp upper approach
      new Waypoint( -95.0f, -151.0f, -268.0f),        // Station 6: Approach to bridge crest
      new Waypoint(-126.5f, -141.1f, -253.1f),        // Station 7: Bridge crest
      new Waypoint(-155.0f, -138.0f, -240.0f),        // Station 8: Bridge crest descent start
      new Waypoint(-180.0f, -126.0f, -224.0f),        // Station 9: Bridge arch mid descent
      new Waypoint(-210.0f, -106.0f, -212.0f),        // Station 10: Bridge lower descent
      new Waypoint(-245.0f,  -94.0f, -210.0f),        // Station 11: Bridge ramp lower
      new Waypoint(-275.0f,  -88.0f, -205.0f),        // Station 12: Bottom of bridge ramp
      new Waypoint(-310.0f,  -65.0f, -165.0f),        // Station 13: Transition from ramp onto deck
      new Waypoint(-350.8f,  -52.1f, -109.3f),        // Station 14: Deck past table
      new Waypoint(-504.1f,  -68.0f,    8.8f)         // Station 15: Exit left into Cut 149
    },
    // Lane 2 (NPC 2 - Outer Lane)
    new Waypoint[]{
      new Waypoint( 371.8f,  -61.5f, -285.6f),        // Station 0: Platform enter
      new Waypoint( 285.5f,  -46.8f, -306.3f, true),  // Station 1: Platform takeoff before water (Green Takeoff)
      new Waypoint(  50.8f, -112.6f, -303.1f),        // Station 2: Bridge ramp foot landing across water (Green Landing)
      new Waypoint(  15.6f, -136.0f, -299.5f),        // Station 3: Bridge ramp lower ascending
      new Waypoint( -21.0f, -157.1f, -289.2f),        // Station 4: Bridge ramp mid
      new Waypoint( -54.4f, -163.0f, -273.3f),        // Station 5: Bridge ramp upper approach
      new Waypoint( -89.4f, -151.0f, -256.3f),        // Station 6: Approach to bridge crest
      new Waypoint(-121.0f, -141.1f, -241.3f),        // Station 7: Bridge crest
      new Waypoint(-148.8f, -138.0f, -228.6f),        // Station 8: Bridge crest descent start
      new Waypoint(-174.1f, -126.0f, -212.4f),        // Station 9: Bridge arch mid descent
      new Waypoint(-207.3f, -106.0f, -199.3f),        // Station 10: Bridge lower descent
      new Waypoint(-243.6f,  -94.0f, -197.1f),        // Station 11: Bridge ramp lower
      new Waypoint(-267.6f,  -88.0f, -194.3f),        // Station 12: Bottom of bridge ramp
      new Waypoint(-294.3f,  -65.0f, -152.6f),        // Station 13: Transition from ramp onto deck
      new Waypoint(-330.8f,  -52.1f,  -87.0f),        // Station 14: Deck past table
      new Waypoint(-485.8f,  -68.0f,   32.6f)         // Station 15: Exit left into Cut 149
    }
  };

  // Scene 3: Cut 149 (Top Scene: Ticket Vendor Girl) - Left ramp -> booth roof platform -> doorway gap jump -> middle booth platform -> run across middle booth roof -> downward ramp -> barrier jump -> exit into Cut 151.
  // Hurdle 1 at index 2 (Left roof platform) -> leaps across doorway opening to landing at index 3 (middle booth platform).
  // Station 4 & 5 at index 4 & 5 (Middle booth roof peak & right edge) -> runs down onto downward ramp.
  // Hurdle 2 at index 6 (Downward ramp before barrier) -> leaps over wooden barrier to landing at index 7 (downward ramp past barrier).
  private static final Waypoint[][] CUT_149_LANES = new Waypoint[][]{
    // Lane 0 (NPC 1 - Inner Lane)
    new Waypoint[]{
      new Waypoint(-458.3f, -100.0f, -349.8f),        // Station 0: Left entrance
      new Waypoint(-315.5f, -180.0f, -202.3f),        // Station 1: Left ramp ascending
      new Waypoint(-237.8f, -218.0f, -133.5f, true),  // Station 2: Left roof takeoff (Doorway gap takeoff)
      new Waypoint( -72.5f, -212.5f,  -57.8f),        // Station 3: Middle booth platform (Doorway gap landing)
      new Waypoint( 130.5f, -201.0f,   34.0f),        // Station 4: Middle booth roof peak (Traverse peak)
      new Waypoint( 165.0f, -195.0f,   37.0f),        // Station 5: Middle booth roof right (Traverse right)
      new Waypoint( 311.0f, -121.3f,   40.5f, true),  // Station 6: Upper ramp before barrier (Barrier takeoff)
      new Waypoint( 438.0f,  -64.5f,   31.5f),        // Station 7: Ramp past barrier (Barrier landing)
      new Waypoint( 632.8f,  -26.8f,   19.0f)         // Station 8: Exit right into Cut 151
    },
    // Lane 1 (Player - Center Lane)
    new Waypoint[]{
      new Waypoint(-489.9f, -100.0f, -317.9f),        // Station 0: Left entrance
      new Waypoint(-341.0f, -180.0f, -175.0f),        // Station 1: Left ramp ascending
      new Waypoint(-262.4f, -218.0f, -100.6f, true),  // Station 2: Left roof takeoff (Doorway gap takeoff)
      new Waypoint( -89.0f, -212.5f,  -20.3f),        // Station 3: Middle booth platform (Doorway gap landing)
      new Waypoint( 119.9f, -206.5f,   72.1f),        // Station 4: Middle booth roof peak (Traverse peak)
      new Waypoint( 165.0f, -195.0f,   73.5f),        // Station 5: Middle booth roof right (Traverse right)
      new Waypoint( 308.4f, -123.4f,   79.1f, true),  // Station 6: Upper ramp before barrier (Barrier takeoff)
      new Waypoint( 438.8f,  -66.0f,   72.9f),        // Station 7: Ramp past barrier (Barrier landing)
      new Waypoint( 635.0f,  -33.4f,   53.6f)         // Station 8: Exit right into Cut 151
    },
    // Lane 2 (NPC 2 - Outer Lane)
    new Waypoint[]{
      new Waypoint(-518.0f, -100.0f, -289.3f),        // Station 0: Left entrance
      new Waypoint(-364.8f, -180.0f, -149.5f),        // Station 1: Left ramp ascending
      new Waypoint(-287.5f, -218.0f,  -67.3f, true),  // Station 2: Left roof takeoff (Doorway gap takeoff)
      new Waypoint(-104.8f, -212.5f,   15.0f),        // Station 3: Middle booth platform (Doorway gap landing)
      new Waypoint( 109.0f, -212.3f,  109.5f),        // Station 4: Middle booth roof peak (Traverse peak)
      new Waypoint( 165.0f, -195.0f,  112.0f),        // Station 5: Middle booth roof right (Traverse right)
      new Waypoint( 305.5f, -125.5f,  116.3f, true),  // Station 6: Upper ramp before barrier (Barrier takeoff)
      new Waypoint( 439.8f,  -67.8f,  118.0f),        // Station 7: Ramp past barrier (Barrier landing)
      new Waypoint( 637.3f,  -39.8f,   87.0f)         // Station 8: Exit right into Cut 151
    }
  };

  // Scene 4: Cut 151 (Full Track for subsequent laps) - Starts on catwalk in plain view approaching Hurdle 1.
  // Hurdle 1 at index 1 (Log 1 takeoff).
  // Hurdle 2 at index 3 (Log 2 takeoff).
  // Curves smoothly along circular catwalk, enters ramp, crosses Green Line banner (Finish Line) to count lap, and exits into Cut 150.
  private static final Waypoint[][] CUT_151_FULL_LANES = new Waypoint[][]{
    // Lane 0 (NPC 1 - Inner Lane)
    new Waypoint[]{
      new Waypoint(-696.3f,   22.3f, 1271.3f),        // Station 0: Catwalk approach (entrance)
      new Waypoint(-501.0f,   25.5f, 1230.0f, true),  // Station 1: Log 1 takeoff (Green 1)
      new Waypoint(-245.3f,   20.5f, 1144.5f),        // Station 2: Log 1 landing
      new Waypoint(  -5.0f,  -22.0f, 1000.0f, true),  // Station 3: Log 2 takeoff (Green 2)
      new Waypoint( 105.5f,  -65.0f,  899.5f),        // Station 4: Log 2 landing
      new Waypoint( 195.0f, -151.8f,  544.8f),        // Station 5: Catwalk descending
      new Waypoint( 302.8f, -183.5f,  382.5f),        // Station 6: Catwalk curve 1
      new Waypoint( 370.3f, -203.5f,  215.3f),        // Station 7: Catwalk curve 2
      new Waypoint( 421.8f, -204.3f,   37.0f),        // Station 8: Catwalk curve 3
      new Waypoint( 455.5f, -163.0f, -201.3f),        // Station 9: Upper ramp 1
      new Waypoint( 454.0f, -133.0f, -372.3f),        // Station 10: Upper ramp 2
      new Waypoint( 428.3f, -101.0f, -543.5f),        // Station 11: Mid ramp
      new Waypoint( 383.3f,  -79.3f, -740.0f, false, true), // Station 12: Green Line Banner (Lap Finish!)
      new Waypoint( 324.0f,  -61.0f, -885.3f),        // Station 13: Ramp lower mid
      new Waypoint( 252.8f,  -35.0f, -1000.3f),       // Station 14: Ramp lower
      new Waypoint( 193.0f,  -38.5f, -1080.8f),       // Station 15: Ramp foot
      new Waypoint( 163.0f,  -40.0f, -1120.0f)        // Station 16: Exit bottom into Cut 150
    },
    // Lane 1 (Player - Center Lane)
    new Waypoint[]{
      new Waypoint(-687.5f,   21.0f, 1308.0f),        // Station 0: Catwalk approach (entrance)
      new Waypoint(-491.8f,   24.0f, 1270.0f, true),  // Station 1: Log 1 takeoff (Green 1)
      new Waypoint(-221.1f,   19.1f, 1188.6f),        // Station 2: Log 1 landing
      new Waypoint(  10.0f,  -27.0f, 1035.0f, true),  // Station 3: Log 2 takeoff (Green 2)
      new Waypoint( 136.9f,  -70.3f,  934.0f),        // Station 4: Log 2 landing
      new Waypoint( 230.0f, -158.4f,  572.5f),        // Station 5: Catwalk descending
      new Waypoint( 342.6f, -190.3f,  403.5f),        // Station 6: Catwalk curve 1
      new Waypoint( 410.0f, -213.4f,  233.8f),        // Station 7: Catwalk curve 2
      new Waypoint( 465.3f, -210.0f,   44.5f),        // Station 8: Catwalk curve 3
      new Waypoint( 497.1f, -170.6f, -198.9f),        // Station 9: Upper ramp 1
      new Waypoint( 497.4f, -140.3f, -374.9f),        // Station 10: Upper ramp 2
      new Waypoint( 475.9f, -110.0f, -550.3f),        // Station 11: Mid ramp
      new Waypoint( 425.5f,  -84.6f, -751.6f, false, true), // Station 12: Green Line Banner (Lap Finish!)
      new Waypoint( 366.0f,  -66.9f, -904.5f),        // Station 13: Ramp lower mid
      new Waypoint( 292.0f,  -42.0f, -1024.5f),       // Station 14: Ramp lower
      new Waypoint( 232.5f,  -46.0f, -1105.3f),       // Station 15: Ramp foot
      new Waypoint( 202.0f,  -48.0f, -1145.0f)        // Station 16: Exit bottom into Cut 150
    },
    // Lane 2 (NPC 2 - Outer Lane)
    new Waypoint[]{
      new Waypoint(-678.8f,   19.5f, 1344.8f),        // Station 0: Catwalk approach (entrance)
      new Waypoint(-482.3f,   22.5f, 1309.0f, true),  // Station 1: Log 1 takeoff (Green 1)
      new Waypoint(-197.0f,   18.0f, 1233.0f),        // Station 2: Log 1 landing
      new Waypoint(  25.0f,  -30.0f, 1070.0f, true),  // Station 3: Log 2 takeoff (Green 2)
      new Waypoint( 165.8f,  -74.8f,  966.3f),        // Station 4: Log 2 landing
      new Waypoint( 263.5f, -164.8f,  598.8f),        // Station 5: Catwalk descending
      new Waypoint( 384.5f, -197.0f,  425.5f),        // Station 6: Catwalk curve 1
      new Waypoint( 448.8f, -223.0f,  251.5f),        // Station 7: Catwalk curve 2
      new Waypoint( 509.8f, -216.0f,   52.0f),        // Station 8: Catwalk curve 3
      new Waypoint( 540.5f, -178.8f, -196.0f),        // Station 9: Upper ramp 1
      new Waypoint( 542.0f, -147.5f, -377.3f),        // Station 10: Upper ramp 2
      new Waypoint( 523.3f, -119.0f, -557.0f),        // Station 11: Mid ramp
      new Waypoint( 469.8f,  -90.0f, -763.8f, false, true), // Station 12: Green Line Banner (Lap Finish!)
      new Waypoint( 408.0f,  -72.3f, -923.5f),        // Station 13: Ramp lower mid
      new Waypoint( 331.0f,  -49.0f, -1048.8f),       // Station 14: Ramp lower
      new Waypoint( 273.0f,  -53.5f, -1130.5f),       // Station 15: Ramp foot
      new Waypoint( 244.0f,  -55.0f, -1170.0f)        // Station 16: Exit bottom into Cut 150
    }
  };

  public static boolean isRaceActive() {
    return state != RaceState.INACTIVE;
  }

  public static void startRace(final SMap smap) {
    LOGGER.info("LohanRaceManager: Starting Lohan Arena Race with fade-in!");
    state = RaceState.START_FADING_OUT;
    startFadeTicks = 16;
    currentCut = 151;
    currentLap = 1;
    isFirstPass = true;
    lapCountedThisPass = false;
    finishTicks = 0;
    finishFadeTicks = 0;
    feedbackTicks = 0;
    lastInteractPressed = false;
    alertSoundPlayed = false;

    // Save indicator mode and turn OFF so Dart's arrow is never rendered by SMap
    try {
      savedIndicatorMode = CONFIG.getConfig(CoreMod.INDICATOR_MODE_CONFIG.get());
      CONFIG.setConfig(CoreMod.INDICATOR_MODE_CONFIG.get(), IndicatorMode.OFF);
    } catch (Throwable t) {
      LOGGER.warn("Could not disable indicator mode config", t);
    }

    // Trigger fade out to black
    startFadeEffect(1, 15);

    // Reset racers to starting grid (waypoint 0 of Cut 151 Scene 1)
    for (final Racer r : racers) {
      r.pathProgress = 0.0f;
      r.sceneEntryProgress = 0.0f;
      r.currentSpeed = r.baseSpeed;
      r.boostTimer = 0;
      r.slowTimer = 0;
      r.isJumping = false;
      r.jumpQueued = false;
      r.jumpProgress = 0;
      r.lastHurdleIndex = -1;
      r.currentAnimIndex = -1;
      r.jumpArcHeight = 22.0f;
      r.jumpSpeed = 0.075f;
    }
    lastAlertHurdleIndex = -1;
    alertSoundPlayed = false;

    // Save Dart position, hide Dart, attach camera to Dart and lock Dart to player racer
    if (smap.sobjs_800c6880 != null && smap.sobjs_800c6880.length > 0 && smap.sobjs_800c6880[0] != null) {
      final SubmapObject210 dartSobj = smap.sobjs_800c6880[0].innerStruct_00;
      dartSavedPos.set(dartSobj.model_00.coord2_14.coord.transfer);
      dartSavedRot.set(dartSobj.model_00.coord2_14.transforms.rotate);
      dartSobj.hidden_128 = true;
      dartSobj.cameraAttached_178 = false;
      smap.sobjs_800c6880[0].pause();
    }

    assignRacerSobjs(smap);
    pauseNonRacerSobjs(smap);

    // Position contestants at starting line and focus camera
    for (final Racer r : racers) {
      final Waypoint[] laneWaypoints = getLaneWaypoints(r.laneIndex);
      updateRacerPose(r, laneWaypoints, true);
    }
    syncRacersToSobjs(smap);
    focusCamera(smap, playerRacer.pos);
  }

  public static void onSubmapLoad(final SMap smap, final RetailSubmap retail, final List<SubmapObject> objects) {
    // Handle deferred Dart restoration after race finishes and Cut 151 reloads
    if (pendingDartRestore && retail.cut == 151) {
      pendingDartRestore = false;
      LOGGER.info("LohanRaceManager: Restoring Dart after race in Cut 151.");
      restoreDart(smap);
      startFadeEffect(2, 15);
      return;
    }

    if (!isRaceActive()) {
      return;
    }

    currentCut = retail.cut;
    LOGGER.info("LohanRaceManager: Loaded submap cut %d during race.", currentCut);

    if (currentCut == 150) {
      // Fix foreground bridge cutout occluding racers in Cut 150.
      // Background NPC depth ranges (screenZ 50-67) overlap with racer depth ranges (screenZ 57-75),
      // making a single cutout Z that satisfies both constraints mathematically impossible.
      // Solution: hide all background NPCs during Cut 150 (same as Cut 151), then push both
      // cutouts to background depth (4095) so racers on the bridge never clip through planks.
      //
      // FG 2 (Env 8): Left bridge ramp foot cutout - background depth
      retail.setEnvironmentOverlayDepthModeAndZ(2, 2, 4095);
      // FG 3 (Env 9): Main bridge arch cutout - background depth (racers always in front)
      retail.setEnvironmentOverlayDepthModeAndZ(2, 3, 4095);
    }

    assignRacerSobjs(smap);
    pauseNonRacerSobjs(smap);

    // Persist relative gap into this new cut
    lapCountedThisPass = false;
    for (final Racer r : racers) {
      r.pathProgress = r.sceneEntryProgress;
      r.lastHurdleIndex = -1;
      r.currentAnimIndex = -1;
      r.isJumping = false;
      r.jumpQueued = false;
      r.jumpProgress = 0;
      r.jumpArcHeight = 22.0f;
      r.jumpSpeed = 0.075f;
      final Waypoint[] laneWaypoints = getLaneWaypoints(r.laneIndex);
      updateRacerPose(r, laneWaypoints, true);
    }
    alertSoundPlayed = false;
    lastAlertHurdleIndex = -1;

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

  private static void pauseNonRacerSobjs(final SMap smap) {
    if (smap.sobjs_800c6880 == null) return;
    for (int idx = 0; idx < smap.sobjs_800c6880.length; idx++) {
      if (idx == PLAYER_SOBJ || idx == NPC1_SOBJ || idx == NPC2_SOBJ || idx == 0) {
        continue;
      }
      // In Cut 151: hide the two non-racer track creatures (sobj 7, 11+)
      // In Cut 150: hide ALL remaining sobjs — the bridge cutout depth space overlaps with both
      //   racers and any background NPC, so no NPC can be safely visible during Cut 150 racing.
      //   Dart (0) and the 3 racers (8/9/10) are already excluded by the continue above.
      final boolean hideCut151Extra = (idx == 7 || idx >= 11);
      final boolean hideCut150Npc   = (currentCut == 150);
      if (hideCut151Extra || hideCut150Npc) {
        if (smap.sobjs_800c6880[idx] != null) {
          smap.sobjs_800c6880[idx].pause();
          final SubmapObject210 sobj = smap.sobjs_800c6880[idx].innerStruct_00;
          sobj.hidden_128 = true;
          sobj.disableAnimation_12a = true;
          sobj.collisionSizeHorizontal_1a0 = 0;
          sobj.collisionSizeVertical_1a4 = 0;
          sobj.model_00.coord2_14.coord.transfer.set(0.0f, 5000.0f, 0.0f);
        }
      }
    }
  }



  private static void resumeAllSobjs(final SMap smap) {
    // Intentionally empty: on race finish, mapTransition(151, 0) reloads the cut freshly.
  }

  private static void suppressInteractInput(final SMap smap) {
    try {
      if (inputPressedField == null) {
        inputPressedField = SMap.class.getDeclaredField("inputPressed");
        inputPressedField.setAccessible(true);
        inputRepeatField = SMap.class.getDeclaredField("inputRepeat");
        inputRepeatField.setAccessible(true);
        inputHeldField = SMap.class.getDeclaredField("inputHeld");
        inputHeldField.setAccessible(true);
      }
      final int pressed = inputPressedField.getInt(smap);
      if ((pressed & 0x20) != 0) {
        inputPressedField.setInt(smap, pressed & ~0x20);
      }
      final int repeat = inputRepeatField.getInt(smap);
      if ((repeat & 0x20) != 0) {
        inputRepeatField.setInt(smap, repeat & ~0x20);
      }
      final int held = inputHeldField.getInt(smap);
      if ((held & 0x20) != 0) {
        inputHeldField.setInt(smap, held & ~0x20);
      }
    } catch (Throwable ignored) {
    }
  }

  public static void onRender() {
    if (!isRaceActive()) {
      return;
    }

    if (currentCut == 150 && currentEngineState_8004dd04 instanceof final SMap smap && smap.submap instanceof final RetailSubmap retail) {
      retail.setEnvironmentOverlayDepthModeAndZ(2, 2, 4095);
      retail.setEnvironmentOverlayDepthModeAndZ(2, 3, 4095);
      // Enforce per-frame: NPC scripts can resume themselves after cut-load, re-showing characters.
      // Dart (0) and the 3 racers are the only sobjs that should be visible in Cut 150.
      if (smap.sobjs_800c6880 != null) {
        for (int idx = 0; idx < smap.sobjs_800c6880.length; idx++) {
          if (idx == 0 || idx == PLAYER_SOBJ || idx == NPC1_SOBJ || idx == NPC2_SOBJ) continue;
          if (smap.sobjs_800c6880[idx] != null) {
            final SubmapObject210 s = smap.sobjs_800c6880[idx].innerStruct_00;
            s.hidden_128 = true;
            s.model_00.coord2_14.coord.transfer.set(0.0f, 5000.0f, 0.0f);
          }
        }
      }
    }

    try {
      if (state == RaceState.START_FADING_OUT) {
        updateStartFade();
      } else if (state == RaceState.COUNTDOWN) {
        updateCountdown();
      } else if (state == RaceState.RACING) {
        updateRace();
      } else if (state == RaceState.FINISH_IDLE || state == RaceState.FINISH_FADING_OUT) {
        updateFinished();
      }

      // Render HUD and Arrow
      renderRaceHUD();
      renderPlayerArrow();
    } catch (Throwable t) {
      LOGGER.error("LohanRaceManager onRender error", t);
    }
  }

  private static void updateStartFade() {
    startFadeTicks--;

    if (currentEngineState_8004dd04 instanceof final SMap smap) {
      for (final Racer r : racers) {
        final Waypoint[] laneWaypoints = getLaneWaypoints(r.laneIndex);
        updateRacerPose(r, laneWaypoints, true);
      }
      syncRacersToSobjs(smap);
      focusCamera(smap, playerRacer.pos);
    }

    if (startFadeTicks <= 0) {
      // Screen is fully black: fade in on the lined up racers!
      startFadeEffect(2, 15);
      state = RaceState.COUNTDOWN;
      countdownTicks = 120; // 4 seconds (3, 2, 1, GO!)
      LOGGER.info("LohanRaceManager: Faded in on starting grid. Beginning countdown.");
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
        updateRacerPose(r, laneWaypoints, true);
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

    // Suppress interact input so SMap and background NPCs don't receive it and trigger dialogue!
    suppressInteractInput(smap);

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

      // Hurdle detection based on actual 3D Euclidean distance in world units
      final int upcomingHurdle = findUpcomingHurdle(r, waypoints);
      if (r.isPlayer && upcomingHurdle != -1) {
        if (upcomingHurdle != lastAlertHurdleIndex) {
          lastAlertHurdleIndex = upcomingHurdle;
          alertSoundPlayed = false;
        }

        final Waypoint hw = waypoints[upcomingHurdle];
        final float dist3d = r.pos.distance(hw.x, hw.y, hw.z);
        final float progressDist = (float) upcomingHurdle - r.pathProgress;

        // In approach zone when approaching obstacle and within 95 world units
        final boolean inApproachZone = progressDist > -0.05f && dist3d < 95.0f && r.lastHurdleIndex != upcomingHurdle;

        // Show/hide the ! alert indicator
        setAlertIndicator(smap, PLAYER_SOBJ, inApproachZone && !r.jumpQueued && !r.isJumping);

        // Play the standard LoD prompt "boing" when ! first appears
        if (inApproachZone && !alertSoundPlayed && !r.jumpQueued && !r.isJumping) {
          playMenuSound(4); // Standard LoD interaction prompt sound
          alertSoundPlayed = true;
        }

        // Player jump attempt: anytime alert is active (or right up to takeoff), pressing interact registers a successful jump!
        if (actionJustPressed && r.lastHurdleIndex != upcomingHurdle && !r.isJumping && !r.jumpQueued &&
            (inApproachZone || (progressDist > -0.15f && dist3d < 100.0f))) {
          r.jumpQueued = true;
          r.jumpSucceeded = true;
          r.boostTimer = 45;
          r.slowTimer = 0;
          playMenuSound(2); // Standard LoD confirm/accept sound
          jumpFeedbackText = "PERFECT JUMP!";
          feedbackTicks = 40;
          setAlertIndicator(smap, PLAYER_SOBJ, false);

          if (r.pathProgress >= upcomingHurdle) {
            launchJump(r, upcomingHurdle, true);
          }
        }
      } else if (!r.isPlayer && upcomingHurdle != -1 && !r.isJumping && !r.jumpQueued && r.lastHurdleIndex != upcomingHurdle) {
        // NPC hurdle jumping logic
        final Waypoint hw = waypoints[upcomingHurdle];
        final float dist3d = r.pos.distance(hw.x, hw.y, hw.z);
        final float progressDist = (float) upcomingHurdle - r.pathProgress;
        if (progressDist > -0.05f && dist3d < 55.0f) {
          final boolean success = Math.random() < (r.id == 0 ? 0.82 : 0.76);
          if (success) {
            r.jumpQueued = true;
            r.jumpSucceeded = true;
            r.boostTimer = 45;
            r.slowTimer = 0;
            if (r.pathProgress >= upcomingHurdle) {
              launchJump(r, upcomingHurdle, true);
            }
          } else {
            r.lastHurdleIndex = upcomingHurdle;
            handleMissedHurdle(r, upcomingHurdle);
          }
        }
      }

      // Launch queued jump when racer reaches takeoff point
      if (r.jumpQueued && upcomingHurdle != -1 && r.pathProgress >= upcomingHurdle) {
        launchJump(r, upcomingHurdle, r.jumpSucceeded);
      }

      // Missed hurdle timeout (stumble) - only triggers if racer passed takeoff without jumping
      if (upcomingHurdle != -1 && !r.isJumping && !r.jumpQueued && r.lastHurdleIndex != upcomingHurdle) {
        final float progressDist = (float) upcomingHurdle - r.pathProgress;
        if (progressDist < -0.10f) {
          r.lastHurdleIndex = upcomingHurdle;
          handleMissedHurdle(r, upcomingHurdle);
        }
      }

      // Distance step along waypoints
      final int curWp = Math.max(0, Math.min(waypoints.length - 2, (int) Math.floor(Math.max(0.0f, r.pathProgress))));
      final Waypoint w0 = waypoints[curWp];
      final Waypoint w1 = waypoints[curWp + 1];
      final float segDist = Math.max(1.0f, (float) Math.hypot(w1.x - w0.x, w1.z - w0.z));
      final float step = r.currentSpeed / segDist;
      r.pathProgress += step;

      // Update 3D position and rotation (smoothly rotating around corners)
      updateRacerPose(r, waypoints, false);

      // Jumping arc update and vertical height offset
      if (r.isJumping) {
        final float totalJumpLen = Math.max(0.1f, r.jumpEndProgress - r.jumpStartProgress);
        final float jumpFraction = Math.max(0.0f, Math.min(1.0f, (r.pathProgress - r.jumpStartProgress) / totalJumpLen));
        r.jumpProgress = jumpFraction;
        final float jumpArc = (float) Math.sin(jumpFraction * Math.PI);
        r.pos.y -= r.jumpArcHeight * jumpArc;
        if (jumpFraction >= 1.0f) {
          r.isJumping = false;
          r.jumpProgress = 0.0f;
        }
      }
    }

    // Lap detection when crossing start/finish line in Cut 151
    if (currentCut == 151 && !isFirstPass && !lapCountedThisPass) {
      final int curIdx = Math.max(0, Math.min(playerWaypoints.length - 1, (int) Math.floor(Math.max(0.0f, playerRacer.pathProgress))));
      if (playerWaypoints[curIdx].isFinishLine) {
        lapCountedThisPass = true;
        currentLap++;
        LOGGER.info("LohanRaceManager: Crossed start/finish line! Completed lap %d of %d.", currentLap - 1, TOTAL_LAPS);
        if (currentLap > TOTAL_LAPS) {
          finishRace();
          return;
        }
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

  private static float getHurdleArcHeight(final int cut, final int hurdleIndex) {
    if (cut == 150) {
      return 70.0f; // Water jump
    } else if (cut == 149) {
      return 55.0f; // Cut 149: doorway gap jump (index 2) and ramp barrier jump (index 6)
    } else {
      return 65.0f; // Cut 151 log hurdles
    }
  }

  private static void launchJump(final Racer r, final int hurdleIndex, final boolean goodTiming) {
    r.isJumping = true;
    r.jumpQueued = false;
    r.lastHurdleIndex = hurdleIndex;
    r.jumpStartProgress = (float) hurdleIndex;
    r.jumpEndProgress = (float) (hurdleIndex + 1);
    r.jumpProgress = 0.0f;
    r.jumpSucceeded = goodTiming;
    r.jumpArcHeight = getHurdleArcHeight(currentCut, hurdleIndex);

    if (goodTiming) {
      r.boostTimer = 45;
      r.slowTimer = 0;
    }
  }

  private static void handleMissedHurdle(final Racer r, final int hurdleIndex) {
    r.jumpQueued = false;
    r.jumpSucceeded = false;
    r.slowTimer = 50; // Speed penalty / stumble
    r.boostTimer = 0;

    final boolean isGap = (currentCut == 150) || (currentCut == 149 && hurdleIndex <= 3);
    if (isGap) {
      // Must hop across the gap with a low stumble arc so racer never walks on air
      r.isJumping = true;
      r.jumpStartProgress = (float) hurdleIndex;
      r.jumpEndProgress = (float) (hurdleIndex + 1);
      r.jumpProgress = 0.0f;
      r.jumpArcHeight = 30.0f;
    } else {
      // Log obstacle / ramp barrier: hits barrier and stumbles forward
      r.isJumping = false;
    }

    if (r.isPlayer) {
      jumpFeedbackText = "MISSED!";
      feedbackTicks = 40;
      if (currentEngineState_8004dd04 instanceof final SMap smap) {
        setAlertIndicator(smap, PLAYER_SOBJ, false);
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
        sobj.disableAnimation_12a = false; // Always ensure animation is active
        sobj.flags_190 &= ~0x6000_0000;
        if (sobj.model_00.animationState_9c == 2) {
          sobj.model_00.animationState_9c = 0;
        }

        sobj.model_00.coord2_14.coord.transfer.set(r.pos);
        sobj.model_00.coord2_14.transforms.rotate.set(r.rot);
        sobj.model_00.coord2_14.transforms.scale.set(0.625f, 0.625f, 0.625f);

        // Animation state machine:
        // 0 = Idle (countdown & finish idle)
        // 2 = Jump leap
        // 3 = Running gallop
        // 4 = Stumbling (slowTimer)
        final int targetAnim = (state == RaceState.COUNTDOWN || state == RaceState.START_FADING_OUT ||
                                state == RaceState.FINISH_IDLE || state == RaceState.FINISH_FADING_OUT) ? 0 :
                               (r.isJumping ? 2 : (r.slowTimer > 0 ? 4 : 3));

        if (sobj.animIndex_132 != targetAnim || sobj.model_00.anim_08 == null || sobj.disableAnimation_12a) {
          r.currentAnimIndex = targetAnim;
          sobj.animIndex_132 = targetAnim;
          sobj.disableAnimation_12a = false;
          sobj.flags_190 &= ~0x6000_0000;
          sobj.animationFinishedFrames_12c = 0;
          if (smap.submap != null) {
            List<legend.game.types.TmdAnimationFile> anims = null;
            if (sobj.sobjIndex_12e < smap.submap.objects.size()) {
              anims = smap.submap.objects.get(sobj.sobjIndex_12e).animations;
            }
            if ((anims == null || anims.isEmpty()) && smap.submap.objects.size() > 7) {
              anims = smap.submap.objects.get(7).animations;
            }
            if (anims != null && targetAnim < anims.size() && anims.get(targetAnim) != null) {
              loadModelStandardAnimation(sobj.model_00, anims.get(targetAnim));
            }
          }
        }
      }
    }
  }

  private static void focusCamera(final SMap smap, final Vector3f targetPos) {
    // 1. Keep Dart hidden and parked out of bounds so Dart never collides with NPCs or triggers dialogues
    if (smap.sobjs_800c6880 != null && smap.sobjs_800c6880.length > 0 && smap.sobjs_800c6880[0] != null) {
      final ScriptState<SubmapObject210> dartState = smap.sobjs_800c6880[0];
      final SubmapObject210 dartSobj = dartState.innerStruct_00;
      if (isRaceActive()) {
        dartSobj.hidden_128 = true;
        dartSobj.cameraAttached_178 = false;
        dartSobj.collisionSizeHorizontal_1a0 = 0;
        dartSobj.collisionSizeVertical_1a4 = 0;
        dartSobj.collisionReach_1b4 = 0;
        dartSobj.collidedWithSobjIndex_19c = -1;
        dartSobj.collidedWithSobjIndex_1a8 = -1;
        dartSobj.model_00.coord2_14.coord.transfer.set(playerRacer.pos);
      }
    }

    // Keep extra track creatures hidden and out of bounds during Cut 151
    if (currentCut == 151 && smap.sobjs_800c6880 != null) {
      for (int idx : new int[]{7, 11}) {
        if (idx < smap.sobjs_800c6880.length && smap.sobjs_800c6880[idx] != null) {
          final SubmapObject210 s = smap.sobjs_800c6880[idx].innerStruct_00;
          s.hidden_128 = true;
          s.model_00.coord2_14.coord.transfer.set(0.0f, 5000.0f, 0.0f);
        }
      }
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
    // Scene cycle:
    // Scene 1 (Cut 151 initial start) -> Scene 2 (Cut 150 bottom) -> Scene 3 (Cut 149 doorway) -> Scene 4 (Cut 151 full lap)
    int nextCut;
    int nextScene;

    if (currentCut == 151) {
      isFirstPass = false;
      nextCut = 150;
      nextScene = 0;
    } else if (currentCut == 150) {
      nextCut = 149;
      nextScene = 11;
    } else {
      nextCut = 151;
      nextScene = 0;
    }

    // Persist relative gap of each racer vs player into the next scene
    for (final Racer r : racers) {
      r.sceneEntryProgress = r.pathProgress - playerRacer.pathProgress;
    }

    state = RaceState.LAP_TRANSITION;
    LOGGER.info("LohanRaceManager: Transitioning from cut %d to cut %d (scene %d)...", currentCut, nextCut, nextScene);
    smap.mapTransition(nextCut, nextScene);
  }

  private static void finishRace() {
    state = RaceState.FINISH_IDLE;
    finishTicks = 90; // ~3 seconds racers idle and celebrate
    finishFadeTicks = 16;
    playMenuSound(1);
    LOGGER.info("LohanRaceManager: Race finished! Player placement: %d", getPlayerPlacement());
  }

  private static void updateFinished() {
    if (!(currentEngineState_8004dd04 instanceof final SMap smap)) return;

    if (state == RaceState.FINISH_IDLE) {
      finishTicks--;
      // Keep racers idling on the track and camera locked
      for (final Racer r : racers) {
        final Waypoint[] laneWaypoints = getLaneWaypoints(r.laneIndex);
        updateRacerPose(r, laneWaypoints, false);
      }
      syncRacersToSobjs(smap);
      focusCamera(smap, playerRacer.pos);

      if (finishTicks <= 0) {
        startFadeEffect(1, 15); // Fade out to black
        state = RaceState.FINISH_FADING_OUT;
      }
    } else if (state == RaceState.FINISH_FADING_OUT) {
      finishFadeTicks--;
      for (final Racer r : racers) {
        final Waypoint[] laneWaypoints = getLaneWaypoints(r.laneIndex);
        updateRacerPose(r, laneWaypoints, false);
      }
      syncRacersToSobjs(smap);
      focusCamera(smap, playerRacer.pos);

      if (finishFadeTicks <= 0) {
        returnToVendor();
      }
    }
  }

  private static void returnToVendor() {
    state = RaceState.INACTIVE;
    safelyClearHUDTextbox();
    if (!(currentEngineState_8004dd04 instanceof final SMap smap)) return;

    LOGGER.info("LohanRaceManager: Returning to Cut 151 vendor booth.");

    // Award reward if won 1st place!
    if (getPlayerPlacement() == 1) {
      if (gameState_800babc8 != null && gameState_800babc8.scriptData_08 != null) {
        gameState_800babc8.scriptData_08[27] = Math.min(99, gameState_800babc8.scriptData_08[27] + 3);
        LOGGER.info("LohanRaceManager: Player won 1st place! Awarded 3 tickets (total=%d).", gameState_800babc8.scriptData_08[27]);
      }
    }

    // Always do a clean map transition back to Cut 151 scene 0.
    // This freshly reloads all retail scripts, models, and roaming creatures.
    pendingDartRestore = true;
    smap.mapTransition(151, 0);
  }

  private static void restoreDart(final SMap smap) {
    LOGGER.info("LohanRaceManager: Restoring Dart in front of vendor booth.");

    if (smap.sobjs_800c6880 != null && smap.sobjs_800c6880.length > 0 && smap.sobjs_800c6880[0] != null) {
      final ScriptState<SubmapObject210> dartState = smap.sobjs_800c6880[0];
      final SubmapObject210 dart = dartState.innerStruct_00;
      dart.hidden_128 = false;
      dart.disableAnimation_12a = false;
      dart.cameraAttached_178 = true;
      dart.model_00.coord2_14.coord.transfer.set(dartSavedPos);
      dart.model_00.coord2_14.transforms.rotate.set(dartSavedRot);
      dartState.resume();
      if (dartState.ticker_04 != null) {
        dartState.ticker_04.accept(dartState, dart);
      }

      // Direct camera to Dart without re-hiding Dart
      try {
        GTE.setTransforms(worldToScreenMatrix_800c3548);
        if (setCameraPosMethod == null) {
          setCameraPosMethod = SMap.class.getDeclaredMethod("setCameraPos", int.class, Vector3f.class);
          setCameraPosMethod.setAccessible(true);
        }
        setCameraPosMethod.invoke(smap, 1, dartSavedPos);
      } catch (Throwable ignored) {
      }
    }

    // Restore indicator config
    if (savedIndicatorMode != null) {
      try {
        CONFIG.setConfig(CoreMod.INDICATOR_MODE_CONFIG.get(), savedIndicatorMode);
      } catch (Throwable ignored) {
      }
      savedIndicatorMode = null;
    }
  }

  private static Waypoint[] getLaneWaypoints(final int laneIndex) {
    final Waypoint[][] lanes;
    if (currentCut == 150) {
      lanes = CUT_150_LANES;
    } else if (currentCut == 149) {
      lanes = CUT_149_LANES;
    } else {
      lanes = isFirstPass ? CUT_151_START_LANES : CUT_151_FULL_LANES;
    }
    final int safeLane = Math.max(0, Math.min(lanes.length - 1, laneIndex));
    return lanes[safeLane];
  }

  private static int findUpcomingHurdle(final Racer r, final Waypoint[] waypoints) {
    if (waypoints.length == 0) return -1;
    final int currentIdx = Math.max(0, (int) Math.floor(Math.max(0.0f, r.pathProgress)));
    for (int i = currentIdx; i < Math.min(waypoints.length, currentIdx + 3); i++) {
      if (waypoints[i].isHurdle) {
        return i;
      }
    }
    return -1;
  }

  private static void updateRacerPose(final Racer r, final Waypoint[] waypoints, final boolean instantYaw) {
    if (waypoints.length < 2) return;

    if (r.pathProgress < 0.0f) {
      // Extrapolate backward along entry vector
      final Waypoint p0 = waypoints[0];
      final Waypoint p1 = waypoints[1];
      final float dx = p1.x - p0.x;
      final float dy = p1.y - p0.y;
      final float dz = p1.z - p0.z;
      r.pos.x = p0.x + dx * r.pathProgress;
      r.pos.y = p0.y + dy * r.pathProgress;
      r.pos.z = p0.z + dz * r.pathProgress;
      r.groundY = r.pos.y;
      final float targetYaw = MathHelper.positiveAtan2(dz, dx);
      if (instantYaw) {
        r.rot.y = targetYaw;
      } else {
        smoothRotateY(r, targetYaw);
      }
      return;
    }

    final int idx = Math.max(0, Math.min(waypoints.length - 2, (int) Math.floor(r.pathProgress)));
    final float t = Math.max(0.0f, Math.min(1.0f, r.pathProgress - idx));

    final Waypoint p0 = waypoints[idx];
    final Waypoint p1 = waypoints[idx + 1];

    r.pos.x = p0.x + (p1.x - p0.x) * t;
    r.pos.y = p0.y + (p1.y - p0.y) * t;
    r.pos.z = p0.z + (p1.z - p0.z) * t;
    r.groundY = r.pos.y;

    final float dx = p1.x - p0.x;
    final float dz = p1.z - p0.z;
    final float targetYaw = MathHelper.positiveAtan2(dz, dx);
    if (instantYaw) {
      r.rot.y = targetYaw;
    } else {
      smoothRotateY(r, targetYaw);
    }
  }

  private static void smoothRotateY(final Racer r, final float targetYaw) {
    float diff = (targetYaw - r.rot.y) % (float) (Math.PI * 2);
    if (diff < -Math.PI) diff += (float) (Math.PI * 2);
    if (diff > Math.PI) diff -= (float) (Math.PI * 2);
    r.rot.y += diff * 0.35f;
  }

  private static void setAlertIndicator(final SMap smap, final int sobjIndex, final boolean show) {
    if (smap != null && smap.sobjs_800c6880 != null && sobjIndex < smap.sobjs_800c6880.length && smap.sobjs_800c6880[sobjIndex] != null) {
      final SubmapObject210 sobj = smap.sobjs_800c6880[sobjIndex].innerStruct_00;
      sobj.showAlertIndicator_194 = show;
      sobj.alertIndicatorOffsetY_198 = -30;
    }
  }

  public static int getPlayerPlacement() {
    int placement = 1;
    if (npcRacer1.pathProgress > playerRacer.pathProgress) placement++;
    if (npcRacer2.pathProgress > playerRacer.pathProgress) placement++;
    return placement;
  }

  private static void renderPlayerArrow() {
    if (!isRaceActive()) return;
    if (!(currentEngineState_8004dd04 instanceof final SMap smap)) return;
    if (smap.sobjs_800c6880 == null || smap.sobjs_800c6880.length <= PLAYER_SOBJ || smap.sobjs_800c6880[PLAYER_SOBJ] == null) return;

    try {
      if (renderTriangleIndicatorsMethod == null) {
        renderTriangleIndicatorsMethod = SMap.class.getDeclaredMethod("renderTriangleIndicators");
        renderTriangleIndicatorsMethod.setAccessible(true);
        triangleIndicatorsField = SMap.class.getDeclaredField("triangleIndicators_800c69fc");
        triangleIndicatorsField.setAccessible(true);
      }

      final TriangleIndicator140 indicator = (TriangleIndicator140) triangleIndicatorsField.get(smap);
      if (indicator != null) {
        final SubmapObject210 playerSobj = smap.sobjs_800c6880[PLAYER_SOBJ].innerStruct_00;
        final MV ls = new MV();
        GsGetLs(playerSobj.model_00.coord2_14, ls);
        PushMatrix();
        GTE.setTransforms(ls);
        GTE.perspectiveTransform(0, -35, 0);
        indicator.playerX_08 = GTE.getScreenX(2);
        indicator.playerY_0c = GTE.getScreenY(2);
        PopMatrix();

        // Suppress door indicators so only Dart's blue player arrow renders
        final short oldType0 = (indicator.indicatorType_18 != null && indicator.indicatorType_18.length > 0) ? indicator.indicatorType_18[0] : -1;
        if (indicator.indicatorType_18 != null) {
          for (int i = 0; i < indicator.indicatorType_18.length; i++) {
            indicator.indicatorType_18[i] = -1;
          }
        }

        renderTriangleIndicatorsMethod.invoke(smap);

        if (indicator.indicatorType_18 != null && indicator.indicatorType_18.length > 0) {
          indicator.indicatorType_18[0] = oldType0;
        }
      }
    } catch (Throwable t) {
      LOGGER.error("Failed to render player arrow", t);
    }
  }

  private static void renderRaceHUD() {
    if (state == RaceState.START_FADING_OUT) {
      safelyClearHUDTextbox();
    } else if (state == RaceState.COUNTDOWN) {
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
    } else if (state == RaceState.FINISH_IDLE || state == RaceState.FINISH_FADING_OUT) {
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
