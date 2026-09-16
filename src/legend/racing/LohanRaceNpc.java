package legend.racing;

import legend.core.MathHelper;
import legend.core.renderer.Translucency;
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
import legend.game.types.Model124;
import legend.game.types.Textbox4c;
import legend.game.types.TextboxChar08;
import legend.game.types.TextboxState;
import legend.game.types.TextboxText84;
import legend.game.types.TextboxTextState;
import legend.game.types.TextboxType;
import legend.game.tmd.UvAdjustmentMetrics14;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static legend.core.GameEngine.PLATFORM;
import static legend.game.Scus94491BpeSegment_800b.gameState_800babc8;
import static legend.game.Scus94491BpeSegment_800b.sobjPositions_800bd818;
import static legend.game.Text.calculateAppropriateTextboxBounds;
import static legend.game.Text.clearTextbox;
import static legend.game.Text.clearTextboxText;
import static legend.game.Text.textboxes_800be358;
import static legend.game.Text.textboxText_800bdf38;
import static legend.lodmod.LodMod.INPUT_ACTION_SMAP_INTERACT;

public class LohanRaceNpc {
  private static final Logger LOGGER = LogManager.getFormatterLogger(LohanRaceNpc.class);

  public static final int LOHAN_CUT = 151;

  // NPC position behind booth counter facing towards Dart (northwest)
  public static final float NPC_POS_X = 360.0f;
  public static final float NPC_POS_Y = -4.0f;
  public static final float NPC_POS_Z = 160.0f;
  public static final float NPC_ROT_Y = 3.92f; // ~225 degrees in radians

  // Player interaction trigger area in front of the counter
  public static final float BOOTH_FRONT_X = 280.0f;
  public static final float BOOTH_FRONT_Z = 200.0f;
  public static final float INTERACT_RADIUS = 75.0f;

  public enum DialogueState {
    IDLE,
    GREETING,
    CHOICE,
    OUTCOME
  }

  private static DialogueState state = DialogueState.IDLE;
  private static int npcSobjIndex = -1;
  private static int cooldownTicks = 0;

  public static void onSubmapLoad(final SMap smap, final RetailSubmap retail, final List<SubmapObject> objects) {
    if (retail.cut != LOHAN_CUT) {
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
        state = DialogueState.IDLE;
        cooldownTicks = 30;
        LOGGER.info("LohanRaceNpc: Spawned Race Minigame NPC at index %d (cut %d)", newIndex, LOHAN_CUT);
      } else {
        LOGGER.warn("LohanRaceNpc: Cannot spawn NPC, sobjs array limit reached (%d)", newIndex);
      }
    } catch (final Throwable t) {
      LOGGER.error("LohanRaceNpc: Failed to spawn NPC in Cut %d", LOHAN_CUT, t);
    }
  }

  public static void onRender() {
    if (!(EngineStates.currentEngineState_8004dd04 instanceof SMap smap)) {
      state = DialogueState.IDLE;
      return;
    }

    if (!(smap.submap instanceof RetailSubmap retail) || retail.cut != LOHAN_CUT) {
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

    // Ensure NPC stays in position behind the counter
    npcSobj.model_00.coord2_14.coord.transfer.set(NPC_POS_X, NPC_POS_Y, NPC_POS_Z);

    final Vector3f dartPos = dartSobj.model_00.coord2_14.coord.transfer;
    final float dx = dartPos.x - BOOTH_FRONT_X;
    final float dz = dartPos.z - BOOTH_FRONT_Z;
    final float distSq = dx * dx + dz * dz;
    final boolean isNear = distSq < (INTERACT_RADIUS * INTERACT_RADIUS);

    // Show alert '!' indicator when player is in front of the counter
    if (state == DialogueState.IDLE) {
      npcSobj.showAlertIndicator_194 = isNear;
      npcSobj.alertIndicatorOffsetY_198 = 80;
      npcSobj.model_00.coord2_14.transforms.rotate.y = NPC_ROT_Y;
    } else {
      npcSobj.showAlertIndicator_194 = false;
      // Turn NPC to face Dart during conversation
      npcSobj.model_00.coord2_14.transforms.rotate.y = MathHelper.positiveAtan2(dartPos.z - NPC_POS_Z, dartPos.x - NPC_POS_X);
      // Freeze Dart during conversation
      dartSobj.movementType_170 = 0;
    }

    // Handle dialogue progression
    switch (state) {
      case IDLE -> {
        if (isNear && cooldownTicks == 0 && PLATFORM.isActionPressed(INPUT_ACTION_SMAP_INTERACT.get())) {
          startDialogue();
        }
      }

      case GREETING -> {
        final TextboxText84 tbText0 = textboxText_800bdf38[0];
        // Advance characters
        if (tbText0.state_00 == TextboxTextState.PROCESS_TEXT_4 && tbText0.charIndex_30 < tbText0.str_24.length()) {
          Text.processTextboxCharacter(0);
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
          // Player selected an option!
          handleSelectionResult(selection);
        }
      }

      case OUTCOME -> {
        final TextboxText84 tbText0 = textboxText_800bdf38[0];
        if (tbText0.state_00 == TextboxTextState.PROCESS_TEXT_4 && tbText0.charIndex_30 < tbText0.str_24.length()) {
          Text.processTextboxCharacter(0);
        }

        if (PLATFORM.isActionPressed(INPUT_ACTION_SMAP_INTERACT.get()) && cooldownTicks == 0) {
          closeDialogue();
        }
      }
    }
  }

  private static void startDialogue() {
    state = DialogueState.GREETING;
    cooldownTicks = 15;

    // Greeting Dialogue (Matching Image 2)
    final String title = "Racing Minigame";
    final String body =
      "Would you like to play the\n" +
      "Race minigame? You can\n" +
      "play one game per ticket.";

    openNamedTextbox(0, 160, 165, 32, 4, title, body);
    LOGGER.info("LohanRaceNpc: Started greeting dialogue.");
  }

  private static void showChoiceMenu() {
    state = DialogueState.CHOICE;
    cooldownTicks = 15;

    final int tickets = getHeroTickets();

    // Top box (Image 3 style): "Ticket remaining  <count>"
    openSimpleTextbox(1, 160, 110, 24, 1, "Ticket remaining  " + tickets);

    // Bottom box (Image 3 style): Title "Dart", choices "No, thank you." and "Let's try."
    final String title = "Dart";
    final String choices =
      "\"No, thank you.\"\n" +
      "\"Let's try.\"";

    openNamedTextbox(0, 160, 175, 30, 3, title, choices);

    // Set selection: Line 1 = "No, thank you.", Line 2 = "Let's try."
    final TextboxText84 tbText0 = textboxText_800bdf38[0];
    while (tbText0.charIndex_30 < tbText0.str_24.length()) {
      Text.processTextboxCharacter(0);
    }
    tbText0.state_00 = TextboxTextState.SELECTION_22;
    tbText0.flags_08 |= TextboxText84.SELECTION;
    tbText0.minSelectionLine_72 = 1;
    tbText0.maxSelectionLine_70 = 2;
    tbText0.selectionLine_68 = 1; // Default cursor on "No, thank you."
    tbText0.selectionIndex_6c = -1;

    LOGGER.info("LohanRaceNpc: Opened choice menu (tickets=%d).", tickets);
  }

  private static void handleSelectionResult(final int selectedLine) {
    clearTextbox(1);
    clearTextboxText(1);

    if (selectedLine == 1) {
      // "No, thank you." chosen
      LOGGER.info("LohanRaceNpc: Player chose 'No, thank you.'.");
      closeDialogue();
    } else if (selectedLine == 2) {
      // "Let's try." chosen
      final int tickets = getHeroTickets();
      LOGGER.info("LohanRaceNpc: Player chose 'Let's try.' (tickets=%d).", tickets);

      if (tickets <= 0) {
        state = DialogueState.OUTCOME;
        cooldownTicks = 15;
        openNamedTextbox(0, 160, 165, 28, 2, "Dart", "I, I have no ticket.");
      } else {
        state = DialogueState.OUTCOME;
        cooldownTicks = 15;
        openNamedTextbox(0, 160, 165, 28, 2, "Racing Minigame", "Let's begin!");
      }
    }
  }

  private static void closeDialogue() {
    state = DialogueState.IDLE;
    cooldownTicks = 20;

    clearTextbox(0);
    clearTextboxText(0);
    clearTextbox(1);
    clearTextboxText(1);

    LOGGER.info("LohanRaceNpc: Dialogue closed.");
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
    clearTextbox(index);
    clearTextboxText(index);

    final Textbox4c textbox = textboxes_800be358[index];
    final TextboxText84 textboxText = textboxText_800bdf38[index];

    textbox.backgroundType_04 = BackgroundType.NORMAL;
    textbox.renderBorder_06 = true;
    textbox.x_14 = x;
    textbox.y_16 = y;
    textbox.chars_18 = chars + 1;
    textbox.lines_1a = lines + 1;
    textbox.width_1c = textbox.chars_18 * 9 / 2;
    textbox.height_1e = textbox.lines_1a * 6;

    textboxText.type_04 = TextboxType.SMAP_NAMED.id;
    textboxText.flags_08 |= TextboxText84.HAS_NAME | TextboxText84.SHOW_ARROW;
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
    clearTextbox(index);
    clearTextboxText(index);

    final Textbox4c textbox = textboxes_800be358[index];
    final TextboxText84 textboxText = textboxText_800bdf38[index];

    textbox.backgroundType_04 = BackgroundType.NORMAL;
    textbox.renderBorder_06 = true;
    textbox.x_14 = x;
    textbox.y_16 = y;
    textbox.chars_18 = chars + 1;
    textbox.lines_1a = lines + 1;
    textbox.width_1c = textbox.chars_18 * 9 / 2;
    textbox.height_1e = textbox.lines_1a * 6;

    textboxText.type_04 = TextboxType.SIMPLE.id;
    textboxText.flags_08 = 0;
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

    // Fast process all characters for simple static textbox
    while (textboxText.charIndex_30 < textboxText.str_24.length()) {
      Text.processTextboxCharacter(index);
    }
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
}
