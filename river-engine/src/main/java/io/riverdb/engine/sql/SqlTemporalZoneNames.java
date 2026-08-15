package io.riverdb.engine.sql;

import io.riverdb.sql.SqlCommand;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRulesProvider;
import java.util.Set;

/** Strict bounded SQL zone-name admission and frozen runtime tzdb identity. */
final class SqlTemporalZoneNames {
  private static final int MAXIMUM_CHARACTERS = 128;
  private static final Set<String> REGION_IDS = ZoneId.getAvailableZoneIds();
  private static final String TZDB_VERSION = loadVersion();
  private final char[] characters = new char[MAXIMUM_CHARACTERS];

  ZoneId parse(SqlCommand command, long handle) {
    int length = command.textByteLength(handle);
    if (length < 1 || length > characters.length) return null;
    for (int index = 0; index < length; index++) {
      int character = Byte.toUnsignedInt(command.textByteAt(handle, index));
      if (character > 0x7f) return null;
      characters[index] = (char) character;
    }
    String name = new String(characters, 0, length);
    if ("UTC".equals(name)) return ZoneOffset.UTC;
    ZoneId fixed = fixedOffset(name);
    if (fixed != null) return fixed;
    if (name.indexOf('/') < 0 || !REGION_IDS.contains(name)) return null;
    try {
      return ZoneId.of(name);
    } catch (DateTimeException invalid) {
      return null;
    }
  }

  static String databaseVersion() {
    return TZDB_VERSION;
  }

  private static ZoneId fixedOffset(String name) {
    if (name.length() != 6
        || name.charAt(0) != '+' && name.charAt(0) != '-'
        || name.charAt(3) != ':'
        || !digit(name.charAt(1))
        || !digit(name.charAt(2))
        || !digit(name.charAt(4))
        || !digit(name.charAt(5))) {
      return null;
    }
    int hours = (name.charAt(1) - '0') * 10 + name.charAt(2) - '0';
    int minutes = (name.charAt(4) - '0') * 10 + name.charAt(5) - '0';
    if (hours > 14 || minutes > 59 || hours == 14 && minutes != 0) return null;
    int seconds = (hours * 60 + minutes) * 60;
    return ZoneOffset.ofTotalSeconds(name.charAt(0) == '-' ? -seconds : seconds);
  }

  private static boolean digit(char value) {
    return value >= '0' && value <= '9';
  }

  private static String loadVersion() {
    var versions = ZoneRulesProvider.getVersions("UTC");
    return versions.isEmpty() ? "unknown" : versions.lastKey();
  }
}
