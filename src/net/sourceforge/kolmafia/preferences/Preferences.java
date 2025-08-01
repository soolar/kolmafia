package net.sourceforge.kolmafia.preferences;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import net.java.dev.spellcast.utilities.DataUtilities;
import net.sourceforge.kolmafia.KoLCharacter;
import net.sourceforge.kolmafia.KoLConstants;
import net.sourceforge.kolmafia.KoLmafia;
import net.sourceforge.kolmafia.RequestLogger;
import net.sourceforge.kolmafia.combat.CombatActionManager;
import net.sourceforge.kolmafia.listener.PreferenceListenerRegistry;
import net.sourceforge.kolmafia.moods.MoodManager;
import net.sourceforge.kolmafia.session.MonorailManager;
import net.sourceforge.kolmafia.swingui.AdventureFrame;
import net.sourceforge.kolmafia.utilities.FileUtilities;
import net.sourceforge.kolmafia.utilities.StringUtilities;
import net.sourceforge.kolmafia.webui.CharPaneDecorator;

public class Preferences {
  // If false, blocks saving of all preferences. Do not modify outside of tests.
  public static boolean saveSettingsToFile = true;

  private static final Object lock = new Object(); // used to synch io

  private static final String[] characterMap = new String[65536];

  private static final HashMap<String, String> globalNames = new HashMap<>();
  private static final Map<String, Object> globalValues = new ConcurrentHashMap<>();
  // user/globalEncodedValues cache the byte sequence corresponding to the on-disk representation
  // of a line in the preferences file, so that writing out preferences is simply a matter of
  // concatenating all the cached values.
  private static final SortedMap<String, byte[]> globalEncodedValues =
      Collections.synchronizedSortedMap(new TreeMap<>());
  private static File globalPropertiesFile = null;

  private static final HashMap<String, String> userNames = new HashMap<>();
  private static final Map<String, Object> userValues = new ConcurrentHashMap<>();
  private static final SortedMap<String, byte[]> userEncodedValues =
      Collections.synchronizedSortedMap(new TreeMap<>());
  private static File userPropertiesFile = null;

  private static final Set<String> defaultsSet = new HashSet<>();
  private static final Set<String> perUserGlobalSet = new HashSet<>();
  private static final Set<String> onlyResetOnRollover =
      new TreeSet<>(List.of("ascensionsToday", "potatoAlarmClockUsed"));
  private static final Set<String> legacyNonDailies =
      new TreeSet<>(List.of("_shortOrderCookCharge"));
  private static final Set<String> legacyDailies = new TreeSet<>();
  private static final Set<String> resetOnAscension = new TreeSet<>();
  private static final Set<String> resetOnFight = new TreeSet<>();

  // Obsolete properties.
  private static final String[] obsoleteProperties =
      new String[] {
        "shadowRiftLastNC", "shadowRiftTotalTurns",
      };

  // Map to store deprecation notices for preferences
  protected static final Map<String, String> deprecationNotices = new HashMap<>();

  static {
    // Initialize perUserGlobalSet and read defaults.txt into
    // defaultsSet, globalNames, and userNames
    Preferences.initializeMaps();

    // Read GLOBAL_prefs.txt into globalNames and globalValues
    Preferences.loadGlobalPreferences();
  }

  private Preferences() {}

  private static void initializeMaps() {
    // There are three specific per-user settings that appear in
    // GLOBAL_prefs.txt because the LoginFrame needs them

    Preferences.perUserGlobalSet.add("saveState");
    Preferences.perUserGlobalSet.add("displayName");
    Preferences.perUserGlobalSet.add("getBreakfast");

    BufferedReader istream =
        FileUtilities.getVersionedReader("defaults.txt", KoLConstants.DEFAULTS_VERSION);

    String[] current;
    while ((current = FileUtilities.readData(istream)) != null) {
      if (current.length >= 2) {
        String map = current[0];
        String name = current[1];
        String defaultValue = current.length < 3 ? "" : current[2];
        String attributeString = current.length < 4 ? "" : current[3];

        HashMap<String, String> desiredMap =
            map.equals("global") ? Preferences.globalNames : Preferences.userNames;
        if (desiredMap.containsKey(name)) {
          System.out.println(map + " setting " + name + " multiply defined");
        }

        HashMap<String, String> otherMap =
            map.equals("global") ? Preferences.userNames : Preferences.globalNames;
        if (otherMap.containsKey(name)) {
          String other = map.equals("global") ? "user" : "global";
          System.out.println(
              map + " setting " + name + " already defined as a " + other + " setting");
          continue;
        }

        desiredMap.put(name, defaultValue);
        defaultsSet.add(name);

        // Parse attributes string to learn more about the pref
        var attributes = attributeString.split(",");
        for (var attr : attributes) {
          String trimmed = attr.trim();
          if (trimmed.equals("roa")) resetOnAscension.add(name);
          else if (trimmed.equals("ld")) legacyDailies.add(name);
          else if (trimmed.equals("rof")) resetOnFight.add(name);
          else if (trimmed.startsWith("deprecated")) {
            // Format: deprecated[:optional notice]
            String[] parts = trimmed.split(":", 2);
            String notice = parts.length > 1 ? parts[1].trim() : "";
            deprecationNotices.put(name, notice);
          }
        }
      }
    }

    // Update Mac-specific properties values to ensure
    // that the displays are usable (by default).

    boolean isUsingMac = System.getProperty("os.name").startsWith("Mac");

    Preferences.globalNames.put("chatFontSize", isUsingMac ? "medium" : "small");

    try {
      assert istream != null;
      istream.close();
    } catch (Exception e) {
      // The stream is already closed, go ahead
      // and ignore this error.
    }
  }

  /** Resets all settings so that the given user is represented whenever settings are modified. */
  public static synchronized void reset(String username) {
    // We might not have been tracking encoded values here before this save. Fix that.
    Preferences.reinitializeEncodedValues();
    Preferences.saveToFile(Preferences.globalPropertiesFile, Preferences.globalEncodedValues);
    // Prevent anybody from manipulating the user map until we are
    // done bulk-loading it.
    synchronized (Preferences.userValues) {
      if (username == null || username.equals("")) {
        if (Preferences.userPropertiesFile != null) {
          Preferences.saveToFile(Preferences.userPropertiesFile, Preferences.userEncodedValues);
          Preferences.userPropertiesFile = null;
          Preferences.userValues.clear();
          Preferences.userEncodedValues.clear();
        }

        return;
      }

      Preferences.loadUserPreferences(username);
    }

    AdventureFrame.updateFromPreferences();
    CharPaneDecorator.updateFromPreferences();
    CombatActionManager.updateFromPreferences();
    MoodManager.updateFromPreferences();
    PreferenceListenerRegistry.fireAllPreferencesChanged();
  }

  public static String baseUserName(final String name) {
    return name == null || name.equals("")
        ? "GLOBAL"
        : StringUtilities.globalStringReplace(name.trim(), " ", "_").toLowerCase();
  }

  private static void loadGlobalPreferences() {
    File file =
        new File(KoLConstants.SETTINGS_LOCATION, Preferences.baseUserName("") + "_prefs.txt");
    Preferences.globalPropertiesFile = file;

    Properties p = Preferences.loadPreferences(file);
    Preferences.globalValues.clear();
    Preferences.globalEncodedValues.clear();

    // GLOBAL_prefs.txt can contain obsolete settings which
    // migrated from global to user. Leave them, since the
    // migration will pull the value from the global map
    for (Entry<Object, Object> entry : p.entrySet()) {
      String key = (String) entry.getKey();
      if (!Preferences.globalNames.containsKey(key)) {
        Preferences.isPerUserGlobalProperty(key);
      } // System.out.println( "obsolete global setting detected: " + key );
      // continue;

      String value = (String) entry.getValue();
      Preferences.putGlobal(key, value);
    }

    // For all global properties in defaults.txt which were not in
    // GLOBAL_prefs.txt, add to global map with default value.
    for (Entry<String, String> entry : Preferences.globalNames.entrySet()) {
      String key = entry.getKey();
      if (!Preferences.globalValues.containsKey(key)) {
        // System.out.println( "Adding new built-in global setting: " + key );
        String value = entry.getValue();
        Preferences.putGlobal(key, value);
      }
    }
  }

  private static void loadUserPreferences(String username) {
    File userPrefsFile =
        new File(KoLConstants.SETTINGS_LOCATION, Preferences.baseUserName(username) + "_prefs.txt");
    File backupFile =
        new File(KoLConstants.SETTINGS_LOCATION, Preferences.baseUserName(username) + "_prefs.bak");

    synchronized (lock) {
      Properties p = Preferences.loadPreferences(userPrefsFile);

      if (p.size() == 0) {
        // Something went wrong reading the preferences.
        if (backupFile.exists()) {
          KoLmafia.updateDisplay(
              userPrefsFile
                  + " could not be read, loading backup. "
                  + "This will restore the last successfully opened preferences");
          // also tell system out, in case things are really fubar
          System.out.println("Prefs could not be read and backup exists, trying backup. ");

          p = Preferences.loadPreferences(backupFile);

          if (p.size() > 0) {
            try {
              Files.copy(
                  backupFile.toPath(), userPrefsFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            } catch (IOException ex) {

              KoLmafia.updateDisplay(
                  "Error when restoring preferences from backup,  see session log for details");
              RequestLogger.updateSessionLog(
                  userPrefsFile
                      + " could not be read and backup was used. KoLmafia was unable to copy your backup file to "
                      + "your preferences file and received error message:"
                      + ex.getMessage()
                      + "\nIf this is unexpected, please manually review your preferences and backup and repair any problems."
                      + " If you have a damaged preferences file, "
                      + "please consider creating a bug report on the forum, noting any special circumstances around "
                      + "the failure, and attaching the preferences.");
            }
          }
        } else {
          KoLmafia.updateDisplay("Preferences could not be read and no backup exists.");
          RequestLogger.updateSessionLog(
              userPrefsFile
                  + " could not be read and backup there is no backup file found. "
                  + "If this is unexpected, please manually inspect "
                  + "your preferences file and repair any problems.  If you have a damaged preferences file, "
                  + "please consider creating a bug report on the forum, noting any special circumstances around "
                  + "the failure, and attaching the preferences.");
        }
      } else {
        try {
          Files.copy(
              userPrefsFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
          System.out.println("I/O Error when creating backup preferences file: " + ex.getMessage());
          RequestLogger.updateSessionLog(
              userPrefsFile
                  + " backup creation failed. Please manually inspect "
                  + "your preferences and backup files and repair any problems.  If you have a damaged preferences file, "
                  + "please consider creating a bug report on the forum, noting any special circumstances around "
                  + "the failure, and attaching the preferences.");
        }
      }

      Preferences.userPropertiesFile = null;
      Preferences.userValues.clear();
      Preferences.userEncodedValues.clear();

      for (Entry<Object, Object> currentEntry : p.entrySet()) {
        String key = (String) currentEntry.getKey();
        String value = (String) currentEntry.getValue();

        Preferences.putUser(key, value);
      }

      for (Entry<String, String> entry : Preferences.userNames.entrySet()) {
        String key = entry.getKey();
        if (Preferences.userValues.containsKey(key)) {
          continue;
        }

        // If a user property in defaults.txt was not in
        // NAME_prefs.txt, add to user map with default value
        // (this is how we add a new user property)
        //
        // If it had a value in the GLOBAL map, use that (this
        // is how we migrate a preference from GLOBAL to user)
        String value =
            Preferences.globalValues.containsKey(key)
                ? (String) Preferences.globalValues.get(key)
                : entry.getValue();

        // System.out.println( "Adding new built-in user setting: " + key );
        Preferences.putUser(key, value);
      }

      Preferences.userPropertiesFile = userPrefsFile;
    }
  }

  private static Properties loadPreferences(File file) {
    InputStream istream = DataUtilities.getInputStream(file);

    Properties p = new Properties();
    try {
      p.load(istream);
    } catch (IOException e) {
      System.out.println(e.getMessage() + " trying to load preferences from file.");
    }

    try {
      istream.close();
    } catch (IOException e) {
      System.out.println(e.getMessage() + " trying to close preferences file.");
    }

    return p;
  }

  private static String encodeProperty(String name, String value) {
    StringBuffer buffer = new StringBuffer();

    Preferences.encodeString(buffer, name);

    if (value != null && value.length() > 0) {
      buffer.append("=");
      Preferences.encodeString(buffer, value);
    }
    buffer.append(KoLConstants.LINE_BREAK);

    return buffer.toString();
  }

  private static boolean mustTrackEncodedValues() {
    return Preferences.getBoolean("saveSettingsOnSet") && Preferences.saveSettingsToFile;
  }

  private static void reinitializeEncodedValuesOn(
      Map<String, Object> valuesMap, Map<String, byte[]> encodedMap) {
    synchronized (valuesMap) {
      for (Entry<String, Object> entry : valuesMap.entrySet()) {
        encodedMap.put(
            entry.getKey(),
            encodeProperty(entry.getKey(), entry.getValue().toString())
                .getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  /** Recompute all cached encoded values from the value maps. */
  private static void reinitializeEncodedValues() {
    // No need to do this at all if not writing to a file.
    if (!Preferences.saveSettingsToFile) {
      return;
    }

    Preferences.reinitializeEncodedValuesOn(
        Preferences.globalValues, Preferences.globalEncodedValues);
    Preferences.reinitializeEncodedValuesOn(Preferences.userValues, Preferences.userEncodedValues);
  }

  private static void encodeString(StringBuffer buffer, String string) {
    int length = string.length();

    for (int i = 0; i < length; ++i) {
      char ch = string.charAt(i);
      encodeCharacter(ch);
      buffer.append(characterMap[ch]);
    }
  }

  private static void encodeCharacter(char ch) {
    if (characterMap[ch] != null) {
      return;
    }

    switch (ch) {
      case '\t' -> {
        characterMap[ch] = "\\t";
        return;
      }
      case '\n' -> {
        characterMap[ch] = "\\n";
        return;
      }
      case '\f' -> {
        characterMap[ch] = "\\f";
        return;
      }
      case '\r' -> {
        characterMap[ch] = "\\r";
        return;
      }
      case '\\', '=', ':', '#', '!' -> {
        characterMap[ch] = "\\" + ch;
        return;
      }
    }

    characterMap[ch] =
        (ch > 0x0019 && ch < 0x007f)
            ? String.valueOf(ch)
            : (ch < 0x0010)
                ? "\\u000" + Integer.toHexString(ch)
                : (ch < 0x0100)
                    ? "\\u00" + Integer.toHexString(ch)
                    : (ch < 0x1000)
                        ? "\\u0" + Integer.toHexString(ch)
                        : "\\u" + Integer.toHexString(ch);
  }

  public static boolean propertyExists(final String name) {
    return propertyExists(name, true) || propertyExists(name, false);
  }

  public static boolean propertyExists(final String name, final boolean global) {
    return global
        ? Preferences.globalValues.containsKey(name)
        : Preferences.userValues.containsKey(name);
  }

  public static String getString(final String name, final boolean global) {
    warnIfDeprecated(name);
    Object value = null;

    if (global) {
      if (Preferences.globalValues.containsKey(name)) {
        value = Preferences.globalValues.get(name);
      }
    } else {
      if (Preferences.userValues.containsKey(name)) {
        value = Preferences.userValues.get(name);
      }
    }

    return value == null ? "" : value.toString();
  }

  public static String getDefault(final String name) {
    if (Preferences.globalNames.containsKey(name)) {
      return Preferences.globalNames.get(name);
    }

    if (Preferences.userNames.containsKey(name)) {
      return Preferences.userNames.get(name);
    }

    return "";
  }

  public static void removeProperty(final String name, final boolean global) {
    boolean trackEncoded = Preferences.mustTrackEncodedValues();
    // Remove only properties which do not have defaults
    if (global) {
      if (!Preferences.globalNames.containsKey(name)) {
        // We are changing the structure of the map.
        // globalValues is a synchronized map.

        Preferences.globalValues.remove(name);
        if (trackEncoded) Preferences.globalEncodedValues.remove(name);
      }
    } else {
      if (!Preferences.userNames.containsKey(name)) {
        // We are changing the structure of the map.
        // userValues is a synchronized map.

        Preferences.userValues.remove(name);
        if (trackEncoded) Preferences.userEncodedValues.remove(name);
      }
    }
    Preferences.maybeSaveToFileAfterUpdating(trackEncoded, name);
    PreferenceListenerRegistry.firePreferenceChanged(name);
  }

  public static boolean isGlobalProperty(final String name) {
    return Preferences.globalNames.containsKey(name);
  }

  public static boolean isPerUserGlobalProperty(final String property) {
    if (property.contains(".")) {
      for (String prefix : Preferences.perUserGlobalSet) {
        if (property.startsWith(prefix)) {
          return true;
        }
      }
    }
    return false;
  }

  public static boolean isUserEditable(final String property) {
    return !property.startsWith("saveState") && !property.equals("externalEditor");
  }

  public static void setString(final String name, final String value) {
    setString(null, name, value);
  }

  public static void setString(final String name, final UnaryOperator<String> updater) {
    setString(name, updater.apply(getString(name)));
  }

  public static String getString(final String name) {
    return getString(null, name);
  }

  public static void setBoolean(final String name, final boolean value) {
    setBoolean(null, name, value);
  }

  public static void setBoolean(final String name, final UnaryOperator<Boolean> updater) {
    setBoolean(name, updater.apply(getBoolean(name)));
  }

  public static boolean getBoolean(final String name) {
    return getBoolean(null, name);
  }

  public static void setInteger(final String name, final int value) {
    setInteger(null, name, value);
  }

  public static void setInteger(final String name, final UnaryOperator<Integer> updater) {
    setInteger(name, updater.apply(getInteger(name)));
  }

  public static int getInteger(final String name) {
    return getInteger(null, name);
  }

  public static void setFloat(final String name, final float value) {
    setFloat(null, name, value);
  }

  public static void setFloat(final String name, final UnaryOperator<Float> updater) {
    setFloat(name, updater.apply(getFloat(name)));
  }

  public static float getFloat(final String name) {
    return getFloat(null, name);
  }

  public static void setLong(final String name, final long value) {
    setLong(null, name, value);
  }

  public static void setLong(final String name, final UnaryOperator<Long> updater) {
    setLong(name, updater.apply(getLong(name)));
  }

  public static long getLong(final String name) {
    return getLong(null, name);
  }

  public static void setDouble(final String name, final double value) {
    setDouble(null, name, value);
  }

  public static void setDouble(final String name, final UnaryOperator<Double> updater) {
    setDouble(name, updater.apply(getDouble(name)));
  }

  public static double getDouble(final String name) {
    return getDouble(null, name);
  }

  public static int increment(final String name) {
    return Preferences.increment(name, 1);
  }

  public static int increment(final String name, final int delta) {
    return Preferences.increment(name, delta, 0, false);
  }

  public static int increment(
      final String name, final int delta, final int max, final boolean mod) {
    int current = Preferences.getInteger(name);
    if (delta != 0) {
      current += delta;

      if (max > 0 && current >= max) {
        if (mod) {
          current %= max;
        } else {
          current = max;
        }
      }

      Preferences.setInteger(name, current);
    }
    return current;
  }

  public static int decrement(final String name) {
    return Preferences.decrement(name, 1);
  }

  public static int decrement(final String name, final int delta) {
    return Preferences.decrement(name, delta, 0);
  }

  public static int decrement(final String name, final int delta, final int min) {
    int current = Preferences.getInteger(name);
    if (delta != 0) {
      current -= delta;

      if (current < min) {
        current = min;
      }

      Preferences.setInteger(name, current);
    }
    return current;
  }

  // Per-user global properties are stored in the global settings with
  // key "<name>.<user>"

  public static String getString(final String user, final String name) {
    warnIfDeprecated(name);
    Object value = Preferences.getObject(user, name);

    if (value == null) {
      return "";
    }

    return value.toString();
  }

  public static boolean getBoolean(final String user, final String name) {
    warnIfDeprecated(name);
    Map<String, Object> map = Preferences.getMap(name);
    Object value = Preferences.getObject(map, user, name);

    if (value == null) {
      return false;
    }

    if (!(value instanceof Boolean)) {
      value = Boolean.valueOf(value.toString());
      map.put(name, value);
    }

    return (Boolean) value;
  }

  public static int getInteger(final String user, final String name) {
    warnIfDeprecated(name);
    Map<String, Object> map = Preferences.getMap(name);
    Object value = Preferences.getObject(map, user, name);

    if (value == null) {
      return 0;
    }

    if (!(value instanceof Integer)) {
      value = StringUtilities.parseInt(value.toString());
      map.put(name, value);
    }

    return (Integer) value;
  }

  public static long getLong(final String user, final String name) {
    Map<String, Object> map = Preferences.getMap(name);
    Object value = Preferences.getObject(map, user, name);

    if (value == null) {
      return 0;
    }

    if (!(value instanceof Long)) {
      value = StringUtilities.parseLong(value.toString());
      map.put(name, value);
    }

    return (Long) value;
  }

  public static float getFloat(final String user, final String name) {
    Map<String, Object> map = Preferences.getMap(name);
    Object value = Preferences.getObject(map, user, name);

    if (value == null) {
      return 0.0f;
    }

    if (!(value instanceof Float)) {
      value = StringUtilities.parseFloat(value.toString());
      map.put(name, value);
    }

    return (Float) value;
  }

  public static double getDouble(final String user, final String name) {
    Map<String, Object> map = Preferences.getMap(name);
    Object value = Preferences.getObject(map, user, name);

    if (value == null) {
      return 0.0;
    }

    if (!(value instanceof Double)) {
      value = StringUtilities.parseDouble(value.toString());
      map.put(name, value);
    }

    return (Double) value;
  }

  private static Map<String, Object> getMap(final String name) {
    return Preferences.isGlobalProperty(name) ? Preferences.globalValues : Preferences.userValues;
  }

  private static Object getObject(final String user, final String name) {
    return Preferences.getObject(Preferences.getMap(name), user, name);
  }

  private static Object getObject(
      final Map<String, Object> map, final String user, final String name) {
    String key = Preferences.propertyName(user, name);
    return map.get(key);
  }

  // Used only in ASH get_all_properties.
  public static TreeMap<String, String> getMap(boolean defaults, boolean user) {
    if (defaults) {
      return new TreeMap<>(user ? userNames : globalNames);
    } else {
      TreeMap<String, String> map = new TreeMap<>();
      Map<String, Object> srcmap = user ? userValues : globalValues;
      for (String pref : srcmap.keySet()) {
        map.put(pref, getString(pref));
      }
      return map;
    }
  }

  public static void setString(final String user, final String name, final String value) {
    String old = Preferences.getString(user, name);
    if (!old.equals(value)) {
      Preferences.setObject(user, name, value, value);
    }
  }

  public static void setBoolean(final String user, final String name, final boolean value) {
    boolean old = Preferences.getBoolean(user, name);
    if (old != value) {
      Preferences.setObject(user, name, value ? "true" : "false", value);
    }
  }

  public static void setInteger(final String user, final String name, final int value) {
    int old = Preferences.getInteger(user, name);
    if (old != value) {
      Preferences.setObject(user, name, String.valueOf(value), value);
    }
  }

  public static void setLong(final String user, final String name, final long value) {
    long old = Preferences.getLong(user, name);
    if (old != value) {
      Preferences.setObject(user, name, String.valueOf(value), value);
    }
  }

  public static void setFloat(final String user, final String name, final float value) {
    float old = Preferences.getFloat(user, name);
    if (old != value) {
      Preferences.setObject(user, name, String.valueOf(value), value);
    }
  }

  public static void setDouble(final String user, final String name, final double value) {
    double old = Preferences.getDouble(user, name);
    if (old != value) {
      Preferences.setObject(user, name, String.valueOf(value), value);
    }
  }

  private static void setObject(
      final String user, final String name, final String value, final Object object) {
    if (Preferences.getBoolean("logPreferenceChange")) {
      Set<String> preferenceFilter = new HashSet<>();
      Collections.addAll(
          preferenceFilter, Preferences.getString("logPreferenceChangeFilter").split(","));
      if (!preferenceFilter.contains(name)) {
        String message =
            "Preference " + name + " changed from " + Preferences.getString(name) + " to " + value;
        RequestLogger.printLine(message);
        RequestLogger.updateSessionLog(message);
      }
    }

    boolean trackEncoded = Preferences.mustTrackEncodedValues();

    // We stop tracking encoded values when saveSettingsOnSet is off. When it is turned back on,
    // many encoded values will be out of date, and we don't know which ones, so we have to
    // recompute all of them.
    if (name == "saveSettingsOnSet" && (boolean) object) {
      Preferences.reinitializeEncodedValues();
      trackEncoded |= Preferences.saveSettingsToFile;
    }

    Preferences.put(user, name, object, trackEncoded);
    Preferences.maybeSaveToFileAfterUpdating(trackEncoded, name);

    PreferenceListenerRegistry.firePreferenceChanged(name);

    if (name.startsWith("choiceAdventure")) {
      PreferenceListenerRegistry.firePreferenceChanged("choiceAdventure*");
    }
  }

  private static void putGlobal(final String name, final Object value) {
    putGlobal(name, value, true);
  }

  private static void putGlobal(final String name, final Object value, boolean updateEncoded) {
    Preferences.globalValues.put(name, value);
    if (updateEncoded) {
      Preferences.globalEncodedValues.put(
          name, encodeProperty(name, value.toString()).getBytes(StandardCharsets.UTF_8));
    }
  }

  private static void putUser(final String name, final Object value) {
    Preferences.putUser(name, value, true);
  }

  private static void putUser(final String name, final Object value, boolean updateEncoded) {
    Preferences.userValues.put(name, value);
    if (updateEncoded) {
      Preferences.userEncodedValues.put(
          name, encodeProperty(name, value.toString()).getBytes(StandardCharsets.UTF_8));
    }
  }

  private static void put(
      final String user, final String name, final Object value, boolean updateEncoded) {
    if (Preferences.isGlobalProperty(name)) {
      String actualName = Preferences.propertyName(user, name);
      Preferences.putGlobal(actualName, value, updateEncoded);
    } else if (Preferences.userPropertiesFile != null) {
      putUser(name, value, updateEncoded);
    }
  }

  private static void maybeSaveToFileAfterUpdating(boolean enable, String updatedProperty) {
    if (enable) {
      if (Preferences.isGlobalProperty(updatedProperty)) {
        Preferences.saveToFile(Preferences.globalPropertiesFile, Preferences.globalEncodedValues);
      } else if (Preferences.userPropertiesFile != null) {
        Preferences.saveToFile(Preferences.userPropertiesFile, Preferences.userEncodedValues);
      }
    }
  }

  private static String propertyName(final String user, final String name) {
    return user == null ? name : name + "." + Preferences.baseUserName(user);
  }

  private static void saveToFile(File file, Map<String, byte[]> encodedData) {
    if (!Preferences.saveSettingsToFile) {
      return;
    }

    // See Collections.synchronizedSortedMap
    //
    // We are essentially iterating over the map. Not exactly - we
    // are iterating over the entrySet - but let's keep the map and
    // the file in synch atomically

    synchronized (lock) {
      // Determine the contents of the file by
      // actually printing them.

      OutputStream fstream = new BufferedOutputStream(DataUtilities.getOutputStream(file));

      try {
        synchronized (encodedData) {
          for (Entry<String, byte[]> current : encodedData.entrySet()) {
            fstream.write(current.getValue());
          }
        }
      } catch (IOException e) {
        System.out.println(e.getMessage() + " trying to write preferences as byte array.");
      }

      try {
        fstream.close();
      } catch (IOException e) {
        System.out.println(e.getMessage() + " trying to close preferences stream.");
      }
    }
  }

  public static void resetToDefault(String... names) {
    for (var name : names) {
      if (Preferences.userNames.containsKey(name)) {
        Preferences.setString(name, Preferences.userNames.get(name));
      } else if (Preferences.globalNames.containsKey(name)) {
        Preferences.setString(name, Preferences.globalNames.get(name));
      }
    }
  }

  public static boolean isDaily(String name) {
    return (name.startsWith("_") && !legacyNonDailies.contains(name))
        || legacyDailies.contains(name);
  }

  public static boolean isResetOnAscension(String name) {
    // yearbookCameraUpgrades and deferred points prefs are not really reset on ascension, just
    // incremented.
    return name.equals("muffinOnOrder")
        || name.equals("bwApronMealsEaten")
        || resetOnAscension.contains(name);
  }

  private static void deferredPoints(String prop, String defprop, int max) {
    int deferred = Preferences.getInteger(defprop);
    if (deferred > 0) {
      Preferences.increment(prop, deferred, max, false);
    }
  }

  public static void resetPerAscension() {
    // Deferred ascension rewards
    Preferences.setInteger(
        "yearbookCameraUpgrades", Preferences.getInteger("yearbookCameraAscensions"));
    Preferences.deferredPoints("awolPointsBeanslinger", "awolDeferredPointsBeanslinger", 10);
    Preferences.deferredPoints("awolPointsCowpuncher", "awolDeferredPointsCowpuncher", 10);
    Preferences.deferredPoints("awolPointsSnakeoiler", "awolDeferredPointsSnakeoiler", 10);
    Preferences.deferredPoints("asolPointsCheeseWizard", "asolDeferredPoints", 11);
    Preferences.deferredPoints("asolPointsJazzAgent", "asolDeferredPoints", 11);
    Preferences.deferredPoints("asolPointsPigSkinner", "asolDeferredPoints", 11);
    Preferences.deferredPoints("noobPoints", "noobDeferredPoints", 20);

    // This pref is -1 if unknown, and >= 0 if known, so reset it to 0 on ascension.
    Preferences.setInteger("bwApronMealsEaten", 0);

    // Most prefs that get reset on ascension just return to their default value
    for (String pref : resetOnAscension) {
      resetToDefault(pref);
    }

    // Some need special treatment
    MonorailManager.resetMuffinOrder();
  }

  public static void resetPerRollover() {
    // Some preferences are only reset on rollover
    for (String pref : onlyResetOnRollover) {
      resetToDefault(pref);
    }
  }

  public static void resetDailies() {
    // See Collections.synchronizedSortedMap
    //
    // userValues is a synchronized map, but we are doing a mass
    // change to it.

    synchronized (Preferences.userValues) {
      Iterator<String> it = Preferences.userValues.keySet().iterator();
      while (it.hasNext()) {
        String name = it.next();
        if (isDaily(name)) {
          if (!Preferences.containsDefault(name)) {
            // fully delete preferences that start with _ and aren't in defaults.txt
            it.remove();
            userEncodedValues.remove(name);
            continue;
          }
          String val = Preferences.userNames.get(name);
          if (val == null) val = "";
          Preferences.setString(name, val);
        }
      }
    }
  }

  public static void resetGlobalDailies() {
    // See Collections.synchronizedSortedMap
    //
    // globalValues is a synchronized map, but we are doing a mass
    // change to it.

    synchronized (Preferences.globalValues) {
      for (String name : Preferences.globalValues.keySet()) {
        if (isDaily(name)) {
          String val = Preferences.globalNames.get(name);
          if (val == null) val = "";
          Preferences.setString(name, val);
        }
      }

      Preferences.setLong("lastGlobalCounterDay", KoLCharacter.getRollover());
    }
  }

  public static void resetStartOfFight() {
    for (String pref : resetOnFight) {
      resetToDefault(pref);
    }
  }

  public static boolean containsDefault(String key) {
    return defaultsSet.contains(key);
  }

  private static void warnIfDeprecated(String name) {
    String notice = deprecationNotices.get(name);
    if (notice == null) return;
    if (notice.isBlank()) notice = "This preference is deprecated.";
    RequestLogger.printLine("Warning: Preference '" + name + "' is deprecated. " + notice);
  }
}
