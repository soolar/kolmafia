package net.sourceforge.kolmafia.webui;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.sourceforge.kolmafia.AdventureResult;
import net.sourceforge.kolmafia.AscensionClass;
import net.sourceforge.kolmafia.KoLCharacter;
import net.sourceforge.kolmafia.KoLConstants.ConsumptionType;
import net.sourceforge.kolmafia.KoLConstants.CraftingRequirements;
import net.sourceforge.kolmafia.KoLConstants.CraftingType;
import net.sourceforge.kolmafia.Modifiers;
import net.sourceforge.kolmafia.Speculation;
import net.sourceforge.kolmafia.equipment.Slot;
import net.sourceforge.kolmafia.modifiers.DoubleModifier;
import net.sourceforge.kolmafia.modifiers.MultiStringModifier;
import net.sourceforge.kolmafia.objectpool.EffectPool;
import net.sourceforge.kolmafia.objectpool.ItemPool;
import net.sourceforge.kolmafia.objectpool.SkillPool;
import net.sourceforge.kolmafia.persistence.ConcoctionDatabase;
import net.sourceforge.kolmafia.persistence.ConsumablesDatabase;
import net.sourceforge.kolmafia.persistence.EffectDatabase;
import net.sourceforge.kolmafia.persistence.EquipmentDatabase;
import net.sourceforge.kolmafia.persistence.ItemDatabase;
import net.sourceforge.kolmafia.persistence.ModifierDatabase;
import net.sourceforge.kolmafia.persistence.QuestDatabase;
import net.sourceforge.kolmafia.persistence.QuestDatabase.Quest;
import net.sourceforge.kolmafia.preferences.Preferences;
import net.sourceforge.kolmafia.request.CampgroundRequest;
import net.sourceforge.kolmafia.request.EquipmentRequest;
import net.sourceforge.kolmafia.request.FightRequest;
import net.sourceforge.kolmafia.request.GenericRequest;
import net.sourceforge.kolmafia.request.IslandRequest;
import net.sourceforge.kolmafia.request.OrcChasmRequest;
import net.sourceforge.kolmafia.request.UseItemRequest;
import net.sourceforge.kolmafia.request.concoction.CreateItemRequest;
import net.sourceforge.kolmafia.session.ChoiceManager;
import net.sourceforge.kolmafia.session.EquipmentManager;
import net.sourceforge.kolmafia.session.InventoryManager;
import net.sourceforge.kolmafia.session.LimitMode;
import net.sourceforge.kolmafia.session.ResultProcessor;
import net.sourceforge.kolmafia.session.TurnCounter;
import net.sourceforge.kolmafia.textui.command.SpeculateCommand;
import net.sourceforge.kolmafia.utilities.StringUtilities;

@SuppressWarnings("incomplete-switch")
public abstract class UseLinkDecorator {
  private static final StringBuffer deferred = new StringBuffer();

  public static final void decorate(final String location, final StringBuffer buffer) {
    // You ain't doin' nothin' in Valhalla
    if (location.startsWith("afterlife.php")) {
      return;
    }

    // If you are Ed in the Underworld, you have to wait until you
    // are on the surface again before you use anything
    var limitmode = KoLCharacter.getLimitMode();
    if (limitmode == LimitMode.ED) {
      return;
    }

    // You CAN buy from the mall in Hardcore or Ronin, but any
    // results go into Hagnk's storage.
    if ((location.startsWith("mallstore.php") || location.startsWith("backoffice.php"))
        && !KoLCharacter.canInteract()) {
      return;
    }

    boolean inCombat = location.startsWith("fight.php");
    boolean inChoice = location.startsWith("choice.php");
    if (!inCombat
        && !inChoice
        && buffer.indexOf("You acquire") == -1
        && buffer.indexOf("O hai, I made dis") == -1) {
      return;
    }

    // Defer use links until later if this isn't the final combat/choice page
    String macro = FightRequest.lastMacroUsed;
    boolean usedNativeMacro = macro != null && !macro.equals("") && !macro.equals("0");
    boolean usedMafiaMacro = location.contains("action=done");
    boolean usedMacro = inCombat && (usedNativeMacro || usedMafiaMacro);

    // Some combats lead to a non-optional fight or choice
    boolean duringCombat =
        inCombat
            && (FightRequest.getCurrentRound() != 0
                || FightRequest.inMultiFight
                || FightRequest.choiceFollowsFight);
    // Some choices lead to a non-optional choice
    boolean duringChoice =
        inChoice && buffer.indexOf("choice.php") != -1 && !ChoiceManager.canWalkAway();

    // If we are currently in a combat or choice, we should consider deferring
    boolean deferrable = inCombat || inChoice;

    // If we are forced to continue to be in a combat or choice, continue deferring
    boolean deferring = duringCombat || duringChoice;

    String text = buffer.toString();
    buffer.setLength(0);

    boolean poetry = inCombat && (FightRequest.haiku || FightRequest.anapest);

    if (poetry) {
      UseLinkDecorator.addPoeticUseLinks(location, text, buffer, deferring);
    } else {
      UseLinkDecorator.addNormalUseLinks(location, text, buffer, deferring, usedMacro);
    }

    if (inCombat) {
      String find = "A sticker falls off your weapon, faded and torn.";
      String replace = find + " <font size=1>[<a href=\"bedazzle.php\">bedazzle</a>]</font>";
      StringUtilities.singleStringReplace(buffer, find, replace);
      // *** If we are deferring, should add this to UseLinkDecorator.deferred
    }

    // System.out.println( "inCombat = " + inCombat + " duringCombat = " + duringCombat + " inChoice
    // = " + inChoice + " duringChoice = " + duringChoice );
    // if ( inChoice ) System.out.println( "choice = " + ChoiceManager.lastChoice + " contains
    // choice.php = " + ( buffer.indexOf( "choice.php" ) != -1 ) + " not can walk away = " +
    // !ChoiceManager.canWalkAway() );
    // System.out.println( "deferring = " + deferring + " deferrable = " + deferrable + " buffer
    // length = " + deferred.length() );

    // If we are currently in combat or a choice, discard all
    // changes, since the links aren't usable yet
    if (deferring) {
      buffer.setLength(0);
      buffer.append(text);
      return;
    }

    // If we have completed a combat or are in a choice that we can
    // walk away, the links are immediately usable.
    if (deferrable) {
      int pos = buffer.lastIndexOf("</table>");
      if (pos == -1) {
        return;
      }
      text = buffer.substring(pos);
      buffer.setLength(pos);
      if (inCombat) {
        buffer.append("</table><table><tr><td>");
        if (usedNativeMacro) {
          buffer.append(
              "<form method=POST action=\"account_combatmacros.php\"><input type=HIDDEN name=action value=edit><input type=HIDDEN name=macroid value=\"");
          buffer.append(macro);
          buffer.append("\"><input type=SUBMIT value=\"Edit last macro\"></form>");
          FightRequest.lastMacroUsed = "";
        } else {
          buffer.append("[<a href=\"/account_combatmacros.php\">edit macros</a>]");
        }
        buffer.append("</td></tr>");
      }

      if (UseLinkDecorator.deferred.length() > 0) {
        String tag = inCombat ? "Found in this fight" : "Previously seen";
        buffer.append("</table><table><tr><td colspan=2>");
        buffer.append(tag);
        buffer.append(":</td></tr>");
        buffer.append(UseLinkDecorator.deferred);
        UseLinkDecorator.deferred.setLength(0);
      }

      buffer.append(text);
    }
  }

  // <table class="item" style="float: none"
  // rel="id=2334&s=0&q=1&d=0&g=0&t=0&n=1&m=0&p=0&u=."><tr><td><img
  // src="http://images.kingdomofloathing.com/itemimages/macguffin.gif" alt="Holy MacGuffin"
  // title="Holy MacGuffin" class=hand onClick='descitem(302128482)'></td><td valign=center
  // class=effect>You acquire an item: <b>Holy MacGuffin</b></td></tr></table>

  private static final Pattern ACQUIRE_PATTERN =
      Pattern.compile(
          "(You acquire|O hai, I made dis)([^<]*?)<b>(.*?)</b>(.*?)</td>", Pattern.DOTALL);

  private static final Pattern BOUNTY_COUNT_PATTERN =
      Pattern.compile("\\((\\d+) of (\\d+) found\\)");

  private static void addNormalUseLinks(
      String location, String text, StringBuffer buffer, boolean deferring, boolean usedMacro) {
    // Get a list of items via "rel" strings
    LinkedList<AdventureResult> items = ResultProcessor.parseItems(text);

    // Get a list of effects via descid
    LinkedList<AdventureResult> effects = ResultProcessor.parseEffects(text);

    Matcher useLinkMatcher = ACQUIRE_PATTERN.matcher(text);

    int specialLinkId = 0;
    String specialLinkText = null;

    while (useLinkMatcher.find()) {
      // See if it's an effect
      if (UseLinkDecorator.addEffectLink(location, useLinkMatcher, buffer, effects)) {
        continue;
      }

      // Get type of acquisition
      String type = useLinkMatcher.group(2);
      int pos = buffer.length();
      boolean link = false;
      String comment = useLinkMatcher.group(4);

      if (comment.contains("Hagnk") || comment.contains("automatically equipped")) {
        // If the item ended up in Hagnk's storage or
        // was automatically equipped, no use link.
        continue;
      }

      // Special handling for bounty items
      if (type.contains("bounty item")) {
        // Add link for visiting bounty hunter if the last bounty drops
        Matcher matcher = UseLinkDecorator.BOUNTY_COUNT_PATTERN.matcher(text);
        if (matcher.find()) {
          String bountyCount = matcher.group(1);
          String bountyTotal = matcher.group(2);
          if (bountyCount.equals(bountyTotal)) {
            UseLink useLink = new UseLink("return to hunter", "bounty.php");
            useLinkMatcher.appendReplacement(
                buffer, "$1$2<b>$3</b> " + useLink.getItemHTML() + "$4");
            link = true;
          }
        }
      } else {
        int itemId = -1;
        int itemCount = 1;

        AdventureResult item = items.size() == 0 ? null : items.getFirst();

        if (item != null) {
          items.removeFirst();
          itemId = item.getItemId();
          itemCount = item.getCount();
        } else {
          String itemName = useLinkMatcher.group(3);
          int spaceIndex = itemName.indexOf(" ");
          if (spaceIndex != -1 && !type.contains(":")) {
            itemCount = StringUtilities.parseInt(itemName.substring(0, spaceIndex));
            itemName = itemName.substring(spaceIndex + 1);
          }
          itemId = ItemDatabase.getItemId(itemName, itemCount, itemCount > 1);
        }

        if (itemId == -1) {
          continue;
        }

        // Certain items get use special links to minimize the
        // amount of scrolling to find the item again.

        if (location.startsWith("inventory.php")
            || (location.startsWith("inv_use.php") && location.contains("ajax=1"))) {
          switch (itemId) {
            case ItemPool.FOIL_BOW, ItemPool.FOIL_RADAR, ItemPool.FOIL_CAT_EARS -> {
              specialLinkId = itemId;
              specialLinkText = "fold";
            }
          }
        }

        // Possibly append a use link
        link =
            UseLinkDecorator.addUseLink(itemId, itemCount, location, useLinkMatcher, text, buffer);
      }

      // If we added no link, copy in the text verbatim
      if (!link) {
        useLinkMatcher.appendReplacement(buffer, "$1$2<b>$3</b>$4</td>");
      }

      // If we are currently deferring use links or have
      // already deferred some during this combat, append to
      // list of items previously seen.
      //
      // During macro execution, append everything found, so
      // they accumulate at the bottom of the screen.

      if (usedMacro
          || deferring
          || UseLinkDecorator.deferred.length() > 0) { // Find where the replacement was appended
        String match =
            useLinkMatcher.group(1) + useLinkMatcher.group(2) + "<b>" + useLinkMatcher.group(3);
        pos = buffer.indexOf(match, pos);
        if (pos == -1) {
          continue;
        }

        // Find start of table containing it
        pos = buffer.lastIndexOf("<table", pos);
        if (pos == -1) {
          continue;
        }

        UseLinkDecorator.deferred.append("<tr>");
        UseLinkDecorator.deferred.append(buffer.substring(pos));
        UseLinkDecorator.deferred.append("</table>");
        UseLinkDecorator.deferred.append("</tr>");
      }
    }

    useLinkMatcher.appendTail(buffer);

    if (!deferring && specialLinkText != null) {
      StringUtilities.singleStringReplace(
          buffer,
          "</center></blockquote>",
          "<p><center><a href=\"inv_use.php?pwd="
              + GenericRequest.passwordHash
              + "&which=2&whichitem="
              + specialLinkId
              + "\">["
              + specialLinkText
              + " it again]</a></center></blockquote>");
    }
  }

  // <img src="http://images.kingdomofloathing.com/itemimages/jerkcicle.gif" alt="dangerous
  // jerkcicle" title="dangerous jerkcicle" class=hand onClick='descitem(726861308)'>
  private static final Pattern POETIC_ACQUIRE_PATTERN =
      Pattern.compile("<img[^>]*?descitem\\((\\d+)\\)'>", Pattern.DOTALL);

  private static void addPoeticUseLinks(
      String location, String text, StringBuffer buffer, boolean deferring) {
    Matcher useLinkMatcher = POETIC_ACQUIRE_PATTERN.matcher(text);

    while (useLinkMatcher.find()) {
      String descId = useLinkMatcher.group(1);
      String itemName = ItemDatabase.getItemName(descId);
      int itemId = ItemDatabase.getItemIdFromDescription(descId);

      if (itemId == -1) {
        continue;
      }

      UseLinkDecorator.deferred.append("<tr><td>");
      UseLinkDecorator.deferred.append(useLinkMatcher.group(0));
      UseLinkDecorator.deferred.append("</td><td>You acquire an item: <b>");
      UseLinkDecorator.deferred.append(itemName);
      UseLinkDecorator.deferred.append("</b>");

      UseLink link = UseLinkDecorator.generateUseLink(itemId, 1, location, text);

      if (link != null) {
        UseLinkDecorator.deferred.append(" ");
        UseLinkDecorator.deferred.append(link.getItemHTML());
      }

      UseLinkDecorator.deferred.append("</td></tr>");
    }

    // Copy the text unchanged into the buffer
    buffer.append(text);
  }

  private static CraftingType shouldAddCreateLink(int itemId, String location) {
    if (location == null || (location.contains("craft.php") && !location.contains("pulverize"))) {
      return CraftingType.NOCREATE;
    }

    // Retrieve the known ingredient uses for the item.
    Set<AdventureResult> creations = ConcoctionDatabase.getKnownUses(itemId);
    if (creations.isEmpty()) {
      return CraftingType.NOCREATE;
    }

    // Skip items which are multi-use.
    ConsumptionType consumeMethod = ItemDatabase.getConsumptionType(itemId);
    if (consumeMethod == ConsumptionType.USE_MULTIPLE) {
      return CraftingType.NOCREATE;
    }

    switch (itemId) {
        // If you find the wooden stakes, you want to equip them
      case ItemPool.WOODEN_STAKES -> {
        return CraftingType.NOCREATE;
      }

        // If you find goat cheese, let the trapper link handle it.
      case ItemPool.GOAT_CHEESE -> {
        return CraftingType.NOCREATE;
      }

        // If you find ore, let the trapper link handle it.
      case ItemPool.LINOLEUM_ORE, ItemPool.ASBESTOS_ORE, ItemPool.CHROME_ORE -> {
        return CraftingType.NOCREATE;
      }

        // Dictionaries and bridges should link to the chasm quest.
      case ItemPool.DICTIONARY, ItemPool.BRIDGE -> {
        return CraftingType.NOCREATE;
      }

        // The eyepatch can be combined, but is usually an outfit piece
        // The dreadsack can be combined, but is usually an outfit piece
        // The frilly skirt is usually used for the frathouse blueprints
      case ItemPool.EYEPATCH, ItemPool.DREADSACK, ItemPool.FRILLY_SKIRT -> {
        return CraftingType.NOCREATE;
      }

        // Spooky Fertilizer CAN be cooked, but almost always is used
        // for with the spooky temple map.
      case ItemPool.SPOOKY_FERTILIZER -> {
        return CraftingType.NOCREATE;
      }

        // Enchanted beans are primarily used for the beanstalk quest.
      case ItemPool.ENCHANTED_BEAN -> {
        if (KoLCharacter.getLevel() >= 10 && !InventoryManager.hasItem(ItemPool.SOCK)) {
          return CraftingType.NOCREATE;
        }
      }
    }

    for (AdventureResult creation : creations) {
      CraftingType mixingMethod = ConcoctionDatabase.getMixingMethod(creation);
      EnumSet<CraftingRequirements> requirements =
          ConcoctionDatabase.getRequirements(creation.getItemId());
      if (!ConcoctionDatabase.isPermittedMethod(mixingMethod, requirements)) {
        continue;
      }

      // Only accept if it's a creation method that the
      // editor kit currently understands and links.

      switch (mixingMethod) {
        case COMBINE, ACOMBINE, MIX, MIX_FANCY, COOK, COOK_FANCY, JEWELRY -> {}
        default -> {
          continue;
        }
      }

      CreateItemRequest irequest = CreateItemRequest.getInstance(creation);
      if (irequest != null && irequest.getQuantityPossible() > 0) {
        return mixingMethod;
      }
    }

    return CraftingType.NOCREATE;
  }

  private static boolean addEffectLink(
      String location,
      Matcher useLinkMatcher,
      StringBuffer buffer,
      LinkedList<AdventureResult> effects) {
    String message = useLinkMatcher.group(0);
    if (!message.contains("You acquire an effect")
        && !message.contains("You acquire an intrinsic")) {
      return false;
    }

    String effectName = useLinkMatcher.group(3);
    AdventureResult effect = effects.size() == 0 ? null : effects.getFirst();

    if (effect != null && effectName.equals(effect.getName())) {
      effects.removeFirst();
    } else {
      effect = EffectPool.get(EffectDatabase.getEffectId(effectName), 1);
    }

    UseLink link = null;

    switch (effect.getEffectId()) {
      case EffectPool.FILTHWORM_LARVA_STENCH -> link =
          new UseLink(0, "feeding chamber", "adventure.php?snarfblat=128");
      case EffectPool.FILTHWORM_DRONE_STENCH -> link =
          new UseLink(0, "guards' chamber", "adventure.php?snarfblat=129");
      case EffectPool.FILTHWORM_GUARD_STENCH -> link =
          new UseLink(0, "queen's chamber", "adventure.php?snarfblat=130");
      case EffectPool.KNOB_GOBLIN_PERFUME -> link =
          new UseLink(0, "throne room", "cobbsknob.php?action=throneroom");
      case EffectPool.DOWN_THE_RABBIT_HOLE -> link =
          new UseLink(0, "rabbit hole", "place.php?whichplace=rabbithole");
      case EffectPool.TRANSPONDENT -> link = new UseLink(0, "spaaace", "spaaace.php?arrive=1");
      case EffectPool.STONE_FACED -> link =
          new UseLink(0, "hidden temple", "adventure.php?snarfblat=280");
      case EffectPool.DIS_ABLED -> link = new UseLink(0, "portal to dis", "suburbandis.php");
      default -> {
        // There are several effect names which are also items.
        // We know that we're dealing with an effect here, so there's
        // no point in also checking for an item match.
        return true;
      }
    }

    String useType = link.getUseType();
    String useLocation = link.getUseLocation();

    useLinkMatcher.appendReplacement(
        buffer,
        "$1$2<b>$3</b> <font size=1>[<a href=\""
            + useLocation
            + "\">"
            + useType
            + "</a>]</font></td>"
            + "$4");
    return true;
  }

  private static boolean addUseLink(
      int itemId,
      int itemCount,
      String location,
      Matcher useLinkMatcher,
      String text,
      StringBuffer buffer) {
    UseLink link = UseLinkDecorator.generateUseLink(itemId, itemCount, location, text);

    if (link == null) {
      return false;
    }

    useLinkMatcher.appendReplacement(buffer, "$1$2<b>$3</b> " + link.getItemHTML() + "$4");

    buffer.append("</td>");
    return true;
  }

  protected static UseLink generateUseLink(
      int itemId, int itemCount, String location, String text) {

    // If this is a workshed item, and you've already replaced your
    // workshed item today, it is pointless to provide a "use" link,
    // since you can do that once per day.
    if (CampgroundRequest.isWorkshedItem(itemId) && Preferences.getBoolean("_workshedItemUsed")) {
      return null;
    }

    // This might be a target of the Party Fair quest - if so we overwrite normal use link to
    // prevent accidents and show progress
    if (QuestDatabase.isQuestStep(Quest.PARTY_FAIR, "step1")
        || QuestDatabase.isQuestStep(Quest.PARTY_FAIR, "step2")) {
      String quest = Preferences.getString("_questPartyFairQuest");
      if (quest.equals("booze") || quest.equals("food")) {
        String target = Preferences.getString("_questPartyFairProgress");
        String itemCountString = null;
        String itemIdString = null;
        int position = target.indexOf(" ");
        if (position > 0) {
          itemCountString = target.substring(0, position);
          itemIdString = target.substring(position);
          if (StringUtilities.parseInt(itemIdString) == itemId) {
            return new UseLink(
                itemId, InventoryManager.getCount(itemId), "adventure.php?snarfblat=528");
          }
        }
      }
    }

    ConsumptionType consumeMethod = ItemDatabase.getConsumptionType(itemId);
    CraftingType mixingMethod = shouldAddCreateLink(itemId, location);

    if (mixingMethod != CraftingType.NOCREATE) {
      return getCreateLink(itemId, itemCount, mixingMethod);
    }

    if (consumeMethod == ConsumptionType.NONE) {
      return getNavigationLink(itemId, location);
    }

    return getUseLink(itemId, itemCount, location, consumeMethod, text);
  }

  private static UseLink getCreateLink(
      final int itemId, final int itemCount, final CraftingType mixingMethod) {
    return switch (mixingMethod) {
      case COMBINE, ACOMBINE, JEWELRY -> new UseLink(
          itemId, itemCount, "combine", "craft.php?mode=combine&a=");
      case MIX, MIX_FANCY -> new UseLink(itemId, itemCount, "mix", "craft.php?mode=cocktail&a=");
      case COOK, COOK_FANCY -> new UseLink(itemId, itemCount, "cook", "craft.php?mode=cook&a=");
      default -> null;
    };
  }

  private static UseLink getCouncilLink(int itemId) {
    return KoLCharacter.isEd()
        ? new UseLink(itemId, "Amun", "council.php")
        : KoLCharacter.isKingdomOfExploathing()
            ? new UseLink(itemId, "council", "place.php?whichplace=exploathing&action=expl_council")
            : new UseLink(itemId, "council", "council.php");
  }

  private static UseLink getUseLink(
      int itemId,
      int itemCount,
      String location,
      ConsumptionType consumeMethod,
      final String text) {
    if (!ConsumablesDatabase.meetsLevelRequirement(ItemDatabase.getItemName(itemId))) {
      return null;
    }

    boolean adventureResults =
        location.startsWith("adventure.php")
            || location.startsWith("fight.php")
            || location.startsWith("choice.php");

    switch (consumeMethod) {
      case FAMILIAR_HATCHLING -> {
        if (itemId == ItemPool.MOSQUITO_LARVA) {
          return getCouncilLink(itemId);
        }

        if (KoLCharacter.isPicky()) {
          return null;
        }

        if (KoLCharacter.inBeecore() && ItemDatabase.unusableInBeecore(itemId)) {
          return null;
        }

        if (KoLCharacter.inGLover() && ItemDatabase.unusableInGLover(itemId)) {
          return null;
        }

        return new UseLink(itemId, "grow", "inv_familiar.php?whichitem=");
      }
      case EAT -> {
        switch (itemId) {
          case ItemPool.GOAT_CHEESE -> {
            return new UseLink(
                itemId,
                InventoryManager.getCount(itemId),
                "place.php?whichplace=mclargehuge&action=trappercabin");
          }
          case ItemPool.FORTUNE_COOKIE -> {
            ArrayList<UseLink> uses = new ArrayList<>();

            if (KoLCharacter.canEat() && !KoLCharacter.isVampyre()) {
              uses.add(new UseLink(itemId, itemCount, "eat", "inv_eat.php?which=1&whichitem="));
            }

            uses.add(new UseLink(itemId, itemCount, "smash", "inv_use.php?which=1&whichitem="));

            if (uses.size() == 1) {
              return uses.get(0);
            }

            return new UsesLink(uses.toArray(new UseLink[0]));
          }
          case ItemPool.HOT_WING -> {
            ArrayList<UseLink> uses = new ArrayList<>();

            if (KoLCharacter.canEat() && !KoLCharacter.isVampyre()) {
              uses.add(new UseLink(itemId, itemCount, "eat", "inv_eat.php?which=1&whichitem="));
            }

            uses.add(new UseLink(itemId, InventoryManager.getCount(itemId)));

            if (uses.size() == 1) {
              return uses.get(0);
            }

            return new UsesLink(uses.toArray(new UseLink[0]));
          }
          case ItemPool.SNOW_BERRIES, ItemPool.ICE_HARVEST -> {
            ArrayList<UseLink> uses = new ArrayList<>();

            if (KoLCharacter.canEat() && !KoLCharacter.isVampyre()) {
              uses.add(new UseLink(itemId, itemCount, "eat", "inv_eat.php?which=1&whichitem="));
            }

            uses.add(new UseLink(itemId, 1, "make stuff", "shop.php?whichshop=snowgarden"));

            if (uses.size() == 1) {
              return uses.get(0);
            }

            return new UsesLink(uses.toArray(new UseLink[0]));
          }
          case ItemPool.GIANT_MARSHMALLOW, ItemPool.SPONGE_CAKE -> {
            if (!QuestDatabase.isQuestFinished(Quest.AZAZEL)
                && InventoryManager.getCount(ItemPool.AZAZELS_UNICORN) == 0) {
              return UseLinkDecorator.svenLink(itemId);
            }
          }
        }

        if (!KoLCharacter.canEat()) {
          return null;
        }

        if (KoLCharacter.inBeecore() && ItemDatabase.unusableInBeecore(itemId)) {
          return null;
        }

        if (KoLCharacter.inGLover() && ItemDatabase.unusableInGLover(itemId)) {
          return null;
        }

        if (KoLCharacter.inNuclearAutumn()
            && ConsumablesDatabase.getFullness(ItemDatabase.getCanonicalName(itemId)) > 1) {
          return null;
        }

        if (KoLCharacter.isVampyre() && !ConsumablesDatabase.consumableByVampyres(itemId)) {
          return null;
        }

        if (itemId == ItemPool.BLACK_PUDDING) {
          return new UseLink(itemId, itemCount, "eat", "inv_eat.php?which=1&whichitem=", false);
        }

        return new UseLink(itemId, itemCount, "eat", "inv_eat.php?which=1&whichitem=");
      }
      case DRINK -> {
        if (!KoLCharacter.canDrink()) {
          return null;
        }

        if (KoLCharacter.inBeecore() && ItemDatabase.unusableInBeecore(itemId)) {
          return null;
        }

        if (KoLCharacter.inGLover() && ItemDatabase.unusableInGLover(itemId)) {
          return null;
        }

        if (KoLCharacter.inNuclearAutumn()
            && ConsumablesDatabase.getInebriety(ItemDatabase.getCanonicalName(itemId)) > 1) {
          return null;
        }

        if (KoLCharacter.isVampyre() && !ConsumablesDatabase.consumableByVampyres(itemId)) {
          return null;
        }

        switch (itemId) {
          case ItemPool.BOOZE_SOAKED_CHERRY, ItemPool.GIN_SOAKED_BLOTTER_PAPER -> {
            if (!QuestDatabase.isQuestFinished(Quest.AZAZEL)
                && InventoryManager.getCount(ItemPool.AZAZELS_UNICORN) == 0) {
              return UseLinkDecorator.svenLink(itemId);
            }
          }
          case ItemPool.BOTTLE_OF_CHATEAU_DE_VINEGAR -> {
            return null;
          }
        }
        return new UseLink(itemId, itemCount, "drink", "inv_booze.php?which=1&whichitem=");
      }
      case FOOD_HELPER -> {
        if (!KoLCharacter.canEat()) {
          return null;
        }
        return new UseLink(itemId, 1, "eat with", "inv_use.php?which=1&whichitem=");
      }
      case DRINK_HELPER -> {
        if (!KoLCharacter.canDrink()) {
          return null;
        }
        return new UseLink(itemId, 1, "drink with", "inv_use.php?which=1&whichitem=");
      }
      case POTION, AVATAR_POTION -> {
        int count = InventoryManager.getCount(itemId);
        int useCount = Math.min(UseItemRequest.maximumUses(itemId), count);

        // If we are limited to 0 uses, no use link needed
        if (useCount == 0) {
          return null;
        }

        if (KoLCharacter.inBeecore() && ItemDatabase.unusableInBeecore(itemId)) {
          return null;
        }

        if (KoLCharacter.inGLover() && ItemDatabase.unusableInGLover(itemId)) {
          return null;
        }

        if (useCount == 1 || !ItemDatabase.isMultiUsable(itemId)) {
          String use = getPotionSpeculation("use", itemId);
          return new UseLink(itemId, 1, use, "inv_use.php?which=3&whichitem=");
        }

        String use = getPotionSpeculation("use multiple", itemId);
        if (Preferences.getBoolean("relayUsesInlineLinks")) {
          return new UseLink(itemId, useCount, use, "#");
        }

        return new UseLink(itemId, useCount, use, "multiuse.php?passitem=");
      }
      case USE_MULTIPLE -> {
        int count = InventoryManager.getCount(itemId);
        int useCount = Math.min(UseItemRequest.maximumUses(itemId), count);

        // If we are limited to 0 uses, no use link needed
        if (useCount == 0) {
          return null;
        }

        if (KoLCharacter.inBeecore() && ItemDatabase.unusableInBeecore(itemId)) {
          return null;
        }

        if (KoLCharacter.inGLover() && ItemDatabase.unusableInGLover(itemId)) {
          return null;
        }

        switch (itemId) {
          case ItemPool.RUSTY_HEDGE_TRIMMERS -> {

            // Not inline, since the redirection to a fight
            // doesn't work ajaxified.

            return new UseLink(itemId, 1, "use", "inv_use.php?which=3&whichitem=", false);
          }
          case ItemPool.DANCE_CARD -> {
            // No use link for a dance card if one is already active or another will expire in 3
            // turns.
            if (TurnCounter.isCounting("Dance Card")
                || TurnCounter.getCounters("", 3, 3).length() > 0) {
              return null;
            }
          }
        }

        if (useCount == 1) {
          String page = (consumeMethod == ConsumptionType.USE_MULTIPLE) ? "3" : "1";
          return new UseLink(
              itemId,
              useCount,
              getPotionSpeculation("use", itemId),
              "inv_use.php?which=" + page + "&whichitem=");
        }

        String use = getPotionSpeculation("use multiple", itemId);
        if (Preferences.getBoolean("relayUsesInlineLinks")) {
          return new UseLink(itemId, useCount, use, "#");
        }

        return new UseLink(itemId, useCount, use, "multiuse.php?passitem=");
      }
      case FOLDER -> {

        // Not inline, since the redirection to a choice
        // doesn't work ajaxified.

        return new UseLink(itemId, 1, "use", "inv_use.php?which=3&whichitem=", false);
      }
      case SPLEEN -> {
        int count = InventoryManager.getCount(itemId);
        int useCount = Math.min(UseItemRequest.maximumUses(itemId), count);

        // If we are limited to 0 uses, no use link needed
        if (useCount == 0) {
          return null;
        }

        if (KoLCharacter.inBeecore() && ItemDatabase.unusableInBeecore(itemId)) {
          return null;
        }

        if (KoLCharacter.inGLover() && ItemDatabase.unusableInGLover(itemId)) {
          return null;
        }

        if (KoLCharacter.inNuclearAutumn()
            && ConsumablesDatabase.getSpleenHit(ItemDatabase.getCanonicalName(itemId)) > 1) {
          return null;
        }

        return new UseLink(
            itemId, useCount, getPotionSpeculation("chew", itemId), "inv_spleen.php?whichitem=");
      }
      case USE, USE_MESSAGE_DISPLAY, USE_INFINITE -> {
        if (KoLCharacter.inBeecore() && ItemDatabase.unusableInBeecore(itemId)) {
          return null;
        }

        if (KoLCharacter.inGLover() && ItemDatabase.unusableInGLover(itemId)) {
          return null;
        }

        switch (itemId) {
          case ItemPool.LOATHING_LEGION_KNIFE,
              ItemPool.LOATHING_LEGION_TATTOO_NEEDLE,
              ItemPool.LOATHING_LEGION_UNIVERSAL_SCREWDRIVER -> {
            ArrayList<UseLink> uses = new ArrayList<>();
            if (itemId == ItemPool.LOATHING_LEGION_TATTOO_NEEDLE) {
              uses.add(new UseLink(itemId, 1, "use", "inv_use.php?which=3&whichitem="));
            } else if (itemId == ItemPool.LOATHING_LEGION_UNIVERSAL_SCREWDRIVER) {
              uses.add(new UseLink(itemId, 1, "untinker", "inv_use.php?which=3&whichitem="));
            }
            uses.add(new UseLink(itemId, 1, "switch", "inv_use.php?which=3&switch=1&whichitem="));

            if (uses.size() == 1) {
              return uses.get(0);
            }
            return new UsesLink(uses.toArray(new UseLink[0]));
          }
          case ItemPool.MACGUFFIN_DIARY, ItemPool.ED_DIARY -> {
            return new UseLink(itemId, 1, "read", "diary.php?textversion=1");
          }
          case ItemPool.VOLCANO_MAP -> {
            return new UseLink(itemId, 1, "read", "volcanoisland.php?intro=1");
          }
          case ItemPool.PIRATE_REALM_FUN_LOG -> {
            return new UseLink(itemId, 1, "order stuff", "shop.php?whichshop=piraterealm");
          }
          case ItemPool.ENCHANTED_BEAN -> {
            return KoLCharacter.getLevel() < 10
                ? null
                : new UseLink(
                    itemId, "plant", "place.php?whichplace=plains&action=garbage_grounds");
          }
          case ItemPool.SPOOKY_SAPLING, ItemPool.SPOOKY_MAP, ItemPool.SPOOKY_FERTILIZER -> {
            if (!InventoryManager.hasItem(ItemPool.SPOOKY_MAP)
                || !InventoryManager.hasItem(ItemPool.SPOOKY_SAPLING)
                || !InventoryManager.hasItem(ItemPool.SPOOKY_FERTILIZER)) {
              return null;
            }

            return new UseLink(ItemPool.SPOOKY_MAP, 1, "map", "inv_use.php?which=3&whichitem=");
          }

            // Soft green echo eyedrop antidote gets an uneffect link

          case ItemPool.REMEDY, ItemPool.ANCIENT_CURE_ALL -> {
            return new UseLink(itemId, 1, "use", "uneffect.php");
          }

          case ItemPool.FRATHOUSE_BLUEPRINTS,
              ItemPool.RONALD_SHELTER_MAP,
              ItemPool.GRIMACE_SHELTER_MAP,
              ItemPool.STAFF_GUIDE,
              ItemPool.FUDGE_WAND,
              ItemPool.REFLECTION_OF_MAP,
              ItemPool.CSA_FIRE_STARTING_KIT,
              ItemPool.CEO_OFFICE_CARD,
              ItemPool.DREADSCROLL,
              ItemPool.RUSTY_HEDGE_TRIMMERS,
              ItemPool.WALKIE_TALKIE,
              ItemPool.SUSPICIOUS_ADDRESS,
              ItemPool.CHEF_BOY_BUSINESS_CARD,
              ItemPool.GRIMSTONE_MASK,
              ItemPool.THUNDER_THIGH,
              ItemPool.AQUA_BRAIN,
              ItemPool.LIGHTNING_MILK,
              ItemPool.SNEAKY_WRAPPING_PAPER,
              ItemPool.BARREL_MAP,
              ItemPool.VYKEA_INSTRUCTIONS,
              ItemPool.TONIC_DJINN,
              ItemPool.COW_PUNCHING_TALES,
              ItemPool.BEAN_SLINGING_TALES,
              ItemPool.SNAKE_OILING_TALES,
              ItemPool.MAYO_MINDER,
              ItemPool.NO_SPOON,
              ItemPool.TIME_SPINNER,
              ItemPool.WAX_GLOB,
              ItemPool.GUMMY_MEMORY,
              ItemPool.METAL_METEOROID,
              ItemPool.GRUBBY_WOOL,
              ItemPool.CORKED_GENIE_BOTTLE,
              ItemPool.GENIE_BOTTLE,
              ItemPool.POCKET_WISH,
              ItemPool.REPLICA_GENIE_BOTTLE,
              ItemPool.WAREHOUSE_KEY,
              ItemPool.BOOMBOX,
              ItemPool.BURNING_NEWSPAPER,
              ItemPool.TALES_OF_SPELUNKING,
              ItemPool.PORTABLE_PANTOGRAM,
              ItemPool.VOTER_BALLOT,
              ItemPool.GOVERNMENT_REQUISITION_FORM,
              ItemPool.CAMPFIRE_SMOKE,
              ItemPool.BURNT_STICK,
              ItemPool.GOVERNMENT_FOOD_SHIPMENT,
              ItemPool.GOVERNMENT_BOOZE_SHIPMENT,
              ItemPool.GOVERNMENT_CANDY_SHIPMENT,
              ItemPool.ADVANCED_PIG_SKINNING,
              ItemPool.THE_CHEESE_WIZARDS_COMPANION,
              ItemPool.JAZZ_AGENT_SHEET_MUSIC,
              ItemPool.CLOSED_CIRCUIT_PAY_PHONE,
              ItemPool.AUTUMNATON,
              ItemPool.BLACK_AND_WHITE_APRON_MEAL_KIT,
              ItemPool.MAYAM_CALENDAR,
              ItemPool.HANDHELD_ALLIED_RADIO,

              // Not inline, since the redirection to a choice
              // doesn't work ajaxified.

              ItemPool.ABYSSAL_BATTLE_PLANS,
              ItemPool.CARONCH_MAP,
              ItemPool.CRUDE_SCULPTURE,
              ItemPool.CURSED_PIECE_OF_THIRTEEN,
              ItemPool.DOLPHIN_WHISTLE,
              ItemPool.ENVYFISH_EGG,
              ItemPool.ICE_SCULPTURE,
              ItemPool.PHOTOCOPIED_MONSTER,
              ItemPool.RAIN_DOH_MONSTER,
              ItemPool.SHAKING_CAMERA,
              ItemPool.SHAKING_CRAPPY_CAMERA,
              ItemPool.SHAKING_SKULL,
              ItemPool.SPOOKY_PUTTY_MONSTER,
              ItemPool.WAX_BUGBEAR,
              ItemPool.LYNYRD_SNARE,
              ItemPool.WHITE_PAGE,
              ItemPool.XIBLAXIAN_HOLOTRAINING_SIMCODE,
              ItemPool.XIBLAXIAN_POLITICAL_PRISONER,
              ItemPool.SCREENCAPPED_MONSTER,
              ItemPool.TIME_RESIDUE,
              ItemPool.MEME_GENERATOR,
              ItemPool.MEGACOPIA,
              ItemPool.AMORPHOUS_BLOB,
              ItemPool.GIANT_AMORPHOUS_BLOB,
              ItemPool.MINIATURE_EMBERING_HULK,

              // Not inline, since the redirection to a fight
              // doesn't work ajaxified.

              ItemPool.PLAINTIVE_TELEGRAM -> {

            // Not inline, since the redirection to an
            // adventure doesn't work ajaxified.

            return new UseLink(itemId, 1, "use", "inv_use.php?which=3&whichitem=", false);
          }
          case ItemPool.MR_STORE_2002_CATALOG, ItemPool.REPLICA_MR_STORE_2002_CATALOG -> {

            // Not inline, since the redirection to a
            // shop doesn't work ajaxified.

            return new UseLink(itemId, 1, "order", "inv_use.php?which=3&whichitem=", false);
          }
          case ItemPool.SEPTEMBER_CENSER -> {

            // Not inline, and also just takes you to the shop directly

            return new UseLink(itemId, 1, "stoke", "shop.php?whichshop=september", false);
          }
          case ItemPool.LATTE_MUG -> {

            // Not inline, since the redirection to a choice
            // doesn't work ajaxified.

            return new UseLink(itemId, 1, "use", "main.php?latte=1", false);
          }
          case ItemPool.DRUM_MACHINE -> {
            if (Preferences.getInteger("desertExploration") == 100) {
              // This will redirect to a sandworm fight
              return new UseLink(itemId, 1, "use", "inv_use.php?which=3&whichitem=", false);
            }
            if (InventoryManager.getCount(ItemPool.WORM_RIDING_HOOKS) > 0) {
              // This will explore the desert
              return new UseLink(itemId, 1, "wormride", "inv_use.php?which=3&whichitem=");
            }
            // *** what happens if you try to use a drum machine with no hooks?
            return null;
          }
          case ItemPool.ASTRAL_MUSHROOM, ItemPool.GONG -> {
            // No use link if already under influence.
            if (KoLCharacter.getLimitMode().limitItem(itemId)) {
              return null;
            }

            // In-line use link does not work.
            return new UseLink(itemId, itemCount, "use", "inv_use.php?which=3&whichitem=", false);
          }
          case ItemPool.COBBS_KNOB_MAP -> {
            if (!InventoryManager.hasItem(ItemPool.ENCRYPTION_KEY)) {
              return null;
            }

            return new UseLink(ItemPool.COBBS_KNOB_MAP, 1, "map", "inv_use.php?which=3&whichitem=");
          }
          case ItemPool.DINGHY_PLANS -> {
            if (InventoryManager.hasItem(ItemPool.DINGY_PLANKS)) {
              return new UseLink(itemId, 1, "use", "inv_use.php?which=3&whichitem=");
            }

            return new UseLink(
                ItemPool.DINGY_PLANKS,
                1,
                "buy planks",
                "shop.php?&whichshop=generalstore&action=buyitem&quantity=1&whichrow=655",
                true);
          }
          case ItemPool.BEER_SCENTED_TEDDY_BEAR, ItemPool.COMFY_PILLOW -> {
            if (!QuestDatabase.isQuestFinished(Quest.AZAZEL)
                && InventoryManager.getCount(ItemPool.AZAZELS_UNICORN) == 0) {
              return UseLinkDecorator.svenLink(itemId);
            }
          }
          case ItemPool.TOPIARY_NUGGLET -> {
            return new UseLink(itemId, 1, "sculpt", "shop.php?whichshop=topiary");
          }
          case ItemPool.TOXIC_GLOBULE -> {
            return new UseLink(itemId, 1, "do science", "shop.php?whichshop=toxic");
          }
          case ItemPool.ROSE, ItemPool.WHITE_TULIP, ItemPool.RED_TULIP, ItemPool.BLUE_TULIP -> {
            return new UseLink(itemId, 1, "trade in", "shop.php?whichshop=flowertradein");
          }
          case ItemPool.GUZZLRBUCK -> {
            ArrayList<UseLink> uses = new ArrayList<>();
            uses.add(new UseLink(itemId, 1, "Let's Guzzle", "shop.php?whichshop=guzzlr"));
            uses.add(new UseLink(itemId, 1, "tap", "inventory.php?tap=guzzlr", false));
            return new UsesLink(uses.toArray(new UseLink[0]));
          }
          case ItemPool.BARLEY,
              ItemPool.HOPS,
              ItemPool.FANCY_BEER_BOTTLE,
              ItemPool.FANCY_BEER_LABEL -> {
            return new UseLink(itemId, 1, "Let's Brew", "shop.php?whichshop=beergarden");
          }
          case ItemPool.WORSE_HOMES_GARDENS -> {
            return new UseLink(itemId, 1, "read", "shop.php?whichshop=junkmagazine");
          }
          case ItemPool.BACON -> {
            int baconcount = InventoryManager.getCount(itemId);
            return new UseLink(itemId, 1, "spend (" + baconcount + ")", "shop.php?whichshop=bacon");
          }
          case ItemPool.X -> {
            int xcount = InventoryManager.getCount(itemId);
            return new UseLink(itemId, 1, "eXpend (" + xcount + ")", "shop.php?whichshop=xo");
          }
          case ItemPool.O -> {
            int ocount = InventoryManager.getCount(itemId);
            return new UseLink(itemId, 1, "blOw (" + ocount + ")", "shop.php?whichshop=xo");
          }
          case ItemPool.RAD -> {
            int radcount = InventoryManager.getCount(itemId);
            return new UseLink(itemId, 1, "mutate (" + radcount + ")", "shop.php?whichshop=mutate");
          }
          case ItemPool.CASHEW -> {
            return new UseLink(itemId, 1, "trade", "shop.php?whichshop=thankshop");
          }
          case ItemPool.EMPTY_AGUA_DE_VIDA_BOTTLE -> {
            return new UseLink(itemId, 1, "gaze", "place.php?whichplace=memories");
          }
          case ItemPool.LITTLE_FIRKIN,
              ItemPool.NORMAL_BARREL,
              ItemPool.BIG_TUN,
              ItemPool.WEATHERED_BARREL,
              ItemPool.DUSTY_BARREL,
              ItemPool.DISINTEGRATING_BARREL,
              ItemPool.MOIST_BARREL,
              ItemPool.ROTTING_BARREL,
              ItemPool.MOULDERING_BARREL,
              ItemPool.BARNACLED_BARREL -> {
            ArrayList<UseLink> uses = new ArrayList<>();
            uses.add(new UseLink(itemId, 1, "use", "inv_use.php?whichitem="));
            uses.add(
                new UseLink(itemId, 1, "smash party", "inv_use.php?choice=1&whichitem=", false));
            return new UsesLink(uses.toArray(new UseLink[0]));
          }
          case ItemPool.GLITCH_ITEM -> {
            ArrayList<UseLink> uses = new ArrayList<>();
            uses.add(new UseLink(itemId, itemCount, "implement", "inv_use.php?whichitem="));
            uses.add(new UseLink(itemId, itemCount, "eat", "inv_eat.php?whichitem=", false));
            return new UsesLink(uses.toArray(new UseLink[0]));
          }
          case ItemPool.FIXODENT -> {
            return new UseLink(itemId, 1, "fix", "shop.php?whichshop=fixodent");
          }
          case ItemPool.CANDY_EGG_DEVILER -> {
            return new UseLink(itemId, 1, "devil", "inventory.php?action=eggdevil", false);
          }
          default -> {
            return new UseLink(
                itemId,
                itemCount,
                getPotionSpeculation("use", itemId),
                "inv_use.php?which=3&whichitem=");
          }
        }
      }
      case PASTA_GUARDIAN -> {
        if (KoLCharacter.inBeecore() && ItemDatabase.unusableInBeecore(itemId)) {
          return null;
        }

        if (KoLCharacter.inGLover() && ItemDatabase.unusableInGLover(itemId)) {
          return null;
        }
        return new UseLink(itemId, 1, "use", "inv_use.php?which=3&whichitem=");
      }
      case HAT, WEAPON, SIXGUN, OFFHAND, SHIRT, PANTS, CONTAINER, ACCESSORY, FAMILIAR_EQUIPMENT -> {
        switch (itemId) {
          case ItemPool.BATSKIN_BELT, ItemPool.BONERDAGON_SKULL -> {
            // If we found it in a battle, take it to the
            // council to complete the quest.
            if (adventureResults) {
              return getCouncilLink(itemId);
            }
          }
          case ItemPool.WORM_RIDING_HOOKS -> {

            // Can you even get the hooks if the desert is fully explored?
            if (Preferences.getInteger("desertExploration") == 100) {
              return null;
            }
            // If you have no drum machine yet, give a link to the Oasis
            if (InventoryManager.getCount(ItemPool.DRUM_MACHINE) == 0) {
              return new UseLink(0, "oasis", "adventure.php?snarfblat=122");
            }
            return new UseLink(
                ItemPool.DRUM_MACHINE, 1, "wormride", "inv_use.php?which=3&whichitem=");
          }
          case ItemPool.PIXEL_CHAIN_WHIP, ItemPool.PIXEL_MORNING_STAR -> {
            // If we "acquire" the pixel whip upgrades in
            // the Chapel, they are autoequipped
            if (location.startsWith("adventure.php")) {
              return null;
            }
          }
          case ItemPool.BJORNS_HAMMER,
              ItemPool.MACE_OF_THE_TORTOISE,
              ItemPool.PASTA_SPOON_OF_PERIL,
              ItemPool.FIVE_ALARM_SAUCEPAN,
              ItemPool.DISCO_BANJO,
              ItemPool.ROCK_N_ROLL_LEGEND -> {
            // If we "acquire" the Epic Weapon from
            // a fight, give a link to the guild to collect
            // the reward as well as "equip" link.
            UseLink equipLink =
                new UseLink(
                    itemId,
                    itemCount,
                    getEquipmentSpeculation("equip", itemId, Slot.NONE),
                    "inv_equip.php?which=2&action=equip&whichitem=");
            if (adventureResults) {
              ArrayList<UseLink> uses = new ArrayList<>();
              // scg = Same Class in Guild
              uses.add(new UseLink(itemId, "guild", "guild.php?place=scg"));
              uses.add(equipLink);
              return new UsesLink(uses.toArray(new UseLink[0]));
            }
            return equipLink;
          }
          case ItemPool.HAMMER_OF_SMITING,
              ItemPool.CHELONIAN_MORNINGSTAR,
              ItemPool.GREEK_PASTA_OF_PERIL,
              ItemPool.SEVENTEEN_ALARM_SAUCEPAN,
              ItemPool.SHAGADELIC_DISCO_BANJO,
              ItemPool.SQUEEZEBOX_OF_THE_AGES -> {
            // When we "craft" the Legendary_Epic Weapon, give a link to
            // the guild to collect the reward as well as "equip" link.
            UseLink equipLink =
                new UseLink(
                    itemId,
                    itemCount,
                    getEquipmentSpeculation("equip", itemId, Slot.NONE),
                    "inv_equip.php?which=2&action=equip&whichitem=");
            ArrayList<UseLink> uses = new ArrayList<>();
            // scg = Same Class in Guild
            uses.add(new UseLink(itemId, "guild", "guild.php?place=scg"));
            uses.add(equipLink);
            return new UsesLink(uses.toArray(new UseLink[0]));
          }
          case ItemPool.SCALP_OF_GORGOLOK,
              ItemPool.ELDER_TURTLE_SHELL,
              ItemPool.COLANDER_OF_EMERIL,
              ItemPool.ANCIENT_SAUCEHELM,
              ItemPool.DISCO_FRO_PICK,
              ItemPool.EL_SOMBRERO_DE_LOPEZ -> {
            // If we "acquire" the Nemesis hat from
            // a fight, give a link to the guild to collect
            // the reward as well as "equip" link.
            UseLink equipLink =
                new UseLink(
                    itemId,
                    itemCount,
                    getEquipmentSpeculation("equip", itemId, Slot.NONE),
                    "inv_equip.php?which=2&action=equip&whichitem=");
            if (adventureResults) {
              ArrayList<UseLink> uses = new ArrayList<>();
              // scg = Same Class in Guild
              uses.add(new UseLink(itemId, "guild", "guild.php?place=scg"));
              uses.add(equipLink);
              return new UsesLink(uses.toArray(new UseLink[0]));
            }
            return equipLink;
          }
          case ItemPool.INFERNAL_SEAL_CLAW,
              ItemPool.TURTLE_POACHER_GARTER,
              ItemPool.SPAGHETTI_BANDOLIER,
              ItemPool.SAUCEBLOB_BELT,
              ItemPool.NEW_WAVE_BLING,
              ItemPool.BELT_BUCKLE_OF_LOPEZ -> {
            // If we "acquire" the Nemesis accessories from
            // a fight, give a link to the guild to collect
            // the reward as well as "outfit" link.
            if (adventureResults) {
              ArrayList<UseLink> uses = new ArrayList<>();
              int outfit = EquipmentDatabase.getOutfitWithItem(itemId);
              // scg = Same Class in Guild
              uses.add(new UseLink(itemId, "guild", "guild.php?place=scg"));
              uses.add(
                  new UseLink(
                      itemId,
                      itemCount,
                      "outfit",
                      "inv_equip.php?action=outfit&which=2&whichoutfit=" + outfit));
              return new UsesLink(uses.toArray(new UseLink[0]));
            }
          }
          case ItemPool.SPELUNKY_SPRING_BOOTS, ItemPool.SPELUNKY_SPIKED_BOOTS -> {
            // Spelunky "accessories" need a single "equip"
            // link which goes to slot 1
            return new UseLink(
                itemId, itemCount, "equip", "inv_equip.php?which=2&action=equip&slot=1&whichitem=");
          }
          case ItemPool.HEIMZ_BEANS,
              ItemPool.TESLA_BEANS,
              ItemPool.MIXED_BEANS,
              ItemPool.HELLFIRE_BEANS,
              ItemPool.FRIGID_BEANS,
              ItemPool.BLACKEST_EYED_PEAS,
              ItemPool.STINKBEANS,
              ItemPool.PORK_N_BEANS,
              ItemPool.PREMIUM_BEANS -> {
            UseLink equipLink =
                new UseLink(
                    itemId,
                    itemCount,
                    getEquipmentSpeculation("equip", itemId, Slot.NONE),
                    "inv_equip.php?which=2&action=equip&whichitem=");
            // inv_use.php?pwd&which=f-1&whichitem=xxx
            UseLink plateLink =
                new UseLink(itemId, itemCount, "plate", "inv_use.php?which=f-1&whichitem=");
            ArrayList<UseLink> uses = new ArrayList<>();
            uses.add(equipLink);
            uses.add(plateLink);
            return new UsesLink(uses.toArray(new UseLink[0]));
          }
          case ItemPool.CODPIECE -> {
            UseLink equipLink =
                new UseLink(
                    itemId,
                    itemCount,
                    getEquipmentSpeculation("equip", itemId, Slot.NONE),
                    "inv_equip.php?which=2&action=equip&whichitem=");
            // inv_use.php?pwd&which=f-1&whichitem=xxx
            UseLink wringOutLink =
                new UseLink(itemId, itemCount, "wring out", "inv_use.php?which=f-1&whichitem=");
            ArrayList<UseLink> uses = new ArrayList<>();
            uses.add(equipLink);
            uses.add(wringOutLink);
            return new UsesLink(uses.toArray(new UseLink[0]));
          }
          case ItemPool.BASS_CLARINET -> {
            UseLink equipLink =
                new UseLink(
                    itemId,
                    itemCount,
                    getEquipmentSpeculation("equip", itemId, Slot.NONE),
                    "inv_equip.php?which=2&action=equip&whichitem=");
            // inv_use.php?pwd&which=f-1&whichitem=xxx
            UseLink drainLink =
                new UseLink(itemId, itemCount, "drain spit", "inv_use.php?which=f-1&whichitem=");
            ArrayList<UseLink> uses = new ArrayList<>();
            uses.add(equipLink);
            uses.add(drainLink);
            return new UsesLink(uses.toArray(new UseLink[0]));
          }
          case ItemPool.FISH_HATCHET -> {
            UseLink equipLink =
                new UseLink(
                    itemId,
                    itemCount,
                    getEquipmentSpeculation("equip", itemId, Slot.NONE),
                    "inv_equip.php?which=2&action=equip&whichitem=");
            // inv_use.php?pwd&which=f-1&whichitem=xxx
            UseLink useLink =
                new UseLink(itemId, itemCount, "use", "inv_use.php?which=f-1&whichitem=");
            ArrayList<UseLink> uses = new ArrayList<>();
            uses.add(equipLink);
            uses.add(useLink);
            return new UsesLink(uses.toArray(new UseLink[0]));
          }
        }

        // Don't offer an "equip" link for weapons or offhands
        // in Fistcore or Axecore
        if ((consumeMethod == ConsumptionType.WEAPON || consumeMethod == ConsumptionType.OFFHAND)
            && (KoLCharacter.inFistcore() || KoLCharacter.inAxecore())) {
          return null;
        }

        int outfit = EquipmentDatabase.getOutfitWithItem(itemId);

        ArrayList<UseLink> uses = new ArrayList<>();

        if (outfit != -1 && EquipmentManager.hasOutfit(outfit)) {
          uses.add(
              new UseLink(
                  itemId,
                  itemCount,
                  "outfit",
                  "inv_equip.php?action=outfit&which=2&whichoutfit=" + outfit));
        }

        if (consumeMethod == ConsumptionType.ACCESSORY
            && !EquipmentManager.getEquipment(Slot.ACCESSORY1).equals(EquipmentRequest.UNEQUIP)
            && !EquipmentManager.getEquipment(Slot.ACCESSORY2).equals(EquipmentRequest.UNEQUIP)
            && !EquipmentManager.getEquipment(Slot.ACCESSORY3).equals(EquipmentRequest.UNEQUIP)) {
          uses.add(
              new UseLink(
                  itemId,
                  itemCount,
                  getEquipmentSpeculation("acc1", itemId, Slot.ACCESSORY1),
                  "inv_equip.php?which=2&action=equip&slot=1&whichitem="));
          uses.add(
              new UseLink(
                  itemId,
                  itemCount,
                  getEquipmentSpeculation("acc2", itemId, Slot.ACCESSORY2),
                  "inv_equip.php?which=2&action=equip&slot=2&whichitem="));
          uses.add(
              new UseLink(
                  itemId,
                  itemCount,
                  getEquipmentSpeculation("acc3", itemId, Slot.ACCESSORY3),
                  "inv_equip.php?which=2&action=equip&slot=3&whichitem="));
        } else if (consumeMethod == ConsumptionType.SIXGUN) {
          // Only as WOL class
          if (KoLCharacter.getAscensionClass() != AscensionClass.COW_PUNCHER
              && KoLCharacter.getAscensionClass() != AscensionClass.BEANSLINGER
              && KoLCharacter.getAscensionClass() != AscensionClass.SNAKE_OILER) {
            return null;
          }
          uses.add(
              new UseLink(
                  itemId,
                  itemCount,
                  getEquipmentSpeculation("holster", itemId, Slot.NONE),
                  "inventory.php?which=2&action=holster&whichitem=",
                  false));
        } else {
          uses.add(
              new UseLink(
                  itemId,
                  itemCount,
                  getEquipmentSpeculation("equip", itemId, Slot.NONE),
                  "inv_equip.php?which=2&action=equip&whichitem="));

          // Quietly, stealthily, you reach out and steal the pants from your
          // unsuspecting self, and fade back into the mazy passages of the
          // Sleazy Back Alley before you notice what has happened.
          //
          // Then you make your way back out of the Alley, clutching your pants
          // triumphantly and trying really hard not to think about how oddly
          // chilly it has suddenly become.

          if (consumeMethod == ConsumptionType.PANTS
              && text.contains("steal the pants from your unsuspecting self")) {
            uses.add(new UseLink(itemId, "guild", "guild.php?place=challenge"));
          }
        }

        if (consumeMethod == ConsumptionType.WEAPON
            && EquipmentDatabase.getHands(itemId) == 1
            && EquipmentDatabase.getHands(EquipmentManager.getEquipment(Slot.WEAPON).getItemId())
                == 1
            && KoLCharacter.hasSkill(SkillPool.DOUBLE_FISTED_SKULL_SMASHING)) {
          uses.add(
              new UseLink(
                  itemId,
                  itemCount,
                  getEquipmentSpeculation("offhand", itemId, Slot.OFFHAND),
                  "inv_equip.php?which=2&action=dualwield&whichitem="));
        }

        if (consumeMethod != ConsumptionType.FAMILIAR_EQUIPMENT
            && KoLCharacter.getFamiliar().canEquip(ItemPool.get(itemId, 1))) {
          uses.add(
              new UseLink(
                  itemId,
                  itemCount,
                  getEquipmentSpeculation("familiar", itemId, Slot.FAMILIAR),
                  "inv_equip.php?which=2&action=hatrack&whichitem="));
        }

        switch (itemId) {
          case ItemPool.LOATHING_LEGION_MANY_PURPOSE_HOOK,
              ItemPool.LOATHING_LEGION_MOONDIAL,
              ItemPool.LOATHING_LEGION_NECKTIE,
              ItemPool.LOATHING_LEGION_ELECTRIC_KNIFE,
              ItemPool.LOATHING_LEGION_CORKSCREW,
              ItemPool.LOATHING_LEGION_CAN_OPENER,
              ItemPool.LOATHING_LEGION_CHAINSAW,
              ItemPool.LOATHING_LEGION_ROLLERBLADES,
              ItemPool.LOATHING_LEGION_FLAMETHROWER,
              ItemPool.LOATHING_LEGION_DEFIBRILLATOR,
              ItemPool.LOATHING_LEGION_DOUBLE_PRISM,
              ItemPool.LOATHING_LEGION_TAPE_MEASURE,
              ItemPool.LOATHING_LEGION_KITCHEN_SINK,
              ItemPool.LOATHING_LEGION_ABACUS,
              ItemPool.LOATHING_LEGION_HELICOPTER,
              ItemPool.LOATHING_LEGION_PIZZA_STONE,
              ItemPool.LOATHING_LEGION_HAMMER -> uses.add(
              new UseLink(itemId, 1, "switch", "inv_use.php?which=3&switch=1&whichitem="));
          case ItemPool.INSULT_PUPPET, ItemPool.OBSERVATIONAL_GLASSES, ItemPool.COMEDY_PROP -> uses
              .add(
                  new UseLink(
                      itemId, itemCount, "visit mourn", "pandamonium.php?action=mourn&whichitem="));
          case ItemPool.GUZZLR_TABLET -> {
            // Not inline, since the redirection to a choice
            // doesn't work ajaxified.
            uses.add(new UseLink(itemId, 1, "tap", "inventory.php?tap=guzzlr", false));
          }
          case ItemPool.CARGO_CULTIST_SHORTS, ItemPool.REPLICA_CARGO_CULTIST_SHORTS -> {
            // Not inline, since the redirection to a choice
            // doesn't work ajaxified.
            uses.add(new UseLink(itemId, 1, "pockets", "inventory.php?action=pocket", false));
          }
          case ItemPool.MCHUGELARGE_DUFFEL_BAG -> {
            uses.add(new UseLink(itemId, 1, "open", "inventory.php?action=skiduffel"));
          }
          case ItemPool.APRIL_SHOWER_THOUGHTS_SHIELD -> {
            uses.add(new UseLink(itemId, 1, "shower", "inventory.php?action=shower"));
          }
          case ItemPool.ALLIED_RADIO_BACKPACK -> {
            uses.add(
                new UseLink(itemId, 1, "request drop", "inventory.php?action=requestdrop", false));
          }
        }

        if (uses.size() == 1) {
          return uses.get(0);
        } else {
          return new UsesLink(uses.toArray(new UseLink[0]));
        }
      }
      case ZAP -> {
        return new UseLink(itemId, itemCount, "zap", "wand.php?whichwand=");
      }
    }

    return null;
  }

  private static int equipSequence = 0;

  private static String getSpeculation(String label, Modifiers mods) {
    String id = "whatif" + UseLinkDecorator.equipSequence++;
    String table =
        SpeculateCommand.getHTML(
            mods,
            "id='"
                + id
                + "' style='background-color: white; visibility: hidden; position: absolute; z-index: 1; right: 0px; top: 1.2em;'");
    if (table == null) return label;
    return "<span style='position: relative;' onMouseOver=\"document.getElementById('"
        + id
        + "').style.visibility='visible';\" onMouseOut=\"document.getElementById('"
        + id
        + "').style.visibility='hidden';\">"
        + table
        + label
        + "</span>";
  }

  public static final String getEquipmentSpeculation(String label, int itemId, Slot slot) {
    if (slot == Slot.NONE) {
      slot = EquipmentRequest.chooseEquipmentSlot(itemId);
    }
    Speculation spec = new Speculation();
    spec.equip(slot, ItemPool.get(itemId, 1));
    Modifiers mods = spec.calculate();
    return getSpeculation(label, mods);
  }

  private static String getPotionSpeculation(String label, int itemId) {
    Modifiers mods = ModifierDatabase.getItemModifiers(itemId);
    if (mods == null) return label;
    String effect = mods.getString(MultiStringModifier.EFFECT);
    if (effect.equals("")) return label;
    int duration = (int) mods.getDouble(DoubleModifier.EFFECT_DURATION);
    int effectId = EffectDatabase.getEffectId(effect);
    Speculation spec = new Speculation();
    spec.addEffect(EffectPool.get(effectId, Math.max(1, duration)));
    mods = spec.calculate();
    mods.setString(MultiStringModifier.EFFECT, effect);
    if (duration > 0) {
      mods.setDouble(DoubleModifier.EFFECT_DURATION, (float) duration);
    }
    return getSpeculation(label, mods);
  }

  private static UseLink svenLink(int itemId) {
    if ((Math.min(InventoryManager.getCount(ItemPool.BEER_SCENTED_TEDDY_BEAR), 1)
                + Math.min(InventoryManager.getCount(ItemPool.GIANT_MARSHMALLOW), 1)
                + InventoryManager.getCount(ItemPool.GIN_SOAKED_BLOTTER_PAPER)
            >= 2)
        && (Math.min(InventoryManager.getCount(ItemPool.BOOZE_SOAKED_CHERRY), 1)
                + Math.min(InventoryManager.getCount(ItemPool.COMFY_PILLOW), 1)
                + InventoryManager.getCount(ItemPool.SPONGE_CAKE)
            >= 2)) {
      return new UseLink(itemId, "sven", "pandamonium.php?action=sven");
    } else {
      // No link if not finished so not accidentally used
      return null;
    }
  }

  private static String gnasir() {
    return KoLCharacter.isKingdomOfExploathing()
        ? "place.php?whichplace=exploathing_beach&action=expl_gnasir"
        : "place.php?whichplace=desertbeach&action=db_gnasir";
  }

  private static UseLink getNavigationLink(int itemId, String location) {
    String useType = null;
    String useLocation = null;
    boolean adventureResults = location.startsWith("fight.php");

    switch (itemId) {
        // Shops
      case ItemPool.FRESHWATER_FISHBONE -> {
        useType = "assemble";
        useLocation = "shop.php?whichshop=fishbones";
      }
      case ItemPool.FAT_LOOT_TOKEN -> {
        useType = String.valueOf(InventoryManager.getCount(ItemPool.FAT_LOOT_TOKEN));
        useLocation =
            KoLCharacter.isKingdomOfExploathing()
                ? "shop.php?whichshop=exploathing"
                : "shop.php?whichshop=damachine";
      }
      case ItemPool.REPLICA_MR_ACCESSORY -> {
        useType = "shop";
        useLocation = "shop.php?whichshop=mrreplica";
      }

        // Subject 37 File goes to Cell #37
      case ItemPool.SUBJECT_37_FILE -> {
        useType = "cell #37";
        useLocation = "cobbsknob.php?level=3&action=cell37";
      }

        // Guild quest items go to guild chief
      case ItemPool.BIG_KNOB_SAUSAGE, ItemPool.EXORCISED_SANDWICH -> {
        useType = "guild";
        useLocation = "guild.php?place=challenge";
      }
      case ItemPool.LOATHING_LEGION_JACKHAMMER -> {
        useType = "switch";
        useLocation = "inv_use.php?which=3&switch=1&whichitem=";
      }

        // Game Grid tokens get a link to the arcade.

      case ItemPool.GG_TOKEN -> {
        useType = "arcade";
        useLocation = "place.php?whichplace=arcade";
      }

        // Game Grid tickets get a link to the arcade redemption counter.

      case ItemPool.GG_TICKET -> {
        useType = "redeem";
        useLocation = "shop.php?whichshop=arcade";
      }

        // Strange leaflet gets a quick 'read' link which sends you
        // to the leaflet completion page.

      case ItemPool.STRANGE_LEAFLET -> {
        useType = "read";
        useLocation = "leaflet.php?action=auto";
      }

        // You want to give the rusty screwdriver to the Untinker, so
        // make it easy.

      case ItemPool.RUSTY_SCREWDRIVER -> {
        useType = "visit untinker";
        useLocation = "place.php?whichplace=forestvillage&action=fv_untinker";
      }

        // Hedge maze puzzle and hedge maze key have a link to the maze
        // for easy access.

      case ItemPool.HEDGE_KEY, ItemPool.PUZZLE_PIECE -> {
        useType = "maze";
        useLocation = "hedgepuzzle.php";
      }

        // Pixels have handy links indicating how many white pixels are
        // present in the player's inventory.

      case ItemPool.WHITE_PIXEL -> {
        if (KoLCharacter.isKingdomOfExploathing()) {
          useType = String.valueOf(InventoryManager.getCount(ItemPool.WHITE_PIXEL));
          useLocation = "shop.php?whichshop=exploathing";
          break;
        }
        return null;
      }

        // Special handling for star charts, lines, and stars, where
        // KoLmafia shows you how many of each you have.

      case ItemPool.STAR_CHART, ItemPool.STAR, ItemPool.LINE -> {
        useType =
            InventoryManager.getCount(ItemPool.STAR_CHART)
                + ","
                + InventoryManager.getCount(ItemPool.STAR)
                + ","
                + InventoryManager.getCount(ItemPool.LINE);
        useLocation = "shop.php?whichshop=starchart";
      }

        // Worthless items and the hermit permit get a link to the hermit.

      case ItemPool.WORTHLESS_TRINKET,
          ItemPool.WORTHLESS_GEWGAW,
          ItemPool.WORTHLESS_KNICK_KNACK,
          ItemPool.HERMIT_PERMIT -> {
        useType = "hermit";
        useLocation = "hermit.php";
      }

        // The different kinds of ores will only have a link if they're
        // the ones applicable to the trapper quest.

      case ItemPool.LINOLEUM_ORE,
          ItemPool.ASBESTOS_ORE,
          ItemPool.CHROME_ORE,
          ItemPool.LUMP_OF_COAL -> {
        if (location.startsWith("dwarffactory.php")) {
          useType = String.valueOf(InventoryManager.getCount(itemId));
          useLocation = "dwarfcontraption.php";
          break;
        }

        if (itemId != ItemDatabase.getItemId(Preferences.getString("trapperOre"))) {
          return null;
        }

        useType = String.valueOf(InventoryManager.getCount(itemId));
        useLocation = "place.php?whichplace=mclargehuge&action=trappercabin";
      }
      case ItemPool.GROARS_FUR, ItemPool.WINGED_YETI_FUR -> {
        useType = "trapper";
        useLocation = "place.php?whichplace=mclargehuge&action=trappercabin";
      }
      case ItemPool.FRAUDWORT, ItemPool.SHYSTERWEED, ItemPool.SWINDLEBLOSSOM -> {
        if (InventoryManager.getCount(ItemPool.FRAUDWORT) < 3
            || InventoryManager.getCount(ItemPool.SHYSTERWEED) < 3
            || InventoryManager.getCount(ItemPool.SWINDLEBLOSSOM) < 3) {
          return null;
        }

        useType = "galaktik";
        useLocation = "shop.php?whichshop=doc";
      }

        // Disintegrating sheet music gets a link which lets you sing it
        // to yourself. We'll call it "sing" for now.

      case ItemPool.SHEET_MUSIC -> {
        useType = "sing";
        useLocation =
            "curse.php?action=use&targetplayer=" + KoLCharacter.getPlayerId() + "&whichitem=";
      }

        // Link which uses the plans when you acquire the planks.

      case ItemPool.DINGY_PLANKS -> {
        if (!InventoryManager.hasItem(ItemPool.DINGHY_PLANS)) {
          return null;
        }

        useType = "plans";
        useLocation = "inv_use.php?which=3&whichitem=";
        itemId = ItemPool.DINGHY_PLANS;
      }

        // Link which uses the Knob map when you get the encryption key.

      case ItemPool.ENCRYPTION_KEY -> {
        if (!InventoryManager.hasItem(ItemPool.COBBS_KNOB_MAP)) {
          return null;
        }

        useType = "use map";
        useLocation = "inv_use.php?which=3&whichitem=";
        itemId = ItemPool.COBBS_KNOB_MAP;
      }

        // Link to the guild upon completion of the Citadel quest.

      case ItemPool.CITADEL_SATCHEL, ItemPool.THICK_PADDED_ENVELOPE -> {
        useType = "guild";
        useLocation = "guild.php?place=paco";
      }

        // Link to the guild when receiving guild quest items.

      case ItemPool.FERNSWARTHYS_KEY -> {
        // ...except that the guild gives you the key again
        if (location.startsWith("guild.php")) {
          useType = "ruins";
          useLocation = "fernruin.php";
          break;
        }
        useType = "guild";
        useLocation = "guild.php?place=ocg";
      }
      case ItemPool.DUSTY_BOOK -> {
        useType = "guild";
        useLocation = "guild.php?place=ocg";
      }

        // Link to the impassable rubble when you have 6 fizzing spore pods
      case ItemPool.FIZZING_SPORE_POD -> {
        if (InventoryManager.getCount(ItemPool.FIZZING_SPORE_POD) >= 6
            && QuestDatabase.isQuestBefore(Quest.NEMESIS, "step15")) {
          useType = "BOOOOOOM!";
          useLocation = "place.php?whichplace=nemesiscave&action=nmcave_rubble";
        }
      }

        // Link to the untinker if you find an abridged dictionary.

      case ItemPool.ABRIDGED -> {
        useType = "untinker";
        useLocation = "place.php?whichplace=forestvillage&action=fv_untinker";
      }

        // Link to the chasm if you just untinkered a dictionary.

      case ItemPool.BRIDGE,
          ItemPool.MORNINGWOOD_PLANK,
          ItemPool.HARDWOOD_PLANK,
          ItemPool.WEIRDWOOD_PLANK,
          ItemPool.THICK_CAULK,
          ItemPool.LONG_SCREW,
          ItemPool.BUTT_JOINT,
          ItemPool.SNOW_BOARDS,
          ItemPool.FANCY_OIL_PAINTING,
          ItemPool.BRIDGE_TRUSS -> {
        int urlEnd = OrcChasmRequest.getChasmProgress();
        if (urlEnd == 30) {
          break;
        }

        useType = "chasm";
        useLocation = "place.php?whichplace=orc_chasm&action=bridge" + urlEnd;
      }

        // Link to the frat house if you acquired a Spanish Fly

      case ItemPool.SPANISH_FLY -> {
        useType = String.valueOf(InventoryManager.getCount(itemId));
        useLocation = "adventure.php?snarfblat=27";
      }

        // Link to Big Brother if you pick up a sand dollar

      case ItemPool.SAND_DOLLAR -> {
        useType = String.valueOf(InventoryManager.getCount(itemId));
        useLocation = "monkeycastle.php?who=2";
      }

        // Link to the Old Man if you buy the damp old boot

      case ItemPool.DAMP_OLD_BOOT -> {
        useType = "old man";
        useLocation = "place.php?whichplace=sea_oldman&action=oldman_oldman";
      }
      case ItemPool.GUNPOWDER -> {
        useType = String.valueOf(InventoryManager.getCount(itemId));
        useLocation = IslandRequest.getPyroURL();
      }
      case ItemPool.TOWEL -> {
        useType = "fold";
        useLocation = "inv_use.php?which=3&whichitem=";
      }
      case ItemPool.GOLD_BOWLING_BALL,
          ItemPool.REALLY_DENSE_MEAT_STACK,
          ItemPool.SCARAB_BEETLE_STATUETTE -> {
        if (!adventureResults) break;
        return getCouncilLink(itemId);
      }
      case ItemPool.HOLY_MACGUFFIN, ItemPool.ED_HOLY_MACGUFFIN -> {
        return getCouncilLink(itemId);
      }

        // Link to the Pretentious Artist when you find his last tool

      case ItemPool.PRETENTIOUS_PAINTBRUSH,
          ItemPool.PRETENTIOUS_PALETTE,
          ItemPool.PRETENTIOUS_PAIL -> {
        if (!InventoryManager.hasItem(ItemPool.PRETENTIOUS_PAINTBRUSH)
            || !InventoryManager.hasItem(ItemPool.PRETENTIOUS_PALETTE)
            || !InventoryManager.hasItem(ItemPool.PRETENTIOUS_PAIL)) {
          return null;
        }

        useType = "artist";
        useLocation = "place.php?whichplace=town_wrong&action=townwrong_artist_quest";
      }
      case ItemPool.MOLYBDENUM_MAGNET,
          ItemPool.MOLYBDENUM_HAMMER,
          ItemPool.MOLYBDENUM_PLIERS,
          ItemPool.MOLYBDENUM_SCREWDRIVER,
          ItemPool.MOLYBDENUM_WRENCH -> {
        useType = "yossarian";
        useLocation = "bigisland.php?action=junkman";
      }
      case ItemPool.FILTHWORM_QUEEN_HEART -> {
        useType = "stand";
        useLocation = "bigisland.php?place=orchard&action=stand";
      }
      case ItemPool.SUGAR_SHEET -> {
        useType = "fold";
        useLocation = "shop.php?whichshop=sugarsheets";
      }

        // Link to the kegger if you acquired a phone number. That's
        // not useful, but having the item count in the link is

      case ItemPool.ORQUETTES_PHONE_NUMBER -> {
        useType = String.valueOf(InventoryManager.getCount(itemId));
        useLocation = "adventure.php?snarfblat=231";
      }
      case ItemPool.FORGED_ID_DOCUMENTS -> {
        if (KoLCharacter.isKingdomOfExploathing()) {
          return getCouncilLink(itemId);
        }

        useType = "vacation";
        useLocation = "adventure.php?snarfblat=355";
      }
      case ItemPool.ZEPPELIN_TICKET -> {
        useType = "zeppelin";
        useLocation = "adventure.php?snarfblat=385";
      }
      case ItemPool.BUS_PASS, ItemPool.IMP_AIR -> {
        if (!QuestDatabase.isQuestFinished(Quest.AZAZEL)
            && InventoryManager.getCount(ItemPool.AZAZELS_TUTU) == 0) {
          useType = String.valueOf(InventoryManager.getCount(itemId));
          useLocation = "pandamonium.php?action=moan";
        }
      }
      case ItemPool.HACIENDA_KEY -> {
        useType = String.valueOf(InventoryManager.getCount(itemId));
        useLocation = "volcanoisland.php?action=tniat&pwd=" + GenericRequest.passwordHash;
      }
      case ItemPool.NOSTRIL_OF_THE_SERPENT -> {
        if (!InventoryManager.hasItem(ItemPool.STONE_WOOL) || KoLCharacter.inGLover()) {
          return null;
        }

        itemId = ItemPool.STONE_WOOL;
        useType = "stone wool";
        useLocation = "inv_use.php?which=3&whichitem=";
      }
      case ItemPool.MOSS_COVERED_STONE_SPHERE -> {
        useType = "use sphere";
        useLocation = "adventure.php?snarfblat=346";
      }
      case ItemPool.DRIPPING_STONE_SPHERE -> {
        useType = "use sphere";
        useLocation = "adventure.php?snarfblat=347";
      }
      case ItemPool.CRACKLING_STONE_SPHERE -> {
        useType = "use sphere";
        useLocation = "adventure.php?snarfblat=348";
      }
      case ItemPool.SCORCHED_STONE_SPHERE -> {
        useType = "use sphere";
        useLocation = "adventure.php?snarfblat=349";
      }
      case ItemPool.STONE_ROSE -> {
        useType = "gnasir";
        useLocation = UseLinkDecorator.gnasir();
      }
      case ItemPool.WORM_RIDING_MANUAL_PAGE -> {
        int count = InventoryManager.getCount(itemId);
        return count < 15
            ? new UseLink(itemId, count)
            : new UseLink(itemId, count, "gnasir", UseLinkDecorator.gnasir());
      }
      case ItemPool.FIRST_PIZZA,
          ItemPool.LACROSSE_STICK,
          ItemPool.EYE_OF_THE_STARS,
          ItemPool.STANKARA_STONE,
          ItemPool.MURPHYS_FLAG,
          ItemPool.SHIELD_OF_BROOK -> {
        useType = "copperhead club";
        useLocation = "adventure.php?snarfblat=383";
      }
      case ItemPool.GOLD_PIECE -> {
        return new UseLink(itemId, InventoryManager.getCount(itemId));
      }
      case ItemPool.SPOOKYRAVEN_NECKLACE -> {
        useType = "talk to Lady Spookyraven";
        useLocation = "place.php?whichplace=manor1&action=manor1_ladys";
      }
      case ItemPool.POWDER_PUFF, ItemPool.FINEST_GOWN, ItemPool.DANCING_SHOES -> {
        if (!InventoryManager.hasItem(ItemPool.POWDER_PUFF)
            || !InventoryManager.hasItem(ItemPool.FINEST_GOWN)
            || !InventoryManager.hasItem(ItemPool.DANCING_SHOES)) {
          return null;
        }

        useType = "talk to Lady Spookyraven";
        useLocation = "place.php?whichplace=manor2&action=manor2_ladys";
      }
      case ItemPool.BABY_GHOSTS -> {
        useType = "talk to Lady Spookyraven";
        useLocation = "place.php?whichplace=manor3&action=manor3_ladys";
      }
      case ItemPool.CRUMBLING_WHEEL -> {
        int count1 = InventoryManager.getCount(itemId);
        int count2 = InventoryManager.getCount(ItemPool.TOMB_RATCHET);
        useType = count1 + "+" + count2;
        return !Preferences.getBoolean("controlRoomUnlock")
            ? new UseLink(itemId, count1, useType, "javascript:return false;")
            : new UseLink(
                itemId, count1, useType, "place.php?whichplace=pyramid&action=pyramid_control");
      }
      case ItemPool.TOMB_RATCHET -> {
        int count1 = InventoryManager.getCount(itemId);
        int count2 = InventoryManager.getCount(ItemPool.CRUMBLING_WHEEL);
        useType = count2 + "+" + count1;
        return !Preferences.getBoolean("controlRoomUnlock")
            ? new UseLink(itemId, count1, useType, "javascript:return false;")
            : new UseLink(
                itemId, count1, useType, "place.php?whichplace=pyramid&action=pyramid_control");
      }
      case ItemPool.PACK_OF_SMOKES -> {
        int count = InventoryManager.getCount(itemId);
        return count < 10
            ? new UseLink(itemId, count)
            : new UseLink(
                itemId,
                count,
                "radio",
                "place.php?whichplace=airport_spooky&action=airport2_radio");
      }
      case ItemPool.EXPERIMENTAL_SERUM_P00 -> {
        int count = InventoryManager.getCount(itemId);
        return count < 5 && QuestDatabase.isQuestStarted(Quest.SERUM)
            ? new UseLink(itemId, count)
            : new UseLink(
                itemId,
                count,
                "radio",
                "place.php?whichplace=airport_spooky&action=airport2_radio");
      }
      case ItemPool.MEATSMITH_CHECK -> {
        return new UseLink(itemId, 1, "visit meatsmith", "shop.php?whichshop=meatsmith");
      }
      case ItemPool.NO_HANDED_PIE -> {
        return new UseLink(itemId, 1, "visit armorer", "shop.php?whichshop=armory");
      }
      case ItemPool.ODD_SILVER_COIN -> {
        return new UseLink(itemId, 1, "spend", "shop.php?whichshop=cindy");
      }
      case ItemPool.SPANT_CHITIN, ItemPool.SPANT_TENDON -> {
        useType = "assemble";
        useLocation = "shop.php?whichshop=spant";
      }
      case ItemPool.RUBEE -> {
        useType = "spend";
        if (KoLCharacter.hasEquipped(ItemPool.FANTASY_REALM_GEM)) {
          useLocation = "shop.php?whichshop=fantasyrealm";
        }
      }
      case ItemPool.BLACK_SLIME_GLOB, ItemPool.GREEN_SLIME_GLOB, ItemPool.ORANGE_SLIME_GLOB -> {
        int slimeCount = InventoryManager.getCount(itemId);
        useType = "use (" + slimeCount + ")";
        useLocation = "shop.php?whichshop=voteslime";
      }
      case ItemPool.SPINMASTER -> {
        useType = "lathe";
        useLocation = "shop.php?whichshop=lathe";
      }
      case ItemPool.MILK_CAP, ItemPool.DRINK_CHIT -> {
        useType = "spend";
        useLocation = "shop.php?whichshop=olivers";
      }
      case ItemPool.THE_SOTS_PARCEL -> {
        useType = "return to sot";
        useLocation = "place.php?whichplace=speakeasy&action=olivers_sot";
      }
      case ItemPool.DINOSAUR_DROPPINGS -> {
        useType = "turn in";
        useLocation = "place.php?whichplace=dinorf&action=dinorf_owner";
      }
      case ItemPool.DISTILLED_SEAL_BLOOD,
          ItemPool.TURTLE_CHAIN,
          ItemPool.HIGH_OCTANE_OLIVE_OIL,
          ItemPool.PEPPERCORNS_OF_POWER,
          ItemPool.VIAL_OF_MOJO,
          ItemPool.GOLDEN_REEDS -> {
        // When we "acquire" the reward from Beelzebozo, provide a
        // link to the guild and, if we already have a hammer and can
        // smith the Legendary Epic Weapon, a link to do that.
        Set<AdventureResult> creations = ConcoctionDatabase.getKnownUses(itemId);
        // There should be exactly one creation.
        CreateItemRequest creator = null;
        for (AdventureResult creation : creations) {
          creator = CreateItemRequest.getInstance(creation.getItemId());
          // This returns null if not permitted.
          // E.g., you don't have a hammer.
          if (creator != null) {
            // Create the URL
            creator.reconstructFields();
            creator.buildFullURL();
            break;
          }
        }

        ArrayList<UseLink> uses = new ArrayList<>();
        // scg = Same Class in Guild
        uses.add(new UseLink(itemId, "guild", "guild.php?place=scg"));
        if (creator != null) {
          UseLink createLink = new UseLink(itemId, 1, "smith", creator.getURLString());
          uses.add(createLink);
        }
        return new UsesLink(uses.toArray(new UseLink[0]));
      }
      case ItemPool.PROFESSOR_WHAT_GARMENT -> {
        return new UseLink(
            itemId, "visit Melvign", "place.php?whichplace=mountains&action=mts_melvin");
      }
      case ItemPool.GLOB_OF_WET_PAPER -> {
        useType = "think";
        useLocation = "shop.php?whichshop=showerthoughts";
      }
    }

    if (useType == null || useLocation == null) {
      return null;
    }

    return new UseLink(itemId, useType, useLocation);
  }

  public static class UseLink {
    private int itemId;
    private int itemCount;
    private String useType;
    private String useLocation;
    private boolean inline;

    protected UseLink() {}

    public UseLink(String useType, String useLocation) {
      this(-1, 0, useType, useLocation, false);
    }

    public UseLink(int itemId, String useType, String useLocation) {
      this(itemId, 1, useType, useLocation);
    }

    public UseLink(int itemId, int itemCount, String useLocation) {
      this(itemId, itemCount, String.valueOf(itemCount), useLocation);
    }

    public UseLink(int itemId, int itemCount, String useType, String useLocation) {
      this(itemId, itemCount, useType, useLocation, useLocation.startsWith("inv"));
    }

    public UseLink(int itemId, int itemCount) {
      // This is just a counter
      this(itemId, itemCount, "javascript:return false;");
    }

    public UseLink(int itemId, int itemCount, String useType, String useLocation, boolean inline) {
      this.itemId = itemId;
      this.itemCount = itemCount;
      // Quote '$' characters so that Matcher.appendReplacement
      // does not throw an  exception.
      this.useType = StringUtilities.globalStringReplace(useType, "$", "\\$");
      this.useLocation = useLocation;
      this.inline = inline;

      if (this.useLocation.endsWith("=")) {
        this.useLocation += this.itemId;
      }

      if (this.useLocation.contains("?")
          && !this.useLocation.contains("phash=")
          &&
          // It's not harmful to include the password hash
          // when it is unnecessary, but it is not pretty
          !this.useLocation.startsWith("adventure.php")
          && !this.useLocation.startsWith("place.php")
          && !this.useLocation.startsWith("council.php")
          && !this.useLocation.startsWith("guild.php")
          && !this.useLocation.startsWith("wand.php")
          && !this.useLocation.startsWith("diary.php")
          && !this.useLocation.startsWith("volcanoisland.php")
          && !this.useLocation.startsWith("cobbsknob.php")) {
        this.useLocation += "&pwd=" + GenericRequest.passwordHash;
      }
    }

    public int getItemId() {
      return this.itemId;
    }

    public int getItemCount() {
      return this.itemCount;
    }

    public String getUseType() {
      return this.useType;
    }

    public String getUseLocation() {
      return this.useLocation;
    }

    public boolean showInline() {
      return this.inline && Preferences.getBoolean("relayUsesInlineLinks");
    }

    public String getItemHTML() {
      if (this.useLocation.equals("#")) {
        return "<font size=1>[<a href=\"javascript:"
            + "multiUse('multiuse.php',"
            + this.itemId
            + ","
            + this.itemCount
            + ");void(0);\">"
            + this.useType
            + "</a>]</font>";
      }

      if (!this.showInline()) {
        return "<font size=1>[<a href=\""
            + this.useLocation
            + "\">"
            + this.useType
            + "</a>]</font>";
      }

      String[] pieces = this.useLocation.split("\\?");

      return "<font size=1>[<a href=\"javascript:"
          + "singleUse('"
          + pieces[0].trim()
          + "','"
          + pieces[1].trim()
          + "&ajax=1');void(0);\">"
          + this.useType
          + "</a>]</font>";
    }
  }

  public static class UsesLink extends UseLink {
    private final UseLink[] links;

    public UsesLink(UseLink[] links) {
      this.links = links;
    }

    @Override
    public String getItemHTML() {
      StringBuilder buf = new StringBuilder();
      for (int i = 0; i < this.links.length; ++i) {
        if (i > 0) buf.append("&nbsp;");
        buf.append(this.links[i].getItemHTML());
      }
      return buf.toString();
    }
  }
}
