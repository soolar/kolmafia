package net.sourceforge.kolmafia.textui.command;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import net.sourceforge.kolmafia.KoLConstants.MafiaState;
import net.sourceforge.kolmafia.KoLmafia;
import net.sourceforge.kolmafia.RequestThread;
import net.sourceforge.kolmafia.objectpool.ItemPool;
import net.sourceforge.kolmafia.preferences.Preferences;
import net.sourceforge.kolmafia.request.GenericRequest;
import net.sourceforge.kolmafia.session.InventoryManager;

public class MayamCommand extends AbstractCommand {
  private static final List<List<String>> SYMBOL_POSITIONS =
      List.of(
          List.of("yam", "sword", "vessel", "fur", "chair", "eye"),
          List.of("yam", "lightning", "bottle", "wood", "meat"),
          List.of("yam", "eyepatch", "cheese", "wall"),
          List.of("yam", "clock", "explosion"));

  private static final String SYMBOL_USAGE =
      SYMBOL_POSITIONS.stream()
          .map(ring -> "<" + String.join("|", ring) + ">")
          .collect(Collectors.joining(" "));

  public MayamCommand() {
    this.usage =
        " rings "
            + SYMBOL_USAGE
            + " - list exactly four symbols to consider from top to bottom (largest ring to smallest)";
  }

  private boolean lacksCalendar() {
    if (!InventoryManager.equippedOrInInventory(ItemPool.MAYAM_CALENDAR)) {
      KoLmafia.updateDisplay(MafiaState.ERROR, "You need a Mayam Calendar");
      return true;
    }
    return false;
  }

  @Override
  public void run(final String cmd, String parameters) {
    if (lacksCalendar()) return;

    String[] args = parameters.split(" ", 2);

    switch (args[0]) {
      case "rings" -> rings(args[1]);
      default -> KoLmafia.updateDisplay(
          MafiaState.ERROR, "Mayam command not recognised. Stop tzolk'in around.");
    }
  }

  private void rings(final String parameters) {
    String[] symbols = parameters.split(" ");

    if (symbols.length != 4) {
      KoLmafia.updateDisplay(MafiaState.ERROR, "You must supply exactly four symbols.");
      return;
    }

    use();

    var symbolsUsed = Arrays.asList(Preferences.getString("_mayamSymbolsUsed").split(","));

    int ring = 0;
    for (var symbol : symbols) {
      int fromTop = ring + 1;

      var isYam = symbol.equals("yam");
      var nameInPref = isYam ? symbol + fromTop : symbol;
      if (symbolsUsed.contains(nameInPref)) {
        KoLmafia.updateDisplay(
            MafiaState.ERROR,
            "You've already used the "
                + symbol
                + " symbol"
                + (isYam ? " in position " + fromTop : "")
                + ".");
        return;
      }

      int pos = SYMBOL_POSITIONS.get(ring).indexOf(symbol);

      if (pos < 0) {
        KoLmafia.updateDisplay(
            MafiaState.ERROR, "Cannot match symbol " + symbol + " on ring " + fromTop + ".");
        return;
      }

      spin(3 - ring, pos);
      ring++;
    }

    consider();

    KoLmafia.updateDisplay("Calendar considered.");
  }

  private void use() {
    GenericRequest useRequest = new GenericRequest("inv_use.php");
    useRequest.addFormField("pwd", GenericRequest.passwordHash);
    useRequest.addFormField("whichitem", String.valueOf(ItemPool.MAYAM_CALENDAR));
    RequestThread.postRequest(useRequest);
  }

  private void spin(int ring, int pos) {
    var request = new GenericRequest("choice.php");
    request.addFormField("whichchoice", "1527");
    request.addFormField("option", "2");
    request.addFormField("r", String.valueOf(ring));
    request.addFormField("p", String.valueOf(pos));
    request.addFormField("pwd", GenericRequest.passwordHash);
    RequestThread.postRequest(request);
  }

  private void consider() {
    var request = new GenericRequest("choice.php");
    request.addFormField("whichchoice", "1527");
    request.addFormField("option", "1");
    request.addFormField("pwd", GenericRequest.passwordHash);
    RequestThread.postRequest(request);
  }
}
