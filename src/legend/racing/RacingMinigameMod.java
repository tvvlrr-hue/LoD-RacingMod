package legend.racing;

import legend.game.modding.events.RenderEvent;
import legend.game.modding.events.submap.SubmapLoadEvent;
import legend.game.submap.RetailSubmap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.legendofdragoon.modloader.Mod;
import org.legendofdragoon.modloader.events.EventListener;

import static legend.core.GameEngine.EVENTS;

@Mod(id = RacingMinigameMod.MOD_ID, version = "^1.0.0")
public class RacingMinigameMod {
  public static final String MOD_ID = "racing_minigame_lohan";
  private static final Logger LOGGER = LogManager.getFormatterLogger(RacingMinigameMod.class);

  public RacingMinigameMod() {
    LOGGER.info("RacingMinigameMod initialized.");
    EVENTS.register(this);
  }

  @EventListener
  public void onSubmapLoad(final SubmapLoadEvent event) {
    if (event.getSubmap() instanceof RetailSubmap retail) {
      LohanRaceNpc.onSubmapLoad(event.getEngineState(), retail, event.submapObjects);
    }
  }

  @EventListener
  public void onRender(final RenderEvent event) {
    LohanRaceNpc.onRender();
  }
}
