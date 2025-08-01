package net.sourceforge.kolmafia.request;

import static internal.helpers.HttpClientWrapper.getRequests;
import static internal.helpers.Networking.assertGetRequest;
import static internal.helpers.Networking.assertPostRequest;
import static internal.helpers.Networking.html;
import static internal.helpers.Player.withAdventuresLeft;
import static internal.helpers.Player.withClass;
import static internal.helpers.Player.withDay;
import static internal.helpers.Player.withEquippableItem;
import static internal.helpers.Player.withEquipped;
import static internal.helpers.Player.withInteractivity;
import static internal.helpers.Player.withItem;
import static internal.helpers.Player.withLevel;
import static internal.helpers.Player.withMP;
import static internal.helpers.Player.withNextResponse;
import static internal.helpers.Player.withPath;
import static internal.helpers.Player.withProperty;
import static internal.helpers.Player.withSkill;
import static internal.matchers.Preference.isSetTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

import internal.helpers.Cleanups;
import internal.helpers.HttpClientWrapper;
import java.time.Month;
import net.sourceforge.kolmafia.AscensionClass;
import net.sourceforge.kolmafia.AscensionPath.Path;
import net.sourceforge.kolmafia.KoLCharacter;
import net.sourceforge.kolmafia.KoLmafia;
import net.sourceforge.kolmafia.equipment.Slot;
import net.sourceforge.kolmafia.objectpool.EffectPool;
import net.sourceforge.kolmafia.objectpool.ItemPool;
import net.sourceforge.kolmafia.objectpool.SkillPool;
import net.sourceforge.kolmafia.persistence.SkillDatabase;
import net.sourceforge.kolmafia.preferences.Preferences;
import net.sourceforge.kolmafia.session.ContactManager;
import net.sourceforge.kolmafia.session.InventoryManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class UseSkillRequestTest {
  @BeforeAll
  static void beforeAll() {
    KoLCharacter.reset("UseSkillRequestTest");
  }

  @BeforeEach
  void beforeEach() {
    Preferences.reset("UseSkillRequestTest");
  }

  private static final int EXPERIENCE_SAFARI = SkillDatabase.getSkillId("Experience Safari");

  @Test
  void errorDoesNotIncrementSkillUses() {
    ContactManager.registerPlayerId("targetPlayer", "123");
    KoLCharacter.setMP(1000, 1000, 1000);
    KoLCharacter.addAvailableSkill(EXPERIENCE_SAFARI);
    int startingCasts = SkillDatabase.getCasts(EXPERIENCE_SAFARI);

    UseSkillRequest req = UseSkillRequest.getInstance(EXPERIENCE_SAFARI, "targetPlayer", 1);

    var cleanups = withNextResponse(200, "You don't have enough mana to cast that skill.");

    try (cleanups) {
      req.run();
    }

    assertEquals("Not enough mana to cast Experience Safari.", UseSkillRequest.lastUpdate);
    assertEquals(startingCasts, SkillDatabase.getCasts(EXPERIENCE_SAFARI));

    KoLmafia.forceContinue();
  }

  @Test
  void successIncrementsSkillUses() {
    ContactManager.registerPlayerId("targetPlayer", "123");
    KoLCharacter.setMP(1000, 1000, 1000);
    KoLCharacter.addAvailableSkill(EXPERIENCE_SAFARI);
    int startingCasts = SkillDatabase.getCasts(EXPERIENCE_SAFARI);

    UseSkillRequest req = UseSkillRequest.getInstance(EXPERIENCE_SAFARI, "targetPlayer", 1);

    var cleanups =
        withNextResponse(
            200,
            "You bless your friend, targetPlayer, with the ability to experience a safari adventure.");

    try (cleanups) {
      req.run();
    }

    assertEquals("", UseSkillRequest.lastUpdate);
    assertEquals(startingCasts + 1, SkillDatabase.getCasts(EXPERIENCE_SAFARI));
  }

  @Test
  void correctErrorMessageForTomeWhenInRun() {
    var cleanups =
        new Cleanups(
            withMP(1000, 1000, 1000),
            withSkill(SkillPool.STICKER),
            withProperty("tomeSummons", 0),
            withProperty("_stickerSummons", 0),
            withInteractivity(false),
            withNextResponse(200, "You may only use three Tome summonings each day"));

    try (cleanups) {
      UseSkillRequest req = UseSkillRequest.getInstance(SkillPool.STICKER);
      req.run();

      assertThat(
          UseSkillRequest.lastUpdate, equalTo("You may only use three Tome summonings each day"));
      assertThat("tomeSummons", isSetTo(3));
      assertThat("_stickerSummons", isSetTo(0));
    }
  }

  @Test
  void correctErrorMessageForTomeWhenOutOfRun() {
    var cleanups =
        new Cleanups(
            withMP(1000, 1000, 1000),
            withSkill(SkillPool.STICKER),
            withProperty("tomeSummons", 0),
            withProperty("_stickerSummons", 0),
            withInteractivity(true),
            withNextResponse(200, "You may only use three Tome summonings each day"));

    try (cleanups) {
      UseSkillRequest req = UseSkillRequest.getInstance(SkillPool.STICKER);
      req.run();

      assertThat(
          UseSkillRequest.lastUpdate, equalTo("You can only cast Summon Stickers 3 times per day"));
      assertThat("tomeSummons", isSetTo(3));
      assertThat("_stickerSummons", isSetTo(3));
    }
  }

  @ParameterizedTest
  @CsvSource({
    "Accordion Thief, 15, true",
    "Accordion Thief, 13, false",
    "Sauceror, 15, false",
    "Turtle Tamer, 13, false",
  })
  void canOnlyCastBenettonsInRightState(String className, int level, boolean canCast) {
    var ascensionClass = AscensionClass.find(className);

    var cleanups = new Cleanups(withClass(ascensionClass), withLevel(level));

    try (cleanups) {
      var skill = UseSkillRequest.getInstance(SkillPool.BENETTONS);
      assertThat(skill.getMaximumCast() > 0, equalTo(canCast));
    }
  }

  @Test
  void incrementsUsageForLimitedSkills() {
    var cleanups =
        new Cleanups(
            withMP(1000, 1000, 1000),
            withSkill(SkillPool.DONHOS),
            withProperty("_donhosCasts", 1),
            withClass(AscensionClass.ACCORDION_THIEF),
            withLevel(15),
            withNextResponse(200, html("request/test_cast_donhos_bubbly_ballad.html")));
    try (cleanups) {
      UseSkillRequest req = UseSkillRequest.getInstance(SkillPool.DONHOS, "me", 5);
      req.run();
      assertThat("_donhosCasts", isSetTo(6));
    }
  }

  @Nested
  class DesignerSweatpants {
    @BeforeEach
    public void initializeState() {
      HttpClientWrapper.setupFakeClient();
      KoLCharacter.reset("DesignerSweatpants");
      Preferences.reset("DesignerSweatpants");
    }

    @AfterAll
    public static void afterAll() {
      UseSkillRequest.lastSkillUsed = -1;
      UseSkillRequest.lastSkillCount = 0;
    }

    @Test
    void tooManyCasts() {
      UseSkillRequest.lastSkillUsed = SkillPool.SWEAT_OUT_BOOZE;
      var req = UseSkillRequest.getInstance(SkillPool.SWEAT_OUT_BOOZE);
      req.responseText = html("request/test_runskillz_cant_use_again.html");
      req.processResults();
      assertThat(UseSkillRequest.lastUpdate, containsString("Summon limit exceeded"));
    }

    @Test
    void wearDesignerSweatpantsForCastingSweatSkills() {
      var cleanups = new Cleanups(withEquippableItem(ItemPool.DESIGNER_SWEATPANTS));
      InventoryManager.checkSkillGrantingEquipment(ItemPool.DESIGNER_SWEATPANTS);

      try (cleanups) {
        var req = UseSkillRequest.getInstance(SkillPool.DRENCH_YOURSELF_IN_SWEAT, 1);
        req.run();

        var requests = getRequests();
        assertThat(requests, hasSize(3));
        assertPostRequest(
            requests.get(0), "/inv_equip.php", "which=2&ajax=1&action=equip&whichitem=10929");
        assertGetRequest(
            requests.get(1), "/runskillz.php", "action=Skillz&whichskill=7419&ajax=1&quantity=1");
        assertPostRequest(requests.get(2), "/api.php", "what=status&for=KoLmafia");
      }
    }

    @Test
    void dontWearDesignerSweatpantsForSweatingOutBooze() {
      var cleanups = new Cleanups(withEquippableItem(ItemPool.DESIGNER_SWEATPANTS));
      InventoryManager.checkSkillGrantingEquipment(ItemPool.DESIGNER_SWEATPANTS);

      try (cleanups) {
        var req = UseSkillRequest.getInstance(SkillPool.SWEAT_OUT_BOOZE, 1);
        req.run();

        var requests = getRequests();
        assertThat(requests, hasSize(2));
        assertGetRequest(
            requests.get(0), "/runskillz.php", "action=Skillz&whichskill=7414&ajax=1&quantity=1");
        assertPostRequest(requests.get(1), "/api.php", "what=status&for=KoLmafia");
      }
    }

    @Test
    void doNotEquipDesignerSweatpantsForSkillIfAlreadyWearing() {
      var cleanups = new Cleanups(withEquipped(Slot.PANTS, "designer sweatpants"));
      InventoryManager.checkSkillGrantingEquipment(ItemPool.DESIGNER_SWEATPANTS);

      try (cleanups) {
        var req = UseSkillRequest.getInstance(SkillPool.DRENCH_YOURSELF_IN_SWEAT, 1);
        req.run();

        var requests = getRequests();
        assertThat(requests, hasSize(2));
        assertGetRequest(
            requests.get(0), "/runskillz.php", "action=Skillz&whichskill=7419&ajax=1&quantity=1");
      }
    }

    @Test
    void decreaseSweatWhenCastingSweatBooze() {
      Preferences.setInteger("sweat", 31);
      UseSkillRequest.lastSkillUsed = SkillPool.SWEAT_OUT_BOOZE;
      UseSkillRequest.lastSkillCount = 1;
      UseSkillRequest.parseResponse(
          "runskillz.php?action=Skillz&whichskill=7414&ajax=1&quantity=1",
          html("request/test_cast_sweat_booze.html"));
      // 31 - 25 = 6
      assertEquals(Preferences.getInteger("sweat"), 6);
    }

    @Test
    void decreaseSweatWhenCastingOtherSweatSkills() {
      Preferences.setInteger("sweat", 69);
      UseSkillRequest.lastSkillUsed = SkillPool.DRENCH_YOURSELF_IN_SWEAT;
      UseSkillRequest.lastSkillCount = 1;
      UseSkillRequest.parseResponse(
          "runskillz.php?action=Skillz&whichskill=7419&ajax=1&quantity=1",
          html("request/test_cast_drench_sweat.html"));
      // 69 - 15 = 54
      assertEquals(Preferences.getInteger("sweat"), 54);
    }
  }

  @Nested
  class Numberology {
    @Test
    void calculatingUniverseRequiresAvailableTurns() {
      var cleanups =
          new Cleanups(
              withProperty("skillLevel144", 1),
              withProperty("_universeCalculated", 0),
              withInteractivity(true),
              withAdventuresLeft(0));
      try (cleanups) {
        var skill = UseSkillRequest.getInstance(SkillPool.CALCULATE_THE_UNIVERSE);
        assertEquals(0, skill.getMaximumCast());
      }
    }

    @ParameterizedTest
    @CsvSource({"5, 0", "5, 1", "5, 2", "5, 3", "5, 4", "5, 5"})
    void calculatingUniverseHasDailyLimit(int skillLevel, int casts) {
      var cleanups =
          new Cleanups(
              withProperty("skillLevel144", skillLevel),
              withProperty("_universeCalculated", casts),
              withInteractivity(true),
              withAdventuresLeft(1));
      try (cleanups) {
        var skill = UseSkillRequest.getInstance(SkillPool.CALCULATE_THE_UNIVERSE);
        assertEquals(skillLevel - casts, skill.getMaximumCast());
      }
    }

    @Test
    void calculatingUniverseLimitedInHardcoreOrRonin() {
      var cleanups =
          new Cleanups(
              withProperty("skillLevel144", 5),
              withProperty("_universeCalculated", 0),
              withInteractivity(false),
              withAdventuresLeft(1));
      try (cleanups) {
        var skill = UseSkillRequest.getInstance(SkillPool.CALCULATE_THE_UNIVERSE);
        assertEquals(3, skill.getMaximumCast());
      }
    }
  }

  @Nested
  class CinchoDeMayo {
    @BeforeEach
    public void initializeState() {
      HttpClientWrapper.setupFakeClient();
      KoLCharacter.reset("CinchoDeMayo");
      Preferences.reset("CinchoDeMayo");
    }

    @AfterAll
    public static void afterAll() {
      UseSkillRequest.lastSkillUsed = -1;
      UseSkillRequest.lastSkillCount = 0;
    }

    @ParameterizedTest
    @ValueSource(
        ints = {SkillPool.CINCHO_PARTY_SOUNDTRACK, SkillPool.CINCHO_DISPENSE_SALT_AND_LIME})
    void wearCinchoForCastingCinchSkills(final int skill) {
      var cleanups =
          new Cleanups(withEquippableItem(ItemPool.CINCHO_DE_MAYO), withProperty("_cinchUsed", 0));
      InventoryManager.checkSkillGrantingEquipment(ItemPool.CINCHO_DE_MAYO);

      try (cleanups) {
        var req = UseSkillRequest.getInstance(skill, 1);
        req.run();

        var requests = getRequests();
        assertThat(requests, hasSize(3));
        assertPostRequest(
            requests.get(0),
            "/inv_equip.php",
            "which=2&ajax=1&slot=3&action=equip&whichitem=11223");
        assertGetRequest(
            requests.get(1),
            "/runskillz.php",
            "action=Skillz&whichskill=" + skill + "&ajax=1&quantity=1");
        assertPostRequest(requests.get(2), "/api.php", "what=status&for=KoLmafia");
      }
    }

    @Test
    void wearReplicaCinchoForCastingCinchSkillsInLol() {
      var cleanups =
          new Cleanups(
              withPath(Path.LEGACY_OF_LOATHING),
              withEquippableItem(ItemPool.REPLICA_CINCHO_DE_MAYO),
              withProperty("_cinchUsed", 0));
      InventoryManager.checkSkillGrantingEquipment(ItemPool.REPLICA_CINCHO_DE_MAYO);

      try (cleanups) {
        var req = UseSkillRequest.getInstance(SkillPool.CINCHO_PARTY_SOUNDTRACK, 1);
        req.run();

        var requests = getRequests();
        assertThat(requests, hasSize(3));
        assertPostRequest(
            requests.get(0),
            "/inv_equip.php",
            "which=2&ajax=1&slot=3&action=equip&whichitem=11254");
        assertGetRequest(
            requests.get(1), "/runskillz.php", "action=Skillz&whichskill=7440&ajax=1&quantity=1");
        assertPostRequest(requests.get(2), "/api.php", "what=status&for=KoLmafia");
      }
    }

    @ParameterizedTest
    @ValueSource(ints = {ItemPool.CINCHO_DE_MAYO, ItemPool.REPLICA_CINCHO_DE_MAYO})
    void doNotEquipCinchoDeMayoForSkillIfAlreadyWearing(final int itemId) {
      var cleanups =
          new Cleanups(withPath(Path.LEGACY_OF_LOATHING), withEquipped(Slot.ACCESSORY2, itemId));
      InventoryManager.checkSkillGrantingEquipment(itemId);

      try (cleanups) {
        var req = UseSkillRequest.getInstance(SkillPool.CINCHO_DISPENSE_SALT_AND_LIME, 1);
        req.run();

        var requests = getRequests();
        assertThat(requests, hasSize(2));
        assertGetRequest(
            requests.getFirst(),
            "/runskillz.php",
            "action=Skillz&whichskill=7439&ajax=1&quantity=1");
      }
    }

    @Test
    void increaseCinchWhenCastingSkill() {
      var cleanups = new Cleanups(withProperty("_cinchUsed", 10));

      try (cleanups) {
        UseSkillRequest.lastSkillUsed = SkillPool.CINCHO_FIESTA_EXIT;
        UseSkillRequest.lastSkillCount = 1;
        UseSkillRequest.parseResponse(
            "runskillz.php?action=Skillz&whichskill=7441&ajax=1&quantity=1",
            html("request/test_cast_cincho_fiesta_exit.html"));
        // 10 + 60 = 70
        assertThat("_cinchUsed", isSetTo(70));
      }
    }

    @Test
    void dispensingSaltAndLimeIsTracked() {
      var cleanups = new Cleanups(withProperty("cinchoSaltAndLime", 2));

      try (cleanups) {
        UseSkillRequest.lastSkillUsed = SkillPool.CINCHO_DISPENSE_SALT_AND_LIME;
        UseSkillRequest.lastSkillCount = 1;
        UseSkillRequest.parseResponse(
            "runskillz.php?action=Skillz&whichskill=74439&ajax=1&quantity=1",
            html("request/test_cast_cincho_dispense_salt_and_lime.html"));
        assertThat("cinchoSaltAndLime", isSetTo(3));
      }
    }
  }

  @Nested
  class August {
    @BeforeEach
    public void initializeState() {
      KoLCharacter.reset("AugustScepter");
      Preferences.reset("AugustScepter");
    }

    @Test
    public void canCastSkillNormally() {
      var cleanups =
          new Cleanups(
              withItem(ItemPool.AUGUST_SCEPTER),
              withProperty("_aug12Cast"),
              withProperty("_augSkillsCast"));
      InventoryManager.checkSkillGrantingEquipment(ItemPool.AUGUST_SCEPTER);

      try (cleanups) {
        var skill = UseSkillRequest.getInstance(SkillPool.AUG_12TH_ELEPHANT_DAY);
        assertEquals(1, skill.getMaximumCast());
      }
    }

    @Test
    public void cannotCastSkillIfAlreadyCast() {
      var cleanups =
          new Cleanups(
              withItem(ItemPool.AUGUST_SCEPTER),
              withProperty("_aug12Cast", true),
              withProperty("_augSkillsCast"));
      InventoryManager.checkSkillGrantingEquipment(ItemPool.AUGUST_SCEPTER);

      try (cleanups) {
        var skill = UseSkillRequest.getInstance(SkillPool.AUG_12TH_ELEPHANT_DAY);
        assertEquals(0, skill.getMaximumCast());
      }
    }

    @Test
    public void cannotCastSkillIfCastFiveOthers() {
      var cleanups =
          new Cleanups(
              withItem(ItemPool.AUGUST_SCEPTER),
              withProperty("_aug12Cast"),
              withProperty("_augSkillsCast", 5));
      InventoryManager.checkSkillGrantingEquipment(ItemPool.AUGUST_SCEPTER);

      try (cleanups) {
        var skill = UseSkillRequest.getInstance(SkillPool.AUG_12TH_ELEPHANT_DAY);
        assertEquals(0, skill.getMaximumCast());
      }
    }

    @Test
    public void cannotCastTodaySkillIfCastFiveOthersInRun() {
      var cleanups =
          new Cleanups(
              withItem(ItemPool.AUGUST_SCEPTER),
              withProperty("_aug12Cast"),
              withProperty("_augSkillsCast", 5),
              withInteractivity(false),
              withDay(2023, Month.AUGUST, 12));
      InventoryManager.checkSkillGrantingEquipment(ItemPool.AUGUST_SCEPTER);

      try (cleanups) {
        var skill = UseSkillRequest.getInstance(SkillPool.AUG_12TH_ELEPHANT_DAY);
        assertEquals(0, skill.getMaximumCast());
      }
    }

    @Test
    public void canCastTodaySkillIfCastFiveOthersInAftercore() {
      var cleanups =
          new Cleanups(
              withItem(ItemPool.AUGUST_SCEPTER),
              withProperty("_aug31Cast"),
              withProperty("_aug30Cast"),
              withProperty("_augSkillsCast", 5),
              withInteractivity(true),
              withDay(2023, Month.AUGUST, 31));
      InventoryManager.checkSkillGrantingEquipment(ItemPool.AUGUST_SCEPTER);

      try (cleanups) {
        var skill = UseSkillRequest.getInstance(SkillPool.AUG_31ST_CABERNET_SAUVIGNON_DAY);
        assertEquals(1, skill.getMaximumCast());
        skill = UseSkillRequest.getInstance(SkillPool.AUG_30TH_BEACH_DAY);
        assertEquals(0, skill.getMaximumCast());
      }
    }

    @Test
    public void castingTodaySkillAfterRunSetsTodayCast() {
      var cleanups =
          new Cleanups(
              withItem(ItemPool.AUGUST_SCEPTER),
              withProperty("_aug12Cast"),
              withProperty("_augSkillsCast", 4),
              withProperty("_augTodayCast", false),
              withInteractivity(true),
              withDay(2023, Month.AUGUST, 12));
      InventoryManager.checkSkillGrantingEquipment(ItemPool.AUGUST_SCEPTER);

      try (cleanups) {
        UseSkillRequest.lastSkillUsed = SkillPool.AUG_12TH_ELEPHANT_DAY;
        UseSkillRequest.lastSkillCount = 1;
        UseSkillRequest.parseResponse(
            "runskillz.php?action=Skillz&whichskill=7463&ajax=1&quantity=1", "response ignored");
        assertThat("_augTodayCast", isSetTo(true));
        assertThat("_augSkillsCast", isSetTo(4));
      }
    }

    @Test
    public void castingTodaySkillInRunIncrementsTotalCast() {
      var cleanups =
          new Cleanups(
              withItem(ItemPool.AUGUST_SCEPTER),
              withProperty("_aug12Cast"),
              withProperty("_augSkillsCast", 4),
              withProperty("_augTodayCast", false),
              withInteractivity(false),
              withDay(2023, Month.AUGUST, 12));
      InventoryManager.checkSkillGrantingEquipment(ItemPool.AUGUST_SCEPTER);

      try (cleanups) {
        UseSkillRequest.lastSkillUsed = SkillPool.AUG_12TH_ELEPHANT_DAY;
        UseSkillRequest.lastSkillCount = 1;
        UseSkillRequest.parseResponse(
            "runskillz.php?action=Skillz&whichskill=7463&ajax=1&quantity=1", "response ignored");
        assertThat("_augTodayCast", isSetTo(false));
        assertThat("_augSkillsCast", isSetTo(5));
      }
    }
  }

  @Nested
  class AdditionalEffects {
    @BeforeEach
    public void initializeState() {
      HttpClientWrapper.setupFakeClient();
      KoLCharacter.reset("AdditionalEffects");
      Preferences.reset("AdditionalEffects");
    }

    @AfterAll
    public static void afterAll() {
      UseSkillRequest.lastSkillUsed = -1;
      UseSkillRequest.lastSkillCount = 0;
    }

    @Test
    public void replaceEffects() {
      var cleanups =
          new Cleanups(
              withEquippableItem(ItemPool.VELOUR_VOULGE),
              withSkill(SkillPool.SNARL_OF_THE_TIMBERWOLF),
              withMP(100, 100, 100));

      try (cleanups) {
        var req =
            UseSkillRequest.getInstance(
                SkillPool.SNARL_OF_THE_TIMBERWOLF, null, 1, EffectPool.SNARL_OF_THREE_TIMBERWOLVES);
        req.run();

        var requests = getRequests();
        assertPostRequest(
            requests.get(0),
            "/inv_equip.php",
            "which=2&ajax=1&action=equip&whichitem=" + ItemPool.VELOUR_VOULGE);
        assertGetRequest(
            requests.get(1),
            "/runskillz.php",
            "action=Skillz&whichskill=" + SkillPool.SNARL_OF_THE_TIMBERWOLF + "&ajax=1&quantity=1");
      }
    }

    @Test
    public void addEffects() {
      var cleanups =
          new Cleanups(
              withEquippableItem(ItemPool.APRIL_SHOWER_THOUGHTS_SHIELD),
              withSkill(SkillPool.SEAL_CLUBBING_FRENZY),
              withMP(100, 100, 100));

      try (cleanups) {
        var req =
            UseSkillRequest.getInstance(
                SkillPool.SEAL_CLUBBING_FRENZY, null, 1, EffectPool.SLIPPERY_AS_A_SEAL);
        req.run();

        var requests = getRequests();
        assertPostRequest(
            requests.get(0),
            "/inv_equip.php",
            "which=2&ajax=1&action=equip&whichitem=" + ItemPool.APRIL_SHOWER_THOUGHTS_SHIELD);
        assertGetRequest(
            requests.get(1),
            "/runskillz.php",
            "action=Skillz&whichskill=" + SkillPool.SEAL_CLUBBING_FRENZY + "&ajax=1&quantity=1");
      }
    }

    @Test
    public void addEffectsWith3HandedWeaponUnequips() {
      var cleanups =
          new Cleanups(
              withEquipped(Slot.WEAPON, ItemPool.GIANT_TURKEY_LEG),
              withEquippableItem(ItemPool.APRIL_SHOWER_THOUGHTS_SHIELD),
              withSkill(SkillPool.SEAL_CLUBBING_FRENZY),
              withMP(100, 100, 100));

      try (cleanups) {
        var req =
            UseSkillRequest.getInstance(
                SkillPool.SEAL_CLUBBING_FRENZY, null, 1, EffectPool.SLIPPERY_AS_A_SEAL);
        req.run();

        var requests = getRequests();
        assertPostRequest(
            requests.get(0), "/inv_equip.php", "which=2&ajax=1&action=unequip&type=weapon");
      }
    }
  }
}
