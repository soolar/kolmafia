package net.sourceforge.kolmafia;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.sourceforge.kolmafia.utilities.FileUtilities;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

public class DataFileTest {
  public static Stream<Arguments> dataExact() {
    return Stream.of(
        Arguments.of(
            "items.txt",
            1,
            new String[] {
              "\\d+", // itemid
              ".+", // name
              "\\d+", // descid
              "[a-zA-Z_0-9/\\-]+\\.gif", // img
              ("(none|food|drink|spleen|usable|multiple|reusable|message|grow|pokepill|"
                  + "hat|weapon|sixgun|offhand|container|shirt|pants|accessory|familiar|sticker|"
                  + "card|folder|bootspur|bootskin|food helper|drink helper|zap|sphere|guardian|"
                  + "avatar|potion)(\\s*,\\s*(usable|multiple|reusable|combat|combat reusable|single|message|"
                  + "solo|craft|curse|bounty|package|candy1|candy2|candy|chocolate|matchable|fancy|paste|smith|cook|mix))*"), // use
              "([qgtd](,[qgtd])*)?", // access
              "\\d+", // autosell
            }),
        Arguments.of(
            "statuseffects.txt",
            4,
            new String[] {
              "\\d+", // effectid
              ".+", // name
              "[a-zA-Z_0-9/\\-]+\\.gif", // img
              "([a-f0-9]+|)", // descid
              "(bad|neutral|good)", // quality
              "(none|song|nohookah|nopvp|noremove|hottub|notcrs)([ ,](none|song|nohookah|nopvp|noremove|hottub|notcrs))*", // attributes
            }),
        Arguments.of(
            "fullness.txt",
            2,
            new String[] {
              ".+", // name
              "\\d+", // fullness
              "\\d+", // level
              // Missing quality is only for fake food "[glitch season reward name]"
              "(crappy|decent|good|awesome|(super (ultra (mega (turbo )?)?)?)?EPIC|\\?\\?\\?|drippy|pseudoitem|quest|)", // quality
              "-?\\d+(-\\d+)?", // adv
              "-?\\d+(-\\d+)?", // mus
              "-?\\d+(-\\d+)?", // mys
              "-?\\d+(-\\d+)?", // mox
            }),
        Arguments.of(
            "inebriety.txt",
            2,
            new String[] {
              ".+", // name
              "\\d+", // fullness
              "\\d+", // level
              // Missing quality is only for fake drink "ice stein"
              "(crappy|decent|good|awesome|(super (ultra (mega (turbo )?)?)?)?EPIC|\\?\\?\\?|drippy|pseudoitem|quest|)", // quality
              "-?\\d+(-\\d+)?", // adv
              "-?\\d+(-\\d+)?", // mus
              "-?\\d+(-\\d+)?", // mys
              "-?\\d+(-\\d+)?", // mox
            }),
        Arguments.of(
            "spleenhit.txt",
            3,
            new String[] {
              ".+", // name
              "\\d+", // fullness
              "\\d+", // level
              "(|crappy|decent|good|awesome|(super (ultra (mega (turbo )?)?)?)?EPIC)", // quality
              "-?\\d+(-\\d+)?", // adv
              "-?\\d+(-\\d+)?", // mus
              "-?\\d+(-\\d+)?", // mys
              "-?\\d+(-\\d+)?", // mox
            }),
        Arguments.of(
            "questscouncil.txt",
            1,
            new String[] {
              "questL(02Larva|03Rat|04Bat|05Goblin|06Friar|07Cyrptic|08Trapper|09Topping|10Garbage|11MacGuffin|12War|12HippyFrat|13Final|13Warehouse)", // property
              "(started|step1|step7|step13|finished)", // quest step
              ".+", // council text
            }),
        Arguments.of(
            "fambattle.txt",
            1,
            new String[] {
              ".+", // type
              "(x/x|2/2|1/3)", // L2
              "(x/x|3/2|2/3|1/4)", // L3
              "(x/x|4/2|3/3|2/4|1/5)", // L4
              "(Bite|Bonk|Claw|Peck|Punch|Sting)", // M1
              "(Armor Up|Backstab|Breathe Fire|Chill Out|Embarrass|Encourage|Frighten|Growl|Howl|Hug|Laser Beam|Lick|Regrow|Retreat|Splash|Stinkblast|Swoop|Tackle)", // M2
              "(Unknown|Bear Hug|Blood Bath|Defense Matrix|Deluxe Impale|Empowering Cheer|Healing Rain|Nasty Cloud|Nuclear Bomb|Owl Stare|Pepperscorn|Rainbow Storm|Spiky Burst|Stick Treats|Universal Backrub|Violent Shred|Vulgar Display)", // M3
              "(None|Smart|Armor|Spiked|Regenerating)", // Att
            }));
  }

  public static Stream<Arguments> dataVariable() {
    return Stream.of(
        Arguments.of("defaults.txt", 1, new String[] {"user|global", "[^\\s]+", "[^\\t]*"}, 2),
        Arguments.of(
            "classskills.txt",
            6,
            new String[] {"\\d+", "[^\\t]+", "[^\\t]*", "[^\\t]*", "\\d+", "\\d+"},
            6));
  }

  private static String join(String[] parts, String delimiter) {
    if (parts.length == 0) {
      return "";
    }
    StringBuilder sb = new StringBuilder(parts[0]);
    for (int i = 1; i < parts.length; ++i) {
      sb.append(parts[i]);
      sb.append(delimiter);
    }
    return sb.toString();
  }

  @ParameterizedTest
  @MethodSource("dataExact")
  public void testDataFileAgainstRegex(String fname, int version, String[] regexes) {
    test(fname, version, regexes, regexes.length);
  }

  @ParameterizedTest
  @MethodSource("dataVariable")
  public void testDataFileAgainstRegex(String fname, int version, String[] regexes, int minCheck) {
    test(fname, version, regexes, minCheck);
  }

  private void test(String fname, int version, String[] regexes, int minCheck) {
    try (BufferedReader reader = FileUtilities.getVersionedReader(fname, version)) {
      String[] fields;
      boolean bogus = false;

      while ((fields = FileUtilities.readData(reader)) != null) {
        // Some files will have a placeholder line for entries to represent gaps in the ids
        if (fields.length == 1) {
          if (fields[0].matches("\\d+")
                  && (fname.equals("items.txt") || fname.equals("statuseffects.txt"))
              || fname.equals("classskills.txt")) {
            continue;
          }
        }

        if (fields.length < minCheck) {
          System.out.println("Entry for " + fields[0] + " is missing fields");
          bogus = true;
          continue;
        }
        for (int i = 0; i < regexes.length; ++i) {
          if (fields.length <= i) {
            // don't test an absent field
            continue;
          }
          // Assume fields[0] is something that uniquely identifies the row.
          if (!Pattern.matches(regexes[i], fields[i])) {
            System.out.println(
                "Field " + i + " (" + fields[i] + ") did not match:\n" + join(fields, "\t"));
            bogus = true;
          }
        }
      }

      if (bogus) {
        fail("Data errors in " + fname);
      }
    } catch (IOException e) {
      fail("Exception in tearing down reader:" + e.toString());
    }
  }
}
