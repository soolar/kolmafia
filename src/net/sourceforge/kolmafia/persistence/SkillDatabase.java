package net.sourceforge.kolmafia.persistence;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import net.sourceforge.kolmafia.*;
import net.sourceforge.kolmafia.equipment.Slot;
import net.sourceforge.kolmafia.objectpool.EffectPool;
import net.sourceforge.kolmafia.objectpool.ItemPool;
import net.sourceforge.kolmafia.objectpool.SkillPool;
import net.sourceforge.kolmafia.preferences.Preferences;
import net.sourceforge.kolmafia.request.FightRequest;
import net.sourceforge.kolmafia.request.UseSkillRequest;
import net.sourceforge.kolmafia.request.UseSkillRequest.BuffTool;
import net.sourceforge.kolmafia.utilities.FileUtilities;
import net.sourceforge.kolmafia.utilities.LockableListFactory;
import net.sourceforge.kolmafia.utilities.StringUtilities;

public class SkillDatabase {
  public enum SkillTag {
    // possible tags in data/classskills.txt
    PASSIVE("passive"),
    COMBAT("combat"),
    NONCOMBAT("nc"),
    ITEM("item"),
    HEAL("heal"),
    EFFECT("effect"),
    SELF("self"),
    OTHER("other"),
    SONG("song"),
    EXPRESSION("expression"),
    WALK("walk"),
    SPELL("spell");

    public final String name;
    private static final Map<String, SkillTag> skillTagByName = new HashMap<>();

    SkillTag(String name) {
      this.name = name;
    }

    public static SkillTag byName(String name) {
      var lookup = skillTagByName.get(name);
      if (lookup != null) return lookup;
      var search = Arrays.stream(SkillTag.values()).filter(x -> x.name.equals(name)).findAny();
      search.ifPresent(x -> skillTagByName.put(name, x));
      return search.orElse(null);
    }
  }

  private static final Map<Integer, String> nameById = new TreeMap<>();
  private static final Map<String, int[]> skillIdSetByName = new TreeMap<>();
  private static final Map<Integer, String> imageById = new TreeMap<>();
  private static final Map<Integer, Long> mpConsumptionById = new HashMap<>();
  private static final Map<Integer, EnumSet<SkillTag>> skillTagsById = new TreeMap<>();
  private static final Map<Integer, Integer> durationById = new HashMap<>();
  private static final Map<Integer, Integer> levelById = new HashMap<>();
  private static final Map<Integer, Boolean> permableById = new HashMap<>();
  private static final Map<Integer, Integer> maxLevelById = new HashMap<>();
  private static final Map<Category, List<String>> skillsByCategory = new EnumMap<>(Category.class);
  private static final Map<Integer, Category> skillCategoryById = new HashMap<>();
  // Per-user data. Needs to be reset when log in as a new user.
  private static final Map<Integer, Integer> castsById = new HashMap<>();

  public enum Category {
    UNKNOWN("unknown"),
    UNCATEGORIZED("uncategorized"),
    SEAL_CLUBBER("seal clubber"), // 1xxx
    TURTLE_TAMER("turtle tamer"), // 2xxx
    PASTAMANCER("pastamancer"), // 3xxx
    SAUCEROR("sauceror"), // 4xxx
    DISCO_BANDIT("disco bandit"), // 5xxx
    ACCORDION_THIEF("accordion thief"), // 6xxx
    CONDITIONAL("conditional"), // 7xxx
    MR_SKILLS("mr. skills"), // 8xxx
    NINE("9XXX"), // 9xxx
    TEN("10XXX"), // 10xxx
    AVATAR_OF_BORIS("avatar of Boris"), // 11xxx
    ZOMBIE_MASTER("zombie master"), // 12xxx
    THIRTEEN("13XXX"), // 13xxx
    AVATAR_OF_JARLSBERG("Avatar of Jarlsberg"), // 14xxx
    AVATAR_OF_SNEAKY_PETE("Avatar of Sneaky Pete"), // 15xxx
    HEAVY_RAINS("Heavy Rains"), // 16xxx
    ED("Ed the Undying"), // 17xxx
    COW_PUNCHER("Cow Puncher"), // 18xxx
    BEANSLINGER("Beanslinger"), // 19xxx
    SNAKE_OILER("Snake Oiler"), // 20xxx
    SOURCE("The Source"), // 21xxx
    NUCLEAR_AUTUMN("Nuclear Autumn"), // 22xxx
    GELATINOUS_NOOB("Gelatinous Noob"), // 23xxx
    VAMPYRE("Vampyre"), // 24xxx
    PLUMBER("Plumber"), // 25xxx
    TWENTY_SIX("26XXX"), // 26xxx
    GREY_YOU("Grey You"), // 27xxx
    PIG_SKINNER("Pig Skinner"), // 28xxx
    CHEESE_WIZARD("Cheese Wizard"), // 29xxx
    JAZZ_AGENT("Jazz Agent"), // 30xxx
    // The following are convenience categories, not implied by skill id
    GNOME_SKILLS("gnome trainer"),
    BAD_MOON("bad moon");

    public final String name;
    static final Category[] VALUES = values();

    Category(String name) {
      this.name = name;
    }

    public static Category bySkillId(int id) {
      int categoryId = id / 1000 + 1; // avoid unknown at start
      // length check, minus gnome / bad moon
      if (categoryId >= Category.VALUES.length - 2) {
        return Category.UNKNOWN;
      }

      return switch (id) {
        case SkillPool.SMILE_OF_MR_A,
            SkillPool.SNOWCONE,
            SkillPool.STICKER,
            SkillPool.SUGAR,
            SkillPool.CLIP_ART,
            SkillPool.RAD_LIB,
            SkillPool.SMITHSNESS,
            SkillPool.CANDY_HEART,
            SkillPool.PARTY_FAVOR,
            SkillPool.LOVE_SONG,
            SkillPool.BRICKOS,
            SkillPool.DICE,
            SkillPool.RESOLUTIONS,
            SkillPool.TAFFY,
            SkillPool.HILARIOUS,
            SkillPool.TASTEFUL,
            SkillPool.CARDS,
            SkillPool.GEEKY,
            SkillPool.CONFISCATOR -> Category.MR_SKILLS;
        case SkillPool.OBSERVATIOGN,
            SkillPool.GNEFARIOUS_PICKPOCKETING,
            SkillPool.TORSO,
            SkillPool.GNOMISH_HARDINESS,
            SkillPool.COSMIC_UNDERSTANDING -> Category.GNOME_SKILLS;
        case SkillPool.LUST,
            SkillPool.GLUTTONY,
            SkillPool.GREED,
            SkillPool.SLOTH,
            SkillPool.WRATH,
            SkillPool.ENVY,
            SkillPool.PRIDE -> Category.BAD_MOON;
        case SkillPool.MUG_FOR_THE_AUDIENCE -> Category.AVATAR_OF_SNEAKY_PETE;
        default ->

        // Moxious maneuver has a 7000 id, but
        // it's not gained by equipment.

        Category.VALUES[categoryId];
      };
    }
  }

  private static final int[] NO_SKILL_IDS = new int[0];
  private static final AdventureResult SUPER_SKILL = EffectPool.get(EffectPool.SUPER_SKILL);
  private static final ArrayList<String> skillNames = new ArrayList<>();
  private static String[] canonicalNames = new String[0];

  static {
    SkillDatabase.reset();
  }

  private SkillDatabase() {}

  public static void reset() {
    for (var category : Category.VALUES) {
      SkillDatabase.skillsByCategory.put(category, new ArrayList<>());
    }

    try (BufferedReader reader =
        FileUtilities.getVersionedReader("classskills.txt", KoLConstants.CLASSSKILLS_VERSION)) {
      String[] data;

      while ((data = FileUtilities.readData(reader)) != null) {
        if (data.length < 6) {
          continue;
        }

        Integer skillId = Integer.valueOf(data[0]);
        String name = data[1];
        String image = data[2];
        EnumSet<SkillTag> tags = parseTags(data[3]);
        Long mp = Long.valueOf(data[4]);
        Integer duration = Integer.valueOf(data[5]);
        Map<String, String> attributes = attributesToMap(data.length > 6 ? data[6] : null);
        SkillDatabase.addSkill(skillId, name, image, tags, mp, duration, attributes);
      }
    } catch (IOException e) {
      StaticEntity.printStackTrace(e);
    }

    SkillDatabase.canonicalNames = new String[SkillDatabase.skillIdSetByName.size()];
    SkillDatabase.skillIdSetByName.keySet().toArray(SkillDatabase.canonicalNames);
  }

  private static EnumSet<SkillTag> parseTags(String data) {
    var tags = data.split(",");
    var set = EnumSet.noneOf(SkillTag.class);
    for (String tag : tags) {
      var parsed = SkillTag.byName(tag);
      if (parsed == null) {
        throw new IllegalStateException("failed to parse tag " + tag);
      }
      set.add(parsed);
    }
    return set;
  }

  private static Map<String, String> attributesToMap(final String attributeString) {
    if (attributeString == null) return Map.of();

    return Arrays.stream(attributeString.split(","))
        .map(
            attr -> {
              var parts = attr.split(":");
              return Map.entry(parts[0].trim().toLowerCase(), parts[1].trim().toLowerCase());
            })
        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  public static void resetCasts() {
    SkillDatabase.castsById.clear();
  }

  private static void addIdToName(String canonicalName, int skillId) {
    int[] idSet = SkillDatabase.skillIdSetByName.get(canonicalName);
    int[] newSet;

    if (idSet == null) {
      newSet = new int[1];
    }
    // *** This assumes the array is sorted
    else if (Arrays.binarySearch(idSet, skillId) >= 0) {
      return;
    } else {
      newSet = Arrays.copyOf(idSet, idSet.length + 1);
    }

    newSet[newSet.length - 1] = skillId;
    // *** Make it so
    Arrays.sort(newSet);
    SkillDatabase.skillIdSetByName.put(canonicalName, newSet);
  }

  private static void addSkill(
      final Integer skillId,
      final String name,
      final String image,
      final EnumSet<SkillTag> tags,
      final Long mpConsumption,
      final Integer duration,
      final Map<String, String> attributes) {
    String canonicalName = StringUtilities.getCanonicalName(name);
    SkillDatabase.nameById.put(skillId, name);
    SkillDatabase.addIdToName(canonicalName, skillId);

    if (image != null) {
      SkillDatabase.imageById.put(skillId, image);
    }
    SkillDatabase.skillTagsById.put(skillId, tags);

    SkillDatabase.mpConsumptionById.put(skillId, mpConsumption);
    SkillDatabase.durationById.put(skillId, duration);

    for (var attr : attributes.entrySet()) {
      var value = attr.getValue();
      switch (attr.getKey()) {
        case "level" -> SkillDatabase.levelById.put(skillId, Integer.valueOf(value));
        case "permable" -> SkillDatabase.permableById.put(skillId, Boolean.valueOf(value));
        case "max level" -> SkillDatabase.maxLevelById.put(skillId, Integer.valueOf(value));
      }
    }

    Category category = Category.bySkillId(skillId);
    if (category == Category.UNKNOWN) {
      return;
    }

    SkillDatabase.skillCategoryById.put(skillId, category);
    SkillDatabase.skillsByCategory.get(category).add(name);

    SkillDatabase.castsById.put(skillId, 0);
  }

  public static final List<String> getSkillsByCategory(Category category) {
    if (category == null) {
      return new ArrayList<>();
    }

    List<String> skills = SkillDatabase.skillsByCategory.get(category);

    if (skills == null) {
      return new ArrayList<>();
    }

    return skills;
  }

  /**
   * Returns the name for an skill, given its Id.
   *
   * @param skillId The Id of the skill to lookup
   * @return The name of the corresponding skill
   */
  public static final String getSkillName(final int skillId) {
    return SkillDatabase.nameById.get(skillId);
  }

  public static final String getSkillDisplayName(final String skillName) {
    if (skillName.startsWith("[")) {
      int ind = skillName.indexOf("]");
      if (ind > 0) {
        int skillId = StringUtilities.parseInt(skillName.substring(1, ind));
        return getSkillName(skillId);
      }
    }
    return skillName;
  }

  public static final String getPrettySkillName(final int skillId) {
    String name = SkillDatabase.nameById.get(skillId);
    switch (skillId) {
      case SkillPool.DART_PART1,
          SkillPool.DART_PART2,
          SkillPool.DART_PART3,
          SkillPool.DART_PART4,
          SkillPool.DART_PART5,
          SkillPool.DART_PART6,
          SkillPool.DART_PART7,
          SkillPool.DART_PART8 -> {
        // Darts: Throw at %part1
        String part = FightRequest.dartSkillToPart.get(skillId);
        if (part != null) {
          name = "Darts: Throw at " + part;
        }
        return name;
      }
      case SkillPool.LEFT_PUNCH -> {
        return zootCombatSkillName(name, "zootGraftedHandLeftFamiliar");
      }
      case SkillPool.RIGHT_PUNCH -> {
        return zootCombatSkillName(name, "zootGraftedHandRightFamiliar");
      }
      case SkillPool.LEFT_KICK -> {
        return zootCombatSkillName(name, "zootGraftedFootLeftFamiliar");
      }
      case SkillPool.RIGHT_KICK -> {
        return zootCombatSkillName(name, "zootGraftedFootRightFamiliar");
      }
    }
    return name;
  }

  private static String zootCombatSkillName(String name, String pref) {
    int familiarId = Preferences.getInteger(pref);
    if (familiarId == 0) {
      return name;
    }
    String fam = FamiliarDatabase.getFamiliarName(familiarId);
    if (fam != null) {
      return StringUtilities.singleStringReplace(name, "%n", fam);
    }
    return name;
  }

  static final Set<Integer> idKeySet() {
    return SkillDatabase.nameById.keySet();
  }

  /**
   * Returns the Id number for an skill, given its name.
   *
   * @param skillName The name of the skill to lookup
   * @return The Id number of the corresponding skill
   */
  public static final int getSkillId(final String skillName) {
    return SkillDatabase.getSkillId(skillName, false);
  }

  public static final int getSkillId(final String skillName, final boolean exact) {
    if (skillName == null) {
      return -1;
    }

    // If name starts with [nnnn] then that is explicitly the skill id
    if (skillName.startsWith("[")) {
      int index = skillName.indexOf("]");
      if (index > 0) {
        String idString = skillName.substring(1, index);
        if (StringUtilities.isNumeric(idString)) {
          return StringUtilities.parseInt(idString);
        }
      }
    }

    int[] ids = SkillDatabase.skillIdSetByName.get(StringUtilities.getCanonicalName(skillName));

    if (ids != null) {
      if (exact && ids.length > 1) {
        return -1;
      }
      return ids[ids.length - 1];
    }

    if (exact) {
      return -1;
    }

    List<String> names = SkillDatabase.getMatchingNames(skillName);
    if (names.size() == 1) {
      return SkillDatabase.getSkillId(names.get(0), true);
    }

    return -1;
  }

  public static final int getSkillId(final String skillName, final String classicTypeName) {
    if (skillName == null) {
      return -1;
    }

    int[] ids = SkillDatabase.skillIdSetByName.get(StringUtilities.getCanonicalName(skillName));

    if (ids == null) {
      return -1;
    }

    for (int skillId : ids) {
      if (classicTypeName.equals(SkillDatabase.getSkillTypeName(skillId))) {
        return skillId;
      }
    }

    return -1;
  }

  public static final int[] getSkillIds(final String skillName, final boolean exact) {
    if (skillName == null) {
      return NO_SKILL_IDS;
    }

    // If name starts with [nnnn] then that is explicitly the effect id
    if (skillName.startsWith("[")) {
      int index = skillName.indexOf("]");
      if (index > 0) {
        String idString = skillName.substring(1, index);
        if (StringUtilities.isNumeric(idString)) {
          int skillId = StringUtilities.parseInt(idString);
          int[] ids = new int[1];
          ids[0] = skillId;
          return ids;
        }
      }
    }

    int[] ids = SkillDatabase.skillIdSetByName.get(StringUtilities.getCanonicalName(skillName));

    if (ids != null) {
      if (exact && ids.length > 1) {
        return NO_SKILL_IDS;
      }
      return ids;
    }

    if (exact) {
      return NO_SKILL_IDS;
    }

    List<String> names = SkillDatabase.getMatchingNames(skillName);
    if (names.size() != 1) {
      return NO_SKILL_IDS;
    }

    ids = skillIdSetByName.get(StringUtilities.getCanonicalName(names.get(0)));

    return ids != null ? ids : NO_SKILL_IDS;
  }

  /** Returns a list of all skills which contain the given substring. */
  public static final List<String> getMatchingNames(final String substring) {
    // If name starts with [nnnn] then that is explicitly the skill id
    if (substring.startsWith("[")) {
      int index = substring.indexOf("]");
      if (index > 0) {
        String idString = substring.substring(1, index);
        if (StringUtilities.isNumeric(idString)) {
          List<String> list = new ArrayList<>();
          list.add(substring);
          return list;
        }
      }
    }
    return StringUtilities.getMatchingNames(SkillDatabase.canonicalNames, substring);
  }

  /**
   * Returns the level for an skill, given its Id.
   *
   * @param skillId The Id of the skill to lookup
   * @return The level of the corresponding skill
   */
  public static final int getSkillLevel(final int skillId) {
    Integer level = SkillDatabase.levelById.get(skillId);
    return level == null ? -1 : level;
  }

  public static final int getSkillPurchaseCost(final int skillId) {
    if (!(1000 <= skillId && skillId < 7000)) {
      return 0;
    }

    return switch (SkillDatabase.getSkillLevel(skillId)) {
      default -> 0;
      case 1 -> 125;
      case 2 -> 250;
      case 3 -> 500;
      case 4 -> 750;
      case 5 -> 1250;
      case 6 -> 1750;
      case 7 -> 2500;
      case 8 -> 3250;
      case 9 -> 4000;
      case 10 -> 5000;
      case 11 -> 6250;
      case 12 -> 7500;
      case 13 -> 10000;
      case 14 -> 12500;
      case 15 -> 15000;
    };
  }

  public static final int classSkillsBase() {
    AscensionClass ascensionClass = KoLCharacter.getAscensionClass();
    return ascensionClass == null ? 0 : ascensionClass.getSkillBase();
  }

  /**
   * Returns the tags for an skill, given its Id.
   *
   * @param skillId The Id of the skill to lookup
   * @return The type of the corresponding skill
   */
  public static final EnumSet<SkillTag> getSkillTags(final int skillId) {
    var skillTags = SkillDatabase.skillTagsById.get(skillId);
    return skillTags == null ? EnumSet.noneOf(SkillTag.class) : skillTags;
  }

  public static final String getSkillTypeName(final int skillId) {
    var tags = SkillDatabase.skillTagsById.get(skillId);
    if (tags == null) {
      return "unknown";
    }
    // passive
    if (tags.contains(SkillTag.PASSIVE)) {
      if (tags.contains(SkillTag.COMBAT)) {
        return "combat/passive";
      }
      if (tags.contains(SkillTag.NONCOMBAT) && tags.contains(SkillTag.HEAL)) {
        return "noncombat remedy/passive";
      }
      return "passive";
    }
    // combat
    if (tags.contains(SkillTag.COMBAT)) {
      if (tags.contains(SkillTag.NONCOMBAT) && tags.contains(SkillTag.HEAL)) {
        return "combat/noncombat remedy";
      }
      return "combat";
    }
    // noncombat
    if (tags.contains(SkillTag.ITEM)) {
      return "summon";
    }
    if (tags.contains(SkillTag.HEAL)) {
      return "remedy";
    }
    // noncombat buffs
    if (tags.contains(SkillTag.WALK)) {
      return "walk";
    }
    if (tags.contains(SkillTag.EXPRESSION)) {
      return "expression";
    }
    if (tags.contains(SkillTag.SONG)) {
      return "song";
    }
    if (tags.contains(SkillTag.OTHER)) {
      return "buff";
    }
    if (tags.contains(SkillTag.SELF)) {
      return "self-only";
    }
    return "unknown";
  }

  public static final Category getSkillCategory(final int skillId) {
    Category cat = SkillDatabase.skillCategoryById.get(skillId);
    return cat == null ? Category.UNKNOWN : cat;
  }

  /**
   * Returns the image for an skill, given its Id.
   *
   * @param skillId The Id of the skill to lookup
   * @return The type of the corresponding skill
   */
  public static final String getSkillImage(final int skillId) {
    return SkillDatabase.imageById.get(skillId);
  }

  /**
   * Returns how much MP is consumed by using the skill with the given Id.
   *
   * @param skillId The id of the skill to lookup
   * @return The MP consumed by the skill, or 0 if unknown
   */
  public static final long getMPConsumptionById(final int skillId) {
    if (isLibramSkill(skillId)) {
      return libramSkillMPConsumption();
    }

    AscensionClass classType = null;
    boolean thrallReduced = false;
    boolean terminal = false;

    // The following funkiness seems to work around a compiler bug in 17.0.3
    // and 17.0.5 that makes the JVM to crash.
    boolean isNonCombat = SkillDatabase.isNonCombat(skillId);
    boolean inFight = (FightRequest.getCurrentRound() > 0);
    boolean isCombat = SkillDatabase.isCombat(skillId) && (!isNonCombat || inFight);

    switch (skillId) {
      case SkillPool.CLOBBER:
        classType = AscensionClass.SEAL_CLUBBER;
        break;
      case SkillPool.TOSS:
        classType = AscensionClass.TURTLE_TAMER;
        break;
      case SkillPool.SPAGHETTI_SPEAR:
        classType = AscensionClass.PASTAMANCER;
        break;
      case SkillPool.SALSABALL:
        classType = AscensionClass.SAUCEROR;
        break;
      case SkillPool.SUCKERPUNCH:
        classType = AscensionClass.DISCO_BANDIT;
        break;
      case SkillPool.SING:
        classType = AscensionClass.ACCORDION_THIEF;
        break;
      case SkillPool.MILD_CURSE:
        classType = AscensionClass.ED;
        break;

      case SkillPool.MAGIC_MISSILE:
        return Math.max(
            Math.min((KoLCharacter.getLevel() + 3) / 2, 6) + KoLCharacter.getManaCostAdjustment(),
            1);

      case SkillPool.STRINGOZZI:
      case SkillPool.RAVIOLI_SHURIKENS:
      case SkillPool.CANNELLONI_CANNON:
      case SkillPool.STUFFED_MORTAR_SHELL:
      case SkillPool.WEAPON_PASTALORD:
        if (KoLCharacter.currentPastaThrall() != PastaThrallData.NO_THRALL
            && KoLCharacter.hasSkill(SkillPool.THRALL_UNIT_TACTICS)) {
          thrallReduced = true;
        }
        break;

      case SkillPool.EXTRACT:
      case SkillPool.DIGITIZE:
      case SkillPool.COMPRESS:
      case SkillPool.DUPLICATE:
      case SkillPool.PORTSCAN:
      case SkillPool.TURBO:
        terminal = true;
        break;

      case SkillPool.STACK_LUMPS:
        return SkillDatabase.stackLumpsCost();

      case SkillPool.SEEK_OUT_A_BIRD:
        int birds = Preferences.getInteger("_birdsSoughtToday");
        return SkillDatabase.birdSkillMPConsumption(birds);
    }

    if (classType != null) {
      return KoLCharacter.getAscensionClass() == classType
          ? 0
          : Math.max(1 + KoLCharacter.getManaCostAdjustment(), 1);
    }

    if (!SkillDatabase.isCombat(skillId) && !SkillDatabase.isNonCombat(skillId)) {
      return 0;
    }

    if (isCombat && KoLConstants.activeEffects.contains(SkillDatabase.SUPER_SKILL)) {
      return 0;
    }

    Long mpConsumption = SkillDatabase.mpConsumptionById.get(skillId);

    if (mpConsumption == null) {
      return 0;
    }

    int cost = mpConsumption.intValue();
    if (cost == 0) {
      return 0;
    }

    if (thrallReduced) {
      cost = cost / 2;
    }

    if (terminal) {
      cost -= Preferences.getInteger("sourceTerminalSpam");
      if (Preferences.getString("sourceTerminalChips").contains("ASHRAM")) {
        cost -= 5;
      }
    }

    int adjustment = KoLCharacter.getManaCostAdjustment(isCombat);
    return Math.max(cost + adjustment, 1);
  }

  public static final boolean hasVariableMpCost(final int skillId) {
    return SkillDatabase.isLibramSkill(skillId) || skillId == SkillPool.SEEK_OUT_A_BIRD;
  }

  /**
   * Determines if a skill comes from a Libram
   *
   * @param skillId The Id of the skill to lookup
   * @return true if it comes from a Libram
   */
  public static final boolean isLibramSkill(final int skillId) {
    return switch (skillId) {
      case SkillPool.CANDY_HEART,
          SkillPool.PARTY_FAVOR,
          SkillPool.LOVE_SONG,
          SkillPool.BRICKOS,
          SkillPool.DICE,
          SkillPool.RESOLUTIONS,
          SkillPool.TAFFY -> true;
      default -> false;
    };
  }

  public static final boolean isDartSkill(final int skillId) {
    return switch (skillId) {
      case SkillPool.DART_PART1,
          SkillPool.DART_PART2,
          SkillPool.DART_PART3,
          SkillPool.DART_PART4,
          SkillPool.DART_PART5,
          SkillPool.DART_PART6,
          SkillPool.DART_PART7,
          SkillPool.DART_PART8,
          SkillPool.DART_BULLSEYE -> true;
      default -> false;
    };
  }

  /**
   * Determines the cost for next casting of a libram skill
   *
   * @return the MP cost to cast it
   */
  public static final long libramSkillMPConsumption() {
    int cast = Preferences.getInteger("libramSummons");
    return libramSkillMPConsumption(cast + 1);
  }

  public static final void setLibramSkillCasts(long cost) {
    // With sufficient mana cost reduction, the first, second, and
    // third libram summons all cost 1 MP. Therefore, we can't
    // necessarily tell how many times librams have been used today
    // by looking at the summoning cost.

    // Heuristic: if the mana cost shown by the bookcase agrees
    // with our current calculated mana cost, assume we have it
    // right. Otherwise, assume that summons have been made outside
    // of KoLmafia and back-calculate from the bookshelf's cost.

    // Get KoLmafia's idea of number of casts
    int casts = Preferences.getInteger("libramSummons");

    // If the next cast costs what the bookshelf says it costs,
    // assume we're correct.
    if (libramSkillMPConsumption(casts + 1) == cost) {
      return;
    }

    // Otherwise, derive number of casts from unadjusted mana cost
    // Make sure we have updated modifiers - otherwise, the initial
    // cost setting done at login may ignore our MP cost adjustments.
    KoLCharacter.recalculateAdjustments();
    cost -= KoLCharacter.getManaCostAdjustment();

    // cost = 1 + (n * (n-1) / 2)
    //
    // n^2 - n + (2 - 2cost) = 0
    //
    // Use the quadratic formula
    //
    //    a = 1, b = -1, c = 2-2*cost
    //
    // x = ( 1 + sqrt(8*cost - 7))/2

    int count = (1 + (int) Math.sqrt(8 * cost - 7)) / 2;

    Preferences.setInteger("libramSummons", count - 1);
    LockableListFactory.sort(KoLConstants.summoningSkills);
    LockableListFactory.sort(KoLConstants.usableSkills);
  }

  /**
   * Determines the cost for a specific casting of a libram skill
   *
   * @param cast which casting
   * @return the MP cost to cast it
   */
  public static final long libramSkillMPConsumption(final int cast) {
    // Old formula: n * (n+1) / 2
    // return Math.max( (cast * ( cast + 1 ) / 2 + KoLCharacter.getManaCostAdjustment(), 1 );

    // New formula: 1 + (n * (n-1) / 2)
    return Math.max(1 + cast * (cast - 1) / 2 + KoLCharacter.getManaCostAdjustment(), 1);
  }

  /**
   * Determines the cost for casting a libram skill multiple times
   *
   * @param cast which casting
   * @param count how many casts
   * @return the MP cost to cast it
   */
  public static final long libramSkillMPConsumption(int cast, int count) {
    long total = 0;
    while (count-- > 0) {
      total += libramSkillMPConsumption(cast++);
    }
    return total;
  }

  /**
   * Determines how many times you can cast libram skills with the specified amount of MP
   *
   * @param availableMP how much MP is available
   * @return the number of casts
   */
  public static final long libramSkillCasts(long availableMP) {
    int cast = Preferences.getInteger("libramSummons");
    return Math.min(200, libramSkillCasts(cast + 1, availableMP));
  }

  /**
   * Determines how many times you can cast libram skills with the specified amount of MP starting
   * with specified casting
   *
   * @param cast which casting
   * @param availableMP how much MP is available
   * @return the number of casts
   */
  public static final int libramSkillCasts(int cast, long availableMP) {
    long mpCost = SkillDatabase.libramSkillMPConsumption(cast);
    int count = 0;

    while (mpCost <= availableMP) {
      count++;
      availableMP -= mpCost;
      mpCost = SkillDatabase.libramSkillMPConsumption(++cast);
    }

    return count;
  }

  public static final long birdSkillMPConsumption(final int cast) {
    // Casting cost: 5, 10, 20, 40, 80, 160, 320, ...
    long mp = 5 * (long) Math.pow(2.0, cast);
    return Math.max(mp + KoLCharacter.getManaCostAdjustment(), 1);
  }

  public static final int birdSkillCasts(int cast, long availableMP) {
    long mpCost = SkillDatabase.birdSkillMPConsumption(cast);
    int count = 0;

    while (mpCost <= availableMP) {
      count++;
      availableMP -= mpCost;
      mpCost = SkillDatabase.birdSkillMPConsumption(cast);
    }

    return count;
  }

  public static final long birdSkillCasts(long availableMP) {
    int birds = Preferences.getInteger("_birdsSoughtToday");
    return SkillDatabase.birdSkillCasts(birds, availableMP);
  }

  public static final long stackLumpsCost() {
    return stackLumpsCost(Preferences.getInteger("_stackLumpsUses"));
  }

  public static final long stackLumpsCost(int casts) {
    long mpCost = 1;
    if (casts < 0) return mpCost;
    if (casts > 17) return Long.MAX_VALUE;
    for (int i = 0; i <= casts; i++) {
      mpCost = (10 * mpCost) + 1;
    }

    return mpCost;
  }

  /**
   * Returns how many rounds of buff are gained by using the skill with the given Id.
   *
   * @param skillId The id of the skill to lookup
   * @return The duration of effect the cast gives
   */
  public static final int getEffectDuration(final int skillId) {
    Integer duration = SkillDatabase.durationById.get(skillId);
    if (duration == null) {
      return 0;
    }

    int actualDuration = duration.intValue();

    if (SkillDatabase.isSong(skillId)) {
      int multiplier = KoLCharacter.hasSkill(SkillPool.GOOD_SINGING_VOICE) ? 2 : 1;
      return actualDuration * multiplier;
    }

    if (!SkillDatabase.isType(skillId, SkillTag.OTHER)) {
      switch (skillId) {
        case SkillPool.SPIRIT_BOON:
          return KoLCharacter.getBlessingLevel().boonDuration();

        case SkillPool.WAR_BLESSING:
        case SkillPool.SHE_WHO_WAS_BLESSING:
        case SkillPool.STORM_BLESSING:
          if (!KoLCharacter.isTurtleTamer()) {
            return 10;
          }
          break;

        case SkillPool.BIND_VAMPIEROGHI:
        case SkillPool.BIND_VERMINCELLI:
        case SkillPool.BIND_ANGEL_HAIR_WISP:
        case SkillPool.BIND_UNDEAD_ELBOW_MACARONI:
        case SkillPool.BIND_PENNE_DREADFUL:
        case SkillPool.BIND_LASAGMBIE:
        case SkillPool.BIND_SPICE_GHOST:
          if (!KoLCharacter.isPastamancer()) {
            return 10;
          }
          return 0;

        case SkillPool.REV_ENGINE:
          return Math.max(Math.abs(KoLCharacter.getAudience()), 5);

        case SkillPool.BIKER_SWAGGER:
          return Math.max(Math.abs(KoLCharacter.getAudience()), 10);
      }

      return actualDuration;
    }

    if (KoLConstants.inventory.contains(UseSkillRequest.WIZARD_HAT)
        || KoLCharacter.hasEquipped(UseSkillRequest.WIZARD_HAT, Slot.HAT)
        || (KoLCharacter.inLegacyOfLoathing()
            && (KoLConstants.inventory.contains(UseSkillRequest.REPLICA_WIZARD_HAT)
                || KoLCharacter.hasEquipped(UseSkillRequest.REPLICA_WIZARD_HAT, Slot.HAT)))) {
      actualDuration += 5;
    }

    BuffTool[] tools =
        (SkillDatabase.isTurtleTamerBuff(skillId))
            ? UseSkillRequest.TAMER_TOOLS
            : (SkillDatabase.isSaucerorBuff(skillId))
                ? UseSkillRequest.SAUCE_TOOLS
                : (SkillDatabase.isAccordionThiefSong(skillId))
                    ? UseSkillRequest.THIEF_TOOLS
                    : null;

    if (tools == null) {
      return actualDuration;
    }

    int inventoryDuration = 0;

    for (BuffTool tool : tools) {
      int current = actualDuration + tool.getBonusTurns();

      if (current <= inventoryDuration) {
        continue;
      }

      if ((tool.hasEquipped() || KoLConstants.inventory.contains(tool.getItem()))
          && (!tool.isClassLimited()
              || KoLCharacter.getAscensionClass() == tool.getAscensionClass())) {
        inventoryDuration = current;
      }
    }

    return inventoryDuration;
  }

  /**
   * Returns whether or not the skill is a passive.
   *
   * @return <code>true</code> if the skill is passive
   */
  public static final boolean isPassive(final int skillId) {
    // Vampyre skills all have a passive (-hp) effect
    return SkillDatabase.isType(skillId, SkillTag.PASSIVE)
        || SkillDatabase.isVampyreSkill(skillId); // TODO: tag
  }

  /**
   * Returns whether or not the skill is a buff (ie: can be used on others).
   *
   * @return <code>true</code> if the skill can target other players
   */
  public static final boolean isBuff(final int skillId) {
    return SkillDatabase.isType(skillId, SkillTag.OTHER);
  }

  public static final boolean isTurtleTamerBuff(final int skillId) {
    return (skillId > 2000 && skillId < 3000 && SkillDatabase.isBuff(skillId));
  }

  public static final boolean isSaucerorBuff(final int skillId) {
    return (skillId > 4000 && skillId < 5000 && SkillDatabase.isBuff(skillId));
  }

  public static final boolean isAccordionThiefSong(final int skillId) {
    return (skillId > 6000 && skillId < 7000 && SkillDatabase.isBuff(skillId));
  }

  /**
   * Returns whether or not the skill is a combat skill (ie: can be used while fighting).
   *
   * @return <code>true</code> if the skill can be used in combat
   */
  public static final boolean isCombat(final int skillId) {
    return SkillDatabase.isType(skillId, SkillTag.COMBAT);
  }

  /**
   * Returns whether or not the skill is a non combat skill (ie: can be used while not fighting).
   *
   * @return <code>true</code> if the skill can be used out of combat
   */
  public static final boolean isNonCombat(final int skillId) {
    return SkillDatabase.isType(skillId, SkillTag.NONCOMBAT);
  }

  /**
   * Returns whether or not the skill is a song
   *
   * @return <code>true</code> if the skill is a song
   */
  public static final boolean isSong(final int skillId) {
    return SkillDatabase.isType(skillId, SkillTag.SONG);
  }

  /**
   * Returns whether or not the skill is an expression
   *
   * @return <code>true</code> if the skill is an expression
   */
  public static final boolean isExpression(final int skillId) {
    return SkillDatabase.isType(skillId, SkillTag.EXPRESSION);
  }

  /**
   * Returns whether or not the skill is a walk
   *
   * @return <code>true</code> if the skill is a walk
   */
  public static final boolean isWalk(final int skillId) {
    return SkillDatabase.isType(skillId, SkillTag.WALK);
  }

  /**
   * Returns whether or not the skill is a summon
   *
   * @return <code>true</code> if the skill is a summon
   */
  public static final boolean isSummon(final int skillId) {
    return SkillDatabase.isType(skillId, SkillTag.ITEM);
  }

  /**
   * Returns whether or not the skill heals
   *
   * @return <code>true</code> if the skill heals
   */
  public static final boolean isRemedy(final int skillId) {
    return SkillDatabase.isType(skillId, SkillTag.HEAL);
  }

  /**
   * Returns whether or not the skill is a self-buff only
   *
   * @return <code>true</code> if the skill is a self-buff only
   */
  public static final boolean isSelfOnly(final int skillId) {
    return SkillDatabase.isType(skillId, SkillTag.SELF)
        && !SkillDatabase.isType(skillId, SkillTag.OTHER);
  }

  /**
   * Returns whether or not the skill is a spell
   *
   * @return <code>true</code> if the skill is a spell
   */
  public static final boolean isSpell(final int skillId) {
    return SkillDatabase.isType(skillId, SkillTag.SPELL);
  }

  /** Utility method used to determine if the given skill is of the appropriate type. */
  private static boolean isType(final int skillId, final SkillTag type) {
    var tags = SkillDatabase.skillTagsById.get(skillId);
    if (tags == null) {
      return false;
    }
    return tags.stream().anyMatch(t -> t == type);
  }

  public static final boolean isSoulsauceSkill(final int skillId) {
    return SkillDatabase.getSoulsauceCost(skillId) > 0;
  }

  public static final int getSoulsauceCost(final int skillId) {
    return switch (skillId) {
      case SkillPool.SOUL_BUBBLE, SkillPool.SOUL_FOOD -> 5;
      case SkillPool.SOUL_FINGER -> 40;
      case SkillPool.SOUL_BLAZE -> 100;
      case SkillPool.SOUL_ROTATION -> 25;
      case SkillPool.SOUL_FUNK -> 50;
      default -> 0;
    };
  }

  public static final boolean isThunderSkill(final int skillId) {
    return SkillDatabase.getThunderCost(skillId) > 0;
  }

  public static final int getThunderCost(final int skillId) {
    return switch (skillId) {
      case SkillPool.THUNDER_CLAP -> 40;
      case SkillPool.THUNDERCLOUD, SkillPool.THUNDERHEART -> 20;
      case SkillPool.THUNDER_BIRD -> 1;
      case SkillPool.THUNDERSTRIKE -> 5;
      case SkillPool.THUNDER_DOWN_UNDERWEAR -> 60;
      default -> 0;
    };
  }

  public static final boolean isRainSkill(final int skillId) {
    return SkillDatabase.getRainCost(skillId) > 0;
  }

  public static final int getRainCost(final int skillId) {
    return switch (skillId) {
      case SkillPool.RAIN_MAN -> 50;
      case SkillPool.RAINY_DAY -> 20;
      case SkillPool.MAKE_IT_RAIN, SkillPool.RAIN_DANCE -> 10;
      case SkillPool.RAINBOW -> 3;
      case SkillPool.RAINCOAT -> 40;
      default -> 0;
    };
  }

  public static final boolean isLightningSkill(final int skillId) {
    return SkillDatabase.getLightningCost(skillId) > 0;
  }

  public static final int getLightningCost(final int skillId) {
    return switch (skillId) {
      case SkillPool.LIGHTNING_STRIKE, SkillPool.LIGHTNING_ROD -> 20;
      case SkillPool.CLEAN_HAIR_LIGHTNING, SkillPool.SHEET_LIGHTNING -> 10;
      case SkillPool.BALL_LIGHTNING -> 5;
      case SkillPool.LIGHTNING_BOLT_RAIN -> 1;
      default -> 0;
    };
  }

  public static final boolean isAsdonMartinSkill(final int skillId) {
    return SkillDatabase.getFuelCost(skillId) > 0;
  }

  public static final int getFuelCost(final int skillId) {
    return switch (skillId) {
      case SkillPool.AM_MISSILE_LAUNCHER -> 100;
      case SkillPool.AM_BEAN_BAG_CANNON -> 10;
      case SkillPool.AM_FRONT_BUMPER -> 50;
      default -> 0;
    };
  }

  public static final boolean isVampyreSkill(final int skillId) {
    return SkillDatabase.getSkillCategory(skillId) == Category.VAMPYRE;
  }

  public static final int getHPCost(final int skillId) {
    return switch (skillId) {
      case SkillPool.BLOOD_SPIKE, SkillPool.PIERCING_GAZE, SkillPool.SAVAGE_BITE -> 3;
      case SkillPool.BLOOD_CHAINS -> 5;
      case SkillPool.CHILL_OF_THE_TOMB -> 7;
      case SkillPool.BLOOD_CLOAK,
          SkillPool.CEASELESS_SNARL,
          SkillPool.CRUSH,
          SkillPool.FLOCK_OF_BATS_FORM,
          SkillPool.MIST_FORM,
          SkillPool.SPECTRAL_AWARENESS,
          SkillPool.WOLF_FORM,
          SkillPool.BLOOD_BUCATINI -> 10;
      case SkillPool.PERCEIVE_SOUL -> 15;
      case SkillPool.BALEFUL_HOWL, SkillPool.ENSORCEL -> 30;

        // Vampyre Book Skills
      case SkillPool.BLOOD_FRENZY, SkillPool.BLOOD_BOND, SkillPool.BLOOD_BUBBLE -> 30;
      case SkillPool.BLOOD_BLADE, SkillPool.BRAMS_BLOODY_BAGATELLE -> 50;
      default -> 0;
    };
  }

  public static final int getPPCost(final int skillId) {
    return switch (skillId) {
      case SkillPool.HAMMER_THROW_COMBAT,
          SkillPool.JUGGLE_FIREBALLS_COMBAT,
          SkillPool.SPIN_JUMP_COMBAT -> 1;
      case SkillPool.ULTRA_SMASH_COMBAT,
          SkillPool.FIREBALL_BARRAGE_COMBAT,
          SkillPool.MULTI_BOUNCE_COMBAT -> 2;
      default -> 0;
    };
  }

  public static final AdventureResult getManaItemCost(final int skillId) {
    return switch (skillId) {
      case SkillPool.DARK_RITUAL -> ItemPool.get(ItemPool.BLACK_MANA, 1);
      case SkillPool.ANCESTRAL_RECALL -> ItemPool.get(ItemPool.BLUE_MANA, 1);
      case SkillPool.GIANT_GROWTH -> ItemPool.get(ItemPool.GREEN_MANA, 1);
      case SkillPool.LIGHTNING_BOLT_CARD -> ItemPool.get(ItemPool.RED_MANA, 1);
      case SkillPool.HEALING_SALVE -> ItemPool.get(ItemPool.WHITE_MANA, 1);
      default -> null;
    };
  }

  public static final int getBlackManaCost(final int skillId) {
    return skillId == SkillPool.DARK_RITUAL ? 1 : 0;
  }

  public static final int getBlueManaCost(final int skillId) {
    return skillId == SkillPool.ANCESTRAL_RECALL ? 1 : 0;
  }

  public static final int getGreenManaCost(final int skillId) {
    return skillId == SkillPool.GIANT_GROWTH ? 1 : 0;
  }

  public static final int getRedManaCost(final int skillId) {
    return skillId == SkillPool.LIGHTNING_BOLT_CARD ? 1 : 0;
  }

  public static final int getWhiteManaCost(final int skillId) {
    return skillId == SkillPool.HEALING_SALVE ? 1 : 0;
  }

  public static final int getAdventureCost(final int skillId) {
    return switch (skillId) {
      case SkillPool.HIBERNATE,
          SkillPool.SPIRIT_VACATION,
          SkillPool.TRANSCENDENTAL_DENTE,
          SkillPool.SIMMER,
          SkillPool.RECRUIT_ZOMBIE,
          SkillPool.CHECK_MIRROR,
          SkillPool.RAIN_MAN,
          SkillPool.EVOKE_ELDRITCH_HORROR -> 1;
      default -> 0;
    };
  }

  public static int getMaxLevel(final int skillId) {
    return SkillDatabase.maxLevelById.getOrDefault(skillId, 0);
  }

  /** Utility method used to determine if the given skill can be made permanent */
  public static boolean isPermable(final int skillId) {
    return SkillDatabase.permableById.getOrDefault(skillId, skillId < 7000);
  }

  public static final boolean isBookshelfSkill(final int skillId) {
    return skillId >= SkillPool.SNOWCONE && skillId <= SkillPool.CONFISCATOR;
  }

  public static final boolean isBookshelfSkill(final String skillName) {
    return isBookshelfSkill(SkillDatabase.getSkillId(skillName));
  }

  public static final int skillToBook(final String skillName) {
    return switch (SkillDatabase.getSkillId(skillName)) {
      case SkillPool.SNOWCONE -> ItemPool.SNOWCONE_BOOK;
      case SkillPool.STICKER -> ItemPool.STICKER_BOOK;
      case SkillPool.SUGAR -> ItemPool.SUGAR_BOOK;
      case SkillPool.CLIP_ART -> ItemPool.CLIP_ART_BOOK;
      case SkillPool.RAD_LIB -> ItemPool.RAD_LIB_BOOK;
      case SkillPool.SMITHSNESS -> ItemPool.SMITH_BOOK;
      case SkillPool.CANDY_HEART -> ItemPool.CANDY_BOOK;
      case SkillPool.PARTY_FAVOR -> ItemPool.DIVINE_BOOK;
      case SkillPool.LOVE_SONG -> ItemPool.LOVE_BOOK;
      case SkillPool.BRICKOS -> ItemPool.BRICKO_BOOK;
      case SkillPool.DICE -> ItemPool.DICE_BOOK;
      case SkillPool.RESOLUTIONS -> ItemPool.RESOLUTION_BOOK;
      case SkillPool.TAFFY -> ItemPool.TAFFY_BOOK;
      case SkillPool.HILARIOUS -> ItemPool.HILARIOUS_BOOK;
      case SkillPool.TASTEFUL -> ItemPool.TASTEFUL_BOOK;
      case SkillPool.CARDS -> ItemPool.CARD_GAME_BOOK;
      case SkillPool.GEEKY -> ItemPool.GEEKY_BOOK;
      case SkillPool.CONFISCATOR -> ItemPool.CONFISCATOR_BOOK;
      default -> -1;
    };
  }

  /** Returns all skills in the database of the given type. */
  public static final List<UseSkillRequest> getSkillsByType(final SkillTag type) {
    return SkillDatabase.getSkillsByType(type, false);
  }

  public static final List<UseSkillRequest> getSkillsByType(
      final SkillTag type, final boolean onlyKnown) {
    return getSkillsByType(EnumSet.of(type), onlyKnown);
  }

  public static final List<UseSkillRequest> getAllSkills() {
    return getSkillsByType(EnumSet.allOf(SkillTag.class), false);
  }

  public static final List<UseSkillRequest> getCastableSkills() {
    return getCastableSkills(false);
  }

  public static final List<UseSkillRequest> getCastableSkills(final boolean onlyKnown) {
    return getSkillsByType(EnumSet.of(SkillTag.NONCOMBAT), onlyKnown);
  }

  private static final List<UseSkillRequest> getSkillsByType(
      final EnumSet<SkillTag> types, final boolean onlyKnown) {
    Integer[] keys = new Integer[SkillDatabase.skillTagsById.size()];
    SkillDatabase.skillTagsById.keySet().toArray(keys);

    ArrayList<UseSkillRequest> list = new ArrayList<>();

    for (Integer skillId : keys) {
      var skillTags = SkillDatabase.skillTagsById.get(skillId);
      if (skillTags == null) continue;

      boolean shouldAdd = skillTags.stream().anyMatch(types::contains);

      if (!shouldAdd || onlyKnown && !KoLCharacter.hasSkill(skillId)) {
        continue;
      }

      list.add(UseSkillRequest.getUnmodifiedInstance(skillId));
    }

    return list;
  }

  /**
   * Returns whether or not an item with a given name exists in the database; this is useful in the
   * event that an item is encountered which is not tradeable (and hence, should not be displayed).
   *
   * @return <code>true</code> if the item is in the database
   */
  public static final boolean contains(final String skillName) {
    if (skillName == null) {
      return false;
    }

    return Arrays.binarySearch(
            SkillDatabase.canonicalNames, StringUtilities.getCanonicalName(skillName))
        >= 0;
  }

  /**
   * Returns the set of skills keyed by name
   *
   * @return The set of skills keyed by name
   */
  public static final Set<Entry<Integer, String>> entrySet() {
    return SkillDatabase.nameById.entrySet();
  }

  public static final void generateSkillList(final StringBuffer buffer, final boolean appendHTML) {
    Map<Category, List<String>> categories = new EnumMap<>(Category.class);

    if (SkillDatabase.skillNames.isEmpty()) {
      SkillDatabase.skillNames.addAll(SkillDatabase.skillIdSetByName.keySet());
    }

    for (var category : Category.VALUES) {
      var list = new ArrayList<>(SkillDatabase.skillsByCategory.get(category));
      categories.put(category, list);

      for (int j = 0; j < categories.get(category).size(); ++j) {
        if (!KoLConstants.availableSkills.contains(
            UseSkillRequest.getUnmodifiedInstance(categories.get(category).get(j)))) {
          categories.get(category).remove(j--);
        }
      }
    }

    boolean printedList = false;

    for (var entry : categories.entrySet()) {
      if (entry.getValue().isEmpty()) {
        continue;
      }

      if (printedList) {
        if (appendHTML) {
          buffer.append("<br>");
        } else {
          buffer.append(KoLConstants.LINE_BREAK);
        }
      }

      SkillDatabase.appendSkillList(
          buffer, appendHTML, StringUtilities.toTitleCase(entry.getKey().name), entry.getValue());
      printedList = true;
    }
  }

  private static void appendSkillList(
      final StringBuffer buffer,
      final boolean appendHTML,
      final String listName,
      final List<String> list) {
    if (list.isEmpty()) {
      return;
    }

    Collections.sort(list);

    if (appendHTML) {
      buffer.append("<u><b>");
    }

    buffer.append(StringUtilities.toTitleCase(listName));

    if (appendHTML) {
      buffer.append("</b></u><br>");
    } else {
      buffer.append(KoLConstants.LINE_BREAK);
    }

    String currentSkill;

    for (String s : list) {
      currentSkill = s;

      if (appendHTML) {
        buffer.append("<a onClick=\"javascript:skill(");
        buffer.append(SkillDatabase.getSkillId(currentSkill));
        buffer.append(");\">");
      } else {
        buffer.append(" - ");
      }

      buffer.append(currentSkill);

      if (appendHTML) {
        buffer.append("</a><br>");
      } else {
        buffer.append(KoLConstants.LINE_BREAK);
      }
    }
  }

  /**
   * Utility method used to retrieve the full name of a skill, given a substring representing it.
   */
  public static final String getSkillName(
      final String substring, final List<UseSkillRequest> list) {
    UseSkillRequest match = getSkill(substring, list);
    return match == null ? null : match.getSkillName();
  }

  /** Utility method used to retrieve a UseSkillRequest, given a substring of its name */
  public static final UseSkillRequest getSkill(
      final String substring, final List<UseSkillRequest> skills) {
    String canonical = StringUtilities.getCanonicalName(substring);

    // Search for exact match
    for (UseSkillRequest skill : skills) {
      if (skill.getCanonical().equals(canonical)) {
        return skill;
      }
    }

    // Search for case insensitive substring match
    UseSkillRequest match = null;
    boolean ambiguous = false;
    for (UseSkillRequest skill : skills) {
      if (skill.getCanonical().contains(canonical)) {
        String skillName = skill.getSkillName();
        if (ambiguous) {
          RequestLogger.printLine(skillName);
        } else if (match != null) {
          RequestLogger.printLine("Possible matches:");
          RequestLogger.printLine(match.getSkillName());
          RequestLogger.printLine(skillName);
          ambiguous = true;
        } else {
          match = skill;
        }
      }
    }

    return (ambiguous || match == null) ? null : match;
  }

  /**
   * Utility method used to retrieve the full name of a skill, given a substring representing it.
   */
  public static final String getSkillName(final String substring) {
    return getSkillName(substring, getAllSkills());
  }

  /**
   * Utility method used to retrieve the full name of a castable skill, given a substring
   * representing it.
   */
  public static final String getUsableSkillName(final String substring) {
    return getSkillName(substring, getCastableSkills());
  }

  /**
   * Utility method used to retrieve the full name of a known castable skill, given a substring
   * representing it.
   */
  public static final String getUsableKnownSkillName(final String substring) {
    return getSkillName(substring, getCastableSkills(true));
  }

  /**
   * Utility method used to retrieve the full name of a combat skill, given a substring representing
   * it.
   */
  public static final UseSkillRequest getCombatSkill(final String substring) {
    return getSkill(substring, getSkillsByType(SkillTag.COMBAT));
  }

  /** Utility method used to retrieve the maximum daily casts of a skill. Returns -1 if no limit. */
  public static long getMaxCasts(int skillId) {
    UseSkillRequest skill = UseSkillRequest.getUnmodifiedInstance(skillId);
    if (skill == null) {
      return -1;
    }
    long max = skill.getMaximumCast();
    return (max == Long.MAX_VALUE ? -1 : max);
  }

  /** Method that is called when we need to update the number of casts for a given skill. */
  public static void registerCasts(int skillId, int count) {
    Integer oldCasts = SkillDatabase.castsById.get(skillId);
    if (oldCasts == null) {
      oldCasts = 0;
    }
    int newCasts = oldCasts.intValue() + count;
    SkillDatabase.castsById.put(skillId, newCasts);
  }

  public static String skillString(
      final int skillId,
      final String skillName,
      final String image,
      final EnumSet<SkillTag> tags,
      final long mp,
      final int duration,
      final Map<String, String> attrs) {
    StringBuilder buffer = new StringBuilder();

    buffer.append(skillId);
    buffer.append("\t");
    buffer.append(skillName);
    buffer.append("\t");
    buffer.append(image);
    buffer.append("\t");
    buffer.append(tags.stream().map(t -> t.name).collect(Collectors.joining(",")));
    buffer.append("\t");
    buffer.append(mp);
    buffer.append("\t");
    buffer.append(duration);
    buffer.append(
        attrs.entrySet().stream()
            .map(e -> e.getKey() + ": " + e.getValue())
            .collect(Collectors.joining(", ")));
    return buffer.toString();
  }

  public static final void registerSkill(final int skillId) {
    // Load the description text for this skill
    String text = DebugDatabase.readSkillDescriptionText(skillId);
    if (text == null) {
      return;
    }
    SkillDatabase.registerSkill(text, skillId, null);
  }

  public static final void registerSkill(final int skillId, String skillName) {
    // Load the description text for this skill
    String text = DebugDatabase.readSkillDescriptionText(skillId);
    if (text == null) {
      return;
    }
    SkillDatabase.registerSkill(text, skillId, skillName);
  }

  public static final void registerSkill(String text, final int skillId, String skillName) {
    if (skillName == null) {
      skillName = DebugDatabase.parseName(text);
    }

    String image = DebugDatabase.parseImage(text);

    String typeString = DebugDatabase.parseSkillType(text);
    EnumSet<SkillTag> tags =
        switch (typeString) {
          case "Passive" -> EnumSet.of(SkillTag.PASSIVE);
          case "Noncombat" -> EnumSet.of(SkillTag.NONCOMBAT, SkillTag.SELF);
          case "Buff" -> EnumSet.of(SkillTag.NONCOMBAT, SkillTag.EFFECT, SkillTag.OTHER);
          case "Combat" -> EnumSet.of(SkillTag.COMBAT);
          case "Combat Spell" -> EnumSet.of(SkillTag.COMBAT, SkillTag.SPELL);
          case "Combat / Noncombat" -> EnumSet.of(SkillTag.COMBAT, SkillTag.NONCOMBAT);
          default -> EnumSet.noneOf(SkillTag.class);
        };
    long mp = DebugDatabase.parseSkillMPCost(text);
    int duration = DebugDatabase.parseSkillEffectDuration(text);

    SkillDatabase.addSkill(skillId, skillName, image, tags, mp, duration, Map.of());

    String printMe;

    // Print what goes in classkills.txt
    printMe = "--------------------";
    RequestLogger.printLine(printMe);
    RequestLogger.updateSessionLog(printMe);

    printMe = SkillDatabase.skillString(skillId, skillName, image, tags, mp, duration, Map.of());
    RequestLogger.printLine(printMe);
    RequestLogger.updateSessionLog(printMe);

    // Passive skills have modifiers
    if (tags.contains(SkillTag.PASSIVE)) {
      // Let modifiers database do what it wishes with this skill
      ModifierDatabase.registerSkill(skillName, text);
    }

    printMe = "--------------------";
    RequestLogger.printLine(printMe);
    RequestLogger.updateSessionLog(printMe);

    String effectName = DebugDatabase.parseSkillEffectName(text);
    if (!effectName.isEmpty() && EffectDatabase.getEffectId(effectName, true) == -1) {
      String effectDescid = DebugDatabase.parseSkillEffectId(text);
      EffectDatabase.registerEffect(effectName, effectDescid, "cast 1 " + skillName);
    }

    // Update Canonical names list
    SkillDatabase.canonicalNames = new String[SkillDatabase.skillIdSetByName.size()];
    SkillDatabase.skillIdSetByName.keySet().toArray(SkillDatabase.canonicalNames);
  }

  /**
   * Utility method used to get the number of times a skill has been cast in the current session.
   */
  public static int getCasts(int skillId) {
    Integer casts = SkillDatabase.castsById.get(skillId);

    if (casts == null) {
      return 0;
    }
    return casts.intValue();
  }

  public static boolean sourceAgentSkill(int skillId) {
    // Return true if this skill is usable against a source agent
    // All class 21 skills can be used
    if ((skillId / 1000) == 21) {
      return true;
    }

    // Some Source Terminal skills are usable. Turbo for sure.
    // List all until we learn which ones are not usable
    return switch (skillId) {
      case SkillPool.EXTRACT,
          SkillPool.DIGITIZE,
          SkillPool.COMPRESS,
          SkillPool.DUPLICATE,
          SkillPool.PORTSCAN,
          SkillPool.TURBO -> true;
      default -> false;
    };
  }

  public static boolean summonsMonster(int skillId) {
    return switch (skillId) {
      case SkillPool.RAIN_MAN, SkillPool.EVOKE_ELDRITCH_HORROR -> true;
      default -> false;
    };
  }
}
