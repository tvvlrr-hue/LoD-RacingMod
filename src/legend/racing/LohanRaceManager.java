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
      new Waypoint( 355.0f,  -80.0f,  -715.0f),  // Green Line Banner
      new Waypoint( 300.0f,  -61.0f,  -855.0f),  // Ramp mid
      new Waypoint( 235.0f,  -36.0f,  -970.0f),  // Ramp lower
      new Waypoint( 200.0f,  -41.0f, -1070.0f),  // Ramp foot
      new Waypoint( 200.0f,  -46.0f, -1120.0f)   // Exit bottom into Cut 150
    },
    // Lane 1 (Player - Center Lane)
    new Waypoint[]{
      new Waypoint( 376.0f,  -80.0f,  -721.0f),  // Green Line Banner
      new Waypoint( 322.0f,  -61.0f,  -859.0f),  // Ramp mid
      new Waypoint( 251.0f,  -36.0f,  -975.0f),  // Ramp lower
      new Waypoint( 220.0f,  -41.0f, -1072.0f),  // Ramp foot
      new Waypoint( 218.0f,  -46.0f, -1121.0f)   // Exit bottom into Cut 150
    },
    // Lane 2 (NPC 2 - Outer Lane)
    new Waypoint[]{
      new Waypoint( 395.0f,  -80.0f,  -727.0f),  // Green Line Banner
      new Waypoint( 340.0f,  -61.0f,  -865.0f),  // Ramp mid
      new Waypoint( 270.0f,  -36.0f,  -980.0f),  // Ramp lower
      new Waypoint( 240.0f,  -41.0f, -1075.0f),  // Ramp foot
      new Waypoint( 235.0f,  -46.0f, -1122.0f)   // Exit bottom into Cut 150
    }
  };

  // Scene 2: Cut 150 (Bottom Scene: Water Jump) - Enters right platform, leaps water to wooden bridge, runs over elevated bridge past table to left exit.
  // Takeoff at index 1 (Red dot on wooden platform before water gap) -> leaps across water to landing at index 2 (yellow dot at bridge ramp).
  private static final Waypoint[][] CUT_150_LANES = new Waypoint[][]{
    // Lane 0 (NPC 1 - Inner Lane)
    new Waypoint[]{
      new Waypoint( 290.0f,  -47.0f, -385.0f),        // Right platform enter
      new Waypoint( 180.0f,  -42.0f, -355.0f, true),  // Red dot (Platform deck takeoff before water)
      new Waypoint( -20.0f, -154.0f, -325.0f),        // Yellow dot (Bridge ramp landing across water)
      new Waypoint( -50.0f, -163.0f, -310.0f),        // Ascending bridge ramp
      new Waypoint(-115.0f, -144.0f, -275.0f),        // Bridge crest
      new Waypoint(-160.0f, -125.0f, -245.0f),        // Foot of bridge onto wooden deck
      new Waypoint(-240.0f,  -90.0f, -210.0f),        // Deck past table
      new Waypoint(-345.0f,  -60.0f, -150.0f),        // Deck ramp
      new Waypoint(-530.0f,  -72.0f,  -35.0f)         // Exit left into Cut 149
    },
    // Lane 1 (Player - Center Lane)
    new Waypoint[]{
      new Waypoint( 290.0f,  -47.0f, -370.0f),        // Right platform enter
      new Waypoint( 180.0f,  -42.0f, -340.0f, true),  // Red dot (Platform deck takeoff before water)
      new Waypoint( -20.0f, -154.0f, -310.0f),        // Yellow dot (Bridge ramp landing across water)
      new Waypoint( -50.0f, -163.0f, -295.0f),        // Ascending bridge ramp
      new Waypoint(-115.0f, -144.0f, -260.0f),        // Bridge crest
      new Waypoint(-160.0f, -125.0f, -230.0f),        // Foot of bridge onto wooden deck
      new Waypoint(-240.0f,  -90.0f, -195.0f),        // Deck past table
      new Waypoint(-345.0f,  -60.0f, -135.0f),        // Deck ramp
      new Waypoint(-530.0f,  -72.0f,  -20.0f)         // Exit left into Cut 149
    },
    // Lane 2 (NPC 2 - Outer Lane)
    new Waypoint[]{
      new Waypoint( 290.0f,  -47.0f, -355.0f),        // Right platform enter
      new Waypoint( 180.0f,  -42.0f, -325.0f, true),  // Red dot (Platform deck takeoff before water)
      new Waypoint( -20.0f, -154.0f, -295.0f),        // Yellow dot (Bridge ramp landing across water)
      new Waypoint( -50.0f, -163.0f, -280.0f),        // Ascending bridge ramp
      new Waypoint(-115.0f, -144.0f, -245.0f),        // Bridge crest
      new Waypoint(-160.0f, -125.0f, -215.0f),        // Foot of bridge onto wooden deck
      new Waypoint(-240.0f,  -90.0f, -180.0f),        // Deck past table
      new Waypoint(-345.0f,  -60.0f, -120.0f),        // Deck ramp
      new Waypoint(-530.0f,  -72.0f,   -5.0f)         // Exit left into Cut 149
    }
  };

  // Scene 3: Cut 149 (Top Scene: Ticket Vendor Girl) - Left ramp -> booth roof -> gap jump -> archway roof -> barrier jump -> downward ramp.
  // Hurdle 1 at index 4 (Red dot 1) -> leaps doorway gap to landing at index 5 (middle booth roof).
  // Hurdle 2 at index 7 (Red dot 2 before barrier) -> leaps barrier to landing at index 8 (downward ramp).
  private static final Waypoint[][] CUT_149_LANES = new Waypoint[][]{
    // Lane 0 (NPC 1 - Inner Lane)
    new Waypoint[]{
      new Waypoint(-514.0f, -100.0f, -278.0f),        // Left entrance
      new Waypoint(-386.0f, -171.0f, -167.0f),        // Left ramp ascending 1
      new Waypoint(-312.0f, -218.0f,  -87.0f),        // Left ramp ascending 2
      new Waypoint(-230.0f, -218.0f,  -34.0f),        // Left ramp upper approach
      new Waypoint( -75.0f, -217.0f,  -14.0f, true),  // Red dot 1 (Doorway gap takeoff)
      new Waypoint(  95.0f, -212.0f,   41.0f),        // Middle booth roof landing
      new Waypoint( 135.0f, -202.0f,   38.0f),        // Middle booth roof run
      new Waypoint( 155.0f, -195.0f,   41.0f, true),  // Red dot 2 (Barrier takeoff)
      new Waypoint( 290.0f, -134.0f,   61.0f),        // Downward ramp landing
      new Waypoint( 328.0f, -111.0f,   43.0f),        // Downward ramp upper
      new Waypoint( 424.0f,  -72.0f,   46.0f),        // Downward ramp mid
      new Waypoint( 454.0f,  -61.0f,   36.0f),        // Downward ramp lower
      new Waypoint( 618.0f,  -34.0f,   26.0f)         // Exit right into Cut 151
    },
    // Lane 1 (Player - Center Lane)
    new Waypoint[]{
      new Waypoint(-514.0f, -100.0f, -264.0f),        // Left entrance
      new Waypoint(-386.0f, -171.0f, -153.0f),        // Left ramp ascending 1
      new Waypoint(-312.0f, -218.0f,  -73.0f),        // Left ramp ascending 2
      new Waypoint(-230.0f, -218.0f,  -20.0f),        // Left ramp upper approach
      new Waypoint( -75.0f, -217.0f,    0.0f, true),  // Red dot 1 (Doorway gap takeoff)
      new Waypoint(  95.0f, -212.0f,   55.0f),        // Middle booth roof landing
      new Waypoint( 135.0f, -202.0f,   52.0f),        // Middle booth roof run
      new Waypoint( 155.0f, -195.0f,   55.0f, true),  // Red dot 2 (Barrier takeoff)
      new Waypoint( 290.0f, -134.0f,   75.0f),        // Downward ramp landing
      new Waypoint( 328.0f, -111.0f,   57.0f),        // Downward ramp upper
      new Waypoint( 424.0f,  -72.0f,   60.0f),        // Downward ramp mid
      new Waypoint( 454.0f,  -61.0f,   50.0f),        // Downward ramp lower
      new Waypoint( 618.0f,  -34.0f,   40.0f)         // Exit right into Cut 151
    },
    // Lane 2 (NPC 2 - Outer Lane)
    new Waypoint[]{
      new Waypoint(-514.0f, -100.0f, -250.0f),        // Left entrance
      new Waypoint(-386.0f, -171.0f, -139.0f),        // Left ramp ascending 1
      new Waypoint(-312.0f, -218.0f,  -59.0f),        // Left ramp ascending 2
      new Waypoint(-230.0f, -218.0f,   -6.0f),        // Left ramp upper approach
      new Waypoint( -75.0f, -217.0f,   14.0f, true),  // Red dot 1 (Doorway gap takeoff)
      new Waypoint(  95.0f, -212.0f,   69.0f),        // Middle booth roof landing
      new Waypoint( 135.0f, -202.0f,   66.0f),        // Middle booth roof run
      new Waypoint( 155.0f, -195.0f,   69.0f, true),  // Red dot 2 (Barrier takeoff)
      new Waypoint( 290.0f, -134.0f,   89.0f),        // Downward ramp landing
      new Waypoint( 328.0f, -111.0f,   71.0f),        // Downward ramp upper
      new Waypoint( 424.0f,  -72.0f,   74.0f),        // Downward ramp mid
      new Waypoint( 454.0f,  -61.0f,   64.0f),        // Downward ramp lower
      new Waypoint( 618.0f,  -34.0f,   54.0f)         // Exit right into Cut 151
    }
  };

  // Scene 4: Cut 151 (Full Track for subsequent laps) - Starts on catwalk in plain view approaching Hurdle 1.
  // Hurdle 1 at index 1 (Red dot 1 before log 1).
  // Hurdle 2 at index 3 (Red dot 2 before log 2).
  // Curves smoothly along circular catwalk, enters ramp, crosses Green Line banner (Finish Line) to count lap, and exits into Cut 150.
  private static final Waypoint[][] CUT_151_FULL_LANES = new Waypoint[][]{
    // Lane 0 (NPC 1 - Inner Lane)
    new Waypoint[]{
      new Waypoint(-261.0f,   22.0f, 1142.0f),        // Station 0: Catwalk approach
      new Waypoint(-215.0f,   16.5f, 1165.0f, true),  // Station 1: Red dot 1 (Log 1 takeoff)
      new Waypoint( -31.0f,  -17.0f, 1048.0f),        // Station 2: Log 1 landing
      new Waypoint(  -2.0f,  -27.0f, 1027.0f, true),  // Station 3: Red dot 2 (Log 2 takeoff)
      new Waypoint( 111.0f,  -66.0f,  935.0f),        // Station 4: Log 2 landing
      new Waypoint( 142.0f,  -75.0f,  906.0f),        // Station 5: Catwalk descending 1
      new Waypoint( 174.0f, -115.0f,  736.0f),        // Station 6: Catwalk descending 2
      new Waypoint( 210.0f, -155.0f,  580.0f),        // Station 7: Catwalk curve 1
      new Waypoint( 270.0f, -178.0f,  390.0f),        // Station 8: Catwalk curve 2
      new Waypoint( 360.0f, -206.0f,  235.0f),        // Station 9: Catwalk curve 3
      new Waypoint( 405.0f, -214.0f,  215.0f),        // Station 10: Catwalk curve 4
      new Waypoint( 445.0f, -210.0f,   60.0f),        // Station 11: Catwalk curve 5
      new Waypoint( 450.0f, -168.0f, -185.0f),        // Station 12: Catwalk into upper ramp
      new Waypoint( 420.0f, -127.0f, -385.0f),        // Station 13: Ramp upper
      new Waypoint( 400.0f, -101.0f, -525.0f),        // Station 14: Ramp mid
      new Waypoint( 355.0f,  -80.0f, -715.0f, false, true), // Station 15: Green Line Banner (Lap Finish!)
      new Waypoint( 300.0f,  -61.0f, -855.0f),        // Station 16: Ramp past banner
      new Waypoint( 235.0f,  -36.0f, -970.0f),        // Station 17: Ramp lower
      new Waypoint( 200.0f,  -41.0f, -1070.0f),       // Station 18: Ramp foot
      new Waypoint( 200.0f,  -46.0f, -1120.0f)        // Station 19: Exit bottom into Cut 150
    },
    // Lane 1 (Player - Center Lane)
    new Waypoint[]{
      new Waypoint(-250.0f,   22.0f, 1156.0f),        // Station 0: Catwalk approach
      new Waypoint(-204.0f,   16.5f, 1179.0f, true),  // Station 1: Red dot 1 (Log 1 takeoff)
      new Waypoint( -20.0f,  -17.0f, 1062.0f),        // Station 2: Log 1 landing
      new Waypoint(   9.0f,  -27.0f, 1041.0f, true),  // Station 3: Red dot 2 (Log 2 takeoff)
      new Waypoint( 122.0f,  -66.0f,  949.0f),        // Station 4: Log 2 landing
      new Waypoint( 153.0f,  -75.0f,  920.0f),        // Station 5: Catwalk descending 1
      new Waypoint( 185.0f, -115.0f,  750.0f),        // Station 6: Catwalk descending 2
      new Waypoint( 218.0f, -155.0f,  589.0f),        // Station 7: Catwalk curve 1
      new Waypoint( 281.0f, -178.0f,  393.0f),        // Station 8: Catwalk curve 2
      new Waypoint( 375.0f, -206.0f,  238.0f),        // Station 9: Catwalk curve 3
      new Waypoint( 418.0f, -214.0f,  217.0f),        // Station 10: Catwalk curve 4
      new Waypoint( 461.0f, -210.0f,   62.0f),        // Station 11: Catwalk curve 5
      new Waypoint( 468.0f, -168.0f, -185.0f),        // Station 12: Catwalk into upper ramp
      new Waypoint( 437.0f, -127.0f, -386.0f),        // Station 13: Ramp upper
      new Waypoint( 417.0f, -101.0f, -526.0f),        // Station 14: Ramp mid
      new Waypoint( 376.0f,  -80.0f, -721.0f, false, true), // Station 15: Green Line Banner (Lap Finish!)
      new Waypoint( 322.0f,  -61.0f, -859.0f),        // Station 16: Ramp past banner
      new Waypoint( 251.0f,  -36.0f, -975.0f),        // Station 17: Ramp lower
      new Waypoint( 220.0f,  -41.0f, -1072.0f),       // Station 18: Ramp foot
      new Waypoint( 218.0f,  -46.0f, -1121.0f)        // Station 19: Exit bottom into Cut 150
    },
    // Lane 2 (NPC 2 - Outer Lane)
    new Waypoint[]{
      new Waypoint(-239.0f,   22.0f, 1170.0f),        // Station 0: Catwalk approach
      new Waypoint(-193.0f,   16.5f, 1193.0f, true),  // Station 1: Red dot 1 (Log 1 takeoff)
      new Waypoint(  -9.0f,  -17.0f, 1076.0f),        // Station 2: Log 1 landing
      new Waypoint(  20.0f,  -27.0f, 1055.0f, true),  // Station 3: Red dot 2 (Log 2 takeoff)
      new Waypoint( 133.0f,  -66.0f,  963.0f),        // Station 4: Log 2 landing
      new Waypoint( 164.0f,  -75.0f,  934.0f),        // Station 5: Catwalk descending 1
      new Waypoint( 196.0f, -115.0f,  764.0f),        // Station 6: Catwalk descending 2
      new Waypoint( 226.0f, -155.0f,  598.0f),        // Station 7: Catwalk curve 1
      new Waypoint( 292.0f, -178.0f,  396.0f),        // Station 8: Catwalk curve 2
      new Waypoint( 390.0f, -206.0f,  241.0f),        // Station 9: Catwalk curve 3
      new Waypoint( 431.0f, -214.0f,  219.0f),        // Station 10: Catwalk curve 4
      new Waypoint( 477.0f, -210.0f,   64.0f),        // Station 11: Catwalk curve 5
      new Waypoint( 486.0f, -168.0f, -185.0f),        // Station 12: Catwalk into upper ramp
      new Waypoint( 454.0f, -127.0f, -387.0f),        // Station 13: Ramp upper
      new Waypoint( 434.0f, -101.0f, -527.0f),        // Station 14: Ramp mid
      new Waypoint( 395.0f,  -80.0f, -727.0f, false, true), // Station 15: Green Line Banner (Lap Finish!)
      new Waypoint( 340.0f,  -61.0f, -865.0f),        // Station 16: Ramp past banner
      new Waypoint( 270.0f,  -36.0f, -980.0f),        // Station 17: Ramp lower
      new Waypoint( 240.0f,  -41.0f, -1074.0f),       // Station 18: Ramp foot
      new Waypoint( 236.0f,  -46.0f, -1122.0f)        // Station 19: Exit bottom into Cut 150
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
      r.jumpProgress = 0;
      r.lastHurdleIndex = -1;
      r.currentAnimIndex = -1;
      r.jumpArcHeight = 22.0f;
      r.jumpSpeed = 0.075f;
    }

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

    assignRacerSobjs(smap);
    pauseNonRacerSobjs(smap);

    // Persist relative gap into this new cut
    lapCountedThisPass = false;
    for (final Racer r : racers) {
      r.pathProgress = r.sceneEntryProgress;
      r.lastHurdleIndex = -1;
      r.currentAnimIndex = -1;
      r.isJumping = false;
      r.jumpProgress = 0;
      r.jumpArcHeight = 22.0f;
      r.jumpSpeed = 0.075f;
      final Waypoint[] laneWaypoints = getLaneWaypoints(r.laneIndex);
      updateRacerPose(r, laneWaypoints, true);
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

  private static void pauseNonRacerSobjs(final SMap smap) {
    if (smap.sobjs_800c6880 == null) return;
    // Hide only the default roaming creature on track in Cut 151
    if (currentCut == 151) {
      if (smap.sobjs_800c6880.length > 7 && smap.sobjs_800c6880[7] != null) {
        smap.sobjs_800c6880[7].pause();
        smap.sobjs_800c6880[7].innerStruct_00.hidden_128 = true;
      }
    }
  }

  private static void resumeAllSobjs(final SMap smap) {
    if (smap.sobjs_800c6880 == null) return;
    // Restore default roaming creature in Cut 151
    if (smap.sobjs_800c6880.length > 7 && smap.sobjs_800c6880[7] != null) {
      smap.sobjs_800c6880[7].resume();
      smap.sobjs_800c6880[7].innerStruct_00.hidden_128 = false;
    }
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
        final Waypoint hw = waypoints[upcomingHurdle];
        final float dist3d = r.pos.distance(hw.x, hw.y, hw.z);
        final float progressDist = (float) upcomingHurdle - r.pathProgress;

        // In approach zone when approaching obstacle and within 95 world units
        final boolean inApproachZone = progressDist > -0.15f && dist3d < 95.0f && r.lastHurdleIndex != upcomingHurdle;

        // Show/hide the ! alert indicator
        setAlertIndicator(smap, PLAYER_SOBJ, inApproachZone);

        // Play the standard LoD prompt "boing" when ! first appears
        if (inApproachZone && !alertSoundPlayed) {
          playMenuSound(4); // Standard LoD interaction prompt sound
          alertSoundPlayed = true;
        } else if (!inApproachZone && progressDist > 0.5f) {
          // Reset for the next hurdle
          alertSoundPlayed = false;
        }

        // Player jump attempt: anytime the alert is active (or right up to takeoff), pressing interact registers a successful jump!
        if (actionJustPressed && !r.isJumping && r.lastHurdleIndex != upcomingHurdle &&
            (inApproachZone || (progressDist > -0.25f && dist3d < 100.0f))) {
          r.lastHurdleIndex = upcomingHurdle;
          executeJump(r, upcomingHurdle, true);
        }
      } else if (!r.isPlayer && upcomingHurdle != -1 && !r.isJumping) {
        // NPC hurdle jumping logic
        final Waypoint hw = waypoints[upcomingHurdle];
        final float dist3d = r.pos.distance(hw.x, hw.y, hw.z);
        final float progressDist = (float) upcomingHurdle - r.pathProgress;
        if (progressDist > -0.10f && dist3d < 50.0f && r.lastHurdleIndex != upcomingHurdle) {
          r.lastHurdleIndex = upcomingHurdle;
          final boolean success = Math.random() < (r.id == 0 ? 0.82 : 0.76);
          executeJump(r, upcomingHurdle, success);
        }
      }

      // Missed hurdle timeout (stumble) - only triggers if racer completely passed the obstacle without jumping
      if (upcomingHurdle != -1 && !r.isJumping && r.lastHurdleIndex != upcomingHurdle) {
        final float progressDist = (float) upcomingHurdle - r.pathProgress;
        if (progressDist < -0.15f) {
          r.lastHurdleIndex = upcomingHurdle;
          executeJump(r, upcomingHurdle, false); // Miss penalty
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

  private static void executeJump(final Racer r, final int hurdleIndex, final boolean goodTiming) {
    r.isJumping = true;
    r.jumpStartProgress = r.pathProgress;
    r.jumpEndProgress = hurdleIndex + 1.0f;
    r.jumpProgress = 0.0f;
    r.jumpSucceeded = goodTiming;

    // Water jump in Cut 150 or doorway gap in Cut 149 require a higher, grander arc
    final boolean isBigGap = (currentCut == 150) || (currentCut == 149 && hurdleIndex <= 4);
    r.jumpArcHeight = isBigGap ? (goodTiming ? 55.0f : 20.0f) : (goodTiming ? 28.0f : 12.0f);

    if (goodTiming) {
      r.boostTimer = 45; // Speed boost
      r.slowTimer = 0;
      if (r.isPlayer) {
        playMenuSound(2); // Standard LoD confirm/accept sound
        jumpFeedbackText = "PERFECT JUMP!";
        feedbackTicks = 40;
        alertSoundPlayed = false; // Reset for next hurdle
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
        dartSobj.model_00.coord2_14.coord.transfer.set(0.0f, 5000.0f, 0.0f);
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

    if (currentCut == 151) {
      // Already in Cut 151! Restore Dart directly in front of the booth and fade in
      restoreDart(smap);
      startFadeEffect(2, 15);
    } else {
      // Defer Dart restoration to onSubmapLoad when Cut 151 finishes loading
      pendingDartRestore = true;
      smap.mapTransition(151, 0);
    }
  }

  private static void restoreDart(final SMap smap) {
    LOGGER.info("LohanRaceManager: Restoring Dart in front of vendor booth.");
    // Despawn racer models completely: pause, hide, and park out of bounds
    for (int idx : new int[]{NPC1_SOBJ, PLAYER_SOBJ, NPC2_SOBJ}) {
      if (smap.sobjs_800c6880 != null && idx < smap.sobjs_800c6880.length && smap.sobjs_800c6880[idx] != null) {
        final ScriptState<SubmapObject210> rState = smap.sobjs_800c6880[idx];
        rState.pause();
        final SubmapObject210 rSobj = rState.innerStruct_00;
        rSobj.hidden_128 = true;
        rSobj.model_00.coord2_14.coord.transfer.set(0.0f, 5000.0f, 0.0f);
      }
    }

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

      // Resume all background and creature sobjs (default models load back in)
      resumeAllSobjs(smap);

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
