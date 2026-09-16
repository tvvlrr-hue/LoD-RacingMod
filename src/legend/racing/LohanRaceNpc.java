package legend.racing;

import legend.core.MathHelper;
import legend.game.EngineStates;
import legend.game.Text;
import legend.game.inventory.screens.TextColour;
import legend.game.scripting.ScriptFile;
import legend.game.scripting.ScriptState;
import legend.game.submap.RetailSubmap;
import legend.game.submap.SMap;
import legend.game.submap.SobjPos14;
import legend.game.submap.SubmapObject;
import legend.game.submap.SubmapObject210;
import legend.game.types.BackgroundType;
import legend.game.types.LodString;
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
import static legend.game.Models.loadModelStandardAnimation;
import static legend.game.Scus94491BpeSegment_800b.gameState_800babc8;
import static legend.game.Scus94491BpeSegment_800b.sobjPositions_800bd818;
import static legend.game.Text.calculateAppropriateTextboxBounds;
import static legend.game.Text.clearTextbox;
import static legend.game.Text.clearTextboxText;
import static legend.game.Text.setTextboxArrowPosition;
import static legend.game.Text.textboxes_800be358;
import static legend.game.Text.textboxText_800bdf38;
import static legend.lodmod.LodMod.INPUT_ACTION_SMAP_INTERACT;

public class LohanRaceNpc {
  private static final Logger LOGGER = LogManager.getFormatterLogger(LohanRaceNpc.class);

  public static final int LOHAN_CUT = 151;

  // Vendor position standing directly inside the very bottom right booth behind the counter
  public static final float NPC_POS_X = 260.0f;
  public static final float NPC_POS_Y = -4.0f;
  public static final float NPC_POS_Z = -790.0f;
  public static final float NPC_ROT_Y = 2.45f; // Facing northwest toward Dart across the counter (red arrow direction)

  // Interaction trigger zone in front of the bottom right booth's counter
  public static final float BOOTH_FRONT_X = 175.0f;
  public static final float BOOTH_FRONT_Z = -720.0f;
  public static final float INTERACT_RADIUS = 75.0f;

  public enum DialogueState {
    IDLE,
    GREETING,
    CHOICE
  }

  private static DialogueState state = DialogueState.IDLE;
  private static int npcSobjIndex = -1;
  private static int templateIndexUsed = -1;
  private static int cooldownTicks = 0;
  private static final Vector3f dartLockedPos = new Vector3f();
  private static ScriptState<SubmapObject210> pausedDartScript = null;

  public static void onSubmapLoad(final SMap smap, final RetailSubmap retail, final List<SubmapObject> objects) {
    if (retail.cut != LOHAN_CUT) {
      if (pausedDartScript != null) {
        pausedDartScript.resume();
        pausedDartScript = null;
      }
      npcSobjIndex = -1;
      state = DialogueState.IDLE;
      return;
    }

    try {
      final int templateIndex = Math.min(5, objects.size() - 1);
      final SubmapObject template = objects.get(templateIndex);

      final SubmapObject npcObj = new SubmapObject();
      npcObj.constructor = SubmapObject210::new;
      npcObj.model = template.model;
      npcObj.animations.addAll(template.animations);
      // Minimal LoD bytecode (yield: 0, rewind: 1)
      npcObj.script = new ScriptFile("Lohan Race NPC Script", new byte[]{0, 0, 0, 0, 1, 0, 0, 0});

      final int newIndex = objects.size();
      if (newIndex < smap.sobjs_800c6880.length) {
        objects.add(npcObj);
        retail.uvAdjustments.add(retail.uvAdjustments.get(templateIndex));

        final SobjPos14 pos = sobjPositions_800bd818[newIndex];
        pos.pos_00.set(NPC_POS_X, NPC_POS_Y, NPC_POS_Z);
        pos.rot_0c.set(0.0f, NPC_ROT_Y, 0.0f);

        npcSobjIndex = newIndex;
        templateIndexUsed = templateIndex;
        state = DialogueState.IDLE;
        cooldownTicks = 30;
        LOGGER.info("LohanRaceNpc: Spawned Race Minigame NPC at index %d (cut %d, template %d)", newIndex, LOHAN_CUT, templateIndex);
      } else {
        LOGGER.warn("LohanRaceNpc: Cannot spawn NPC, sobjs array limit reached (%d)", newIndex);
      }
    } catch (final Throwable t) {
      LOGGER.error("LohanRaceNpc: Failed to spawn NPC in Cut %d", LOHAN_CUT, t);
    }
  }

  public static void onRender() {
    if (!(EngineStates.currentEngineState_8004dd04 instanceof SMap smap)) {
      if (pausedDartScript != null) {
        pausedDartScript.resume();
        pausedDartScript = null;
      }
      state = DialogueState.IDLE;
      return;
    }

    if (!(smap.submap instanceof RetailSubmap retail) || retail.cut != LOHAN_CUT) {
      if (pausedDartScript != null) {
        pausedDartScript.resume();
        pausedDartScript = null;
      }
      state = DialogueState.IDLE;
      return;
    }

    if (cooldownTicks > 0) {
      cooldownTicks--;
    }

    if (npcSobjIndex < 0 || npcSobjIndex >= smap.sobjs_800c6880.length) {
      return;
    }

    final ScriptState<SubmapObject210> npcState = smap.sobjs_800c6880[npcSobjIndex];
    final ScriptState<SubmapObject210> dartState = smap.sobjs_800c6880[0];
    if (npcState == null || dartState == null) {
      return;
    }

    final SubmapObject210 npcSobj = npcState.innerStruct_00;
    final SubmapObject210 dartSobj = dartState.innerStruct_00;

    // Ensure NPC stays behind this counter
    npcSobj.hidden_128 = false;
    npcSobj.showAlertIndicator_194 = false; // Never show interact button / alert icon
    npcSobj.model_00.coord2_14.coord.transfer.set(NPC_POS_X, NPC_POS_Y, NPC_POS_Z);

    if (templateIndexUsed >= 0 && templateIndexUsed < retail.uvAdjustments.size()) {
      npcSobj.model_00.uvAdjustments_9d = retail.uvAdjustments.get(templateIndexUsed);
    }

    final Vector3f dartPos = dartSobj.model_00.coord2_14.coord.transfer;
    final float dx = dartPos.x - BOOTH_FRONT_X;
    final float dz = dartPos.z - BOOTH_FRONT_Z;
    final float distSq = dx * dx + dz * dz;
    final boolean isNear = distSq < (INTERACT_RADIUS * INTERACT_RADIUS);

    // Check if Dart is facing towards the booth/vendor
    final float angleToBooth = MathHelper.positiveAtan2(NPC_POS_Z - dartPos.z, NPC_POS_X - dartPos.x);
    final float dartRotY = dartSobj.model_00.coord2_14.transforms.rotate.y;
    final boolean isFacingBooth = angleDifference(dartRotY, angleToBooth) < 1.3f;

    if (state == DialogueState.IDLE) {
      npcSobj.model_00.coord2_14.transforms.rotate.y = NPC_ROT_Y;
    } else {
      // Lock Dart's position and turn both characters to face each other across the counter
      dartSobj.model_00.coord2_14.coord.transfer.set(dartLockedPos);
      dartSobj.movementStep_148.zero();
      dartSobj.interpMovementTicks = 0;
      dartSobj.interpMovementTicksTotal = 0;
      dartSobj.movementTicks_144 = 0;
      dartSobj.movementType_170 = 0;
      dartSobj.animIndex_132 = 0;

      // Dart faces the vendor
      final float dartFacingAngle =
          MathHelper.positiveAtan2(NPC_POS_Z - dartLockedPos.z, NPC_POS_X - dartLockedPos.x);
      dartSobj.model_00.coord2_14.transforms.rotate.y = dartFacingAngle;
      dartSobj.interpRotationTicksTotalY = 0;
      dartSobj.rotationFrames_188 = 0;

      // Vendor faces Dart
      npcSobj.model_00.coord2_14.transforms.rotate.y =
          MathHelper.positiveAtan2(dartLockedPos.z - NPC_POS_Z, dartLockedPos.x - NPC_POS_X);
    }

    // Handle dialogue progression
    switch (state) {
      case IDLE -> {
        if (isNear && isFacingBooth && cooldownTicks == 0 && PLATFORM.isActionPressed(INPUT_ACTION_SMAP_INTERACT.get())) {
          LOGGER.info("LohanRaceNpc: Dart interacting at (%.1f, %.1f, %.1f) facing Race Booth counter",
              dartPos.x, dartPos.y, dartPos.z);
          startDialogue(smap, dartState, dartSobj);
        }
      }

      case GREETING -> {
        final TextboxText84 tbText0 = textboxText_800bdf38[0];
        // Advance characters
        if (tbText0.state_00 == TextboxTextState.PROCESS_TEXT_4 && tbText0.charIndex_30 < tbText0.str_24.length()) {
          Text.processTextboxCharacter(0);
          if (tbText0.charIndex_30 >= tbText0.str_24.length() - 1) {
            setTextboxArrowPosition(0, true);
          }
        }

        // Wait for player confirm
        if (PLATFORM.isActionPressed(INPUT_ACTION_SMAP_INTERACT.get()) && cooldownTicks == 0) {
          if (tbText0.state_00 != TextboxTextState.WAIT_FOR_INPUT_ADVANCED_DIRTY_BOX_6
              && tbText0.state_00 != TextboxTextState.CLOSE_TEXTBOX_15
              && tbText0.charIndex_30 < tbText0.str_24.length()) {
            // Fast-forward text
            while (tbText0.charIndex_30 < tbText0.str_24.length()
                && tbText0.state_00 != TextboxTextState.WAIT_FOR_INPUT_ADVANCED_DIRTY_BOX_6
                && tbText0.state_00 != TextboxTextState.CLOSE_TEXTBOX_15) {
              Text.processTextboxCharacter(0);
            }
            setTextboxArrowPosition(0, true);
            cooldownTicks = 10;
          } else {
            showChoiceMenu();
          }
        }
      }

      case CHOICE -> {
        final TextboxText84 tbText0 = textboxText_800bdf38[0];
        final int selection = tbText0.selectionIndex_6c;

        if (selection >= 0) {
          // Player selected an option: 0 = "No, thank you.", 1 = "Let's try."
          handleSelectionResult(selection);
        }
      }
    }
  }

  private static void startDialogue(final SMap smap, final ScriptState<SubmapObject210> dartState, final SubmapObject210 dartSobj) {
    dartLockedPos.set(dartSobj.model_00.coord2_14.coord.transfer);
    state = DialogueState.GREETING;
    cooldownTicks = 15;
    pausedDartScript = dartState;
    dartState.pause();

    // Immediately stop running and set idle animation
    dartSobj.animIndex_132 = 0;
    if (smap.submap != null && !smap.submap.objects.isEmpty() && dartSobj.sobjIndex_12e < smap.submap.objects.size()) {
      final SubmapObject playerObj = smap.submap.objects.get(dartSobj.sobjIndex_12e);
      if (!playerObj.animations.isEmpty()) {
        loadModelStandardAnimation(dartSobj.model_00, playerObj.animations.get(0));
      }
    }

    dartSobj.movementStep_148.zero();
    dartSobj.interpMovementTicks = 0;
    dartSobj.interpMovementTicksTotal = 0;
    dartSobj.interpRotationTicksTotalY = 0;
    dartSobj.rotationFrames_188 = 0;

    final float dartFacingAngle =
        MathHelper.positiveAtan2(NPC_POS_Z - dartLockedPos.z, NPC_POS_X - dartLockedPos.x);
    dartSobj.model_00.coord2_14.transforms.rotate.y = dartFacingAngle;

    // Greeting Dialogue (Matching retail vendor style)
    final String title = "Racing Minigame";
    final String body =
      "Would you like to play the\n" +
      "Race minigame? You can\n" +
      "play one game per ticket.";

    openNamedTextbox(0, 160, 165, 30, 4, title, body);
    LOGGER.info("LohanRaceNpc: Started greeting dialogue.");
  }

  private static void showChoiceMenu() {
    state = DialogueState.CHOICE;
    cooldownTicks = 15;

    final int tickets = getHeroTickets();

    // Top box: "Ticket remaining   <count>"
    openSimpleTextbox(1, 160, 110, 24, 1, "Ticket remaining   " + tickets);

    // Bottom box: Title "Dart", choices without quotation marks
    final String title = "Dart";
    final String choices =
      "No, thank you.\n" +
      "Let's try.";

    openNamedTextbox(0, 160, 175, 28, 3, title, choices);

    // Set selection: Line 1 = "No, thank you.", Line 2 = "Let's try."
    final TextboxText84 tbText0 = textboxText_800bdf38[0];
    while (tbText0.charIndex_30 < tbText0.str_24.length()
        && tbText0.state_00 != TextboxTextState.CLOSE_TEXTBOX_15) {
      Text.processTextboxCharacter(0);
    }
    tbText0.flags_08 |= TextboxText84.SELECTION;
    tbText0.minSelectionLine_72 = 1;
    tbText0.maxSelectionLine_70 = 2;
    tbText0.selectionLine_68 = 1; // Default cursor on "No, thank you."
    tbText0.selectionIndex_6c = -1;

    // Transition timer gives a brief delay so the player's button press from the greeting
    // doesn't immediately confirm the selection
    tbText0.ticksUntilStateTransition_64 = 10;
    tbText0.stateAfterTransition_78 = TextboxTextState.SELECTION_22;
    tbText0.state_00 = TextboxTextState.TRANSITION_AFTER_TIMEOUT_23;

    LOGGER.info("LohanRaceNpc: Opened choice menu (tickets=%d).", tickets);
  }

  private static void handleSelectionResult(final int selectedLine) {
    if (selectedLine == 0) {
      LOGGER.info("LohanRaceNpc: Player chose 'No, thank you.'.");
    } else {
      LOGGER.info("LohanRaceNpc: Player chose 'Let's try.'.");
    }
    // Accepting the minigame ends the convo cleanly like saying no thanks
    closeDialogue();
  }

  private static void closeDialogue() {
    state = DialogueState.IDLE;
    cooldownTicks = 20;

    if (pausedDartScript != null) {
      pausedDartScript.resume();
      pausedDartScript = null;
    }

    safelyClearTextbox(0);
    safelyClearTextbox(1);

    LOGGER.info("LohanRaceNpc: Dialogue closed.");
  }

  private static void safelyClearTextbox(final int index) {
    try {
      clearTextbox(index);
      clearTextboxText(index);

      final TextboxText84 tbText = textboxText_800bdf38[index];
      tbText.state_00 = TextboxTextState.UNINITIALIZED_0;
      tbText.chars_58 = null;

      final Textbox4c tb = textboxes_800be358[index];
      tb.state_00 = TextboxState.UNINITIALIZED_0;
      tb.flags_08 = 0;
      tb.width_1c = 0;
      tb.height_1e = 0;
      tb.oldW = 0;
      tb.oldH = 0;
    } catch (final Throwable ignored) {}
  }

  private static int getHeroTickets() {
    try {
      if (gameState_800babc8 != null && gameState_800babc8.scriptData_08 != null) {
        return gameState_800babc8.scriptData_08[27];
      }
    } catch (final Throwable ignored) {}
    return 0;
  }

  private static void openNamedTextbox(final int index, final int x, final int y, final int chars, final int lines, final String name, final String text) {
    safelyClearTextbox(index);

    final Textbox4c textbox = textboxes_800be358[index];
    final TextboxText84 textboxText = textboxText_800bdf38[index];

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

    textboxText.type_04 = TextboxType.SMAP_NAMED.id;
    textboxText.flags_08 = TextboxText84.HAS_NAME | TextboxText84.SHOW_ARROW;
    textboxText.str_24 = buildNamedLodString(name, text);
    textboxText.chars_1c = textbox.chars_18 - 1;
    textboxText.lines_1e = lines;
    textboxText.chars_58 = new TextboxChar08[textboxText.chars_1c * (textboxText.lines_1e + 1)];
    Arrays.setAll(textboxText.chars_58, i -> new TextboxChar08());
    textboxText.charIndex_30 = 0;
    textboxText.charX_34 = 0;
    textboxText.charY_36 = 0;
    textboxText.state_00 = TextboxTextState.PROCESS_TEXT_4;

    calculateAppropriateTextboxBounds(index, x, y);
  }

  private static void openSimpleTextbox(final int index, final int x, final int y, final int chars, final int lines, final String text) {
    safelyClearTextbox(index);

    final Textbox4c textbox = textboxes_800be358[index];
    final TextboxText84 textboxText = textboxText_800bdf38[index];

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

    calculateAppropriateTextboxBounds(index, x, y);

    // Step through all characters using the engine so chars_58 is populated with font glyphs
    while (textboxText.charIndex_30 < textboxText.str_24.length()
        && textboxText.state_00 != TextboxTextState.CLOSE_TEXTBOX_15) {
      Text.processTextboxCharacter(index);
    }
    // Set to passive wait state so it does NOT close or consume inputs
    textboxText.state_00 = TextboxTextState.CLOSE_BATTLE_NO_INPUT_16;
  }

  private static LodString buildNamedLodString(final String name, final String text) {
    final List<Integer> list = new ArrayList<>();
    // Yellow name on line 0
    for (int i = 0; i < name.length(); i++) {
      list.add(LodString.toLodChar(name.charAt(i)));
    }
    list.add(0xa1ff); // newline

    // Body lines
    for (int i = 0; i < text.length(); i++) {
      final char c = text.charAt(i);
      if (c == '\n') {
        list.add(0xa1ff);
      } else {
        list.add(LodString.toLodChar(c));
      }
    }
    list.add(0xa0ff); // end

    final int[] arr = new int[list.size()];
    for (int i = 0; i < list.size(); i++) {
      arr[i] = list.get(i);
    }
    return new LodString(arr);
  }

  private static float angleDifference(final float a, final float b) {
    float diff = (a - b) % ((float) Math.PI * 2.0f);
    if (diff < -Math.PI) diff += (float) Math.PI * 2.0f;
    if (diff > Math.PI) diff -= (float) Math.PI * 2.0f;
    return Math.abs(diff);
  }
}
