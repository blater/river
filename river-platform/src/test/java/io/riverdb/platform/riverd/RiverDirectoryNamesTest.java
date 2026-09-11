package io.riverdb.platform.riverd;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class RiverDirectoryNamesTest {
  @Test
  void posixNamesKeepTheExistingBoundaryAndCharacterRules() {
    assertTrue(RiverDirectoryNames.validPosix("record"));
    assertTrue(RiverDirectoryNames.validPosix("name.with:colon"));
    assertTrue(RiverDirectoryNames.validPosix("a".repeat(255)));

    assertFalse(RiverDirectoryNames.validPosix(null));
    assertFalse(RiverDirectoryNames.validPosix(" "));
    assertFalse(RiverDirectoryNames.validPosix("."));
    assertFalse(RiverDirectoryNames.validPosix(".."));
    assertFalse(RiverDirectoryNames.validPosix("a".repeat(256)));
    assertFalse(RiverDirectoryNames.validPosix("a/b"));
    assertFalse(RiverDirectoryNames.validPosix("a\\b"));
    assertFalse(RiverDirectoryNames.validPosix("a\0b"));
    assertFalse(RiverDirectoryNames.validPosix("a\nb"));
  }

  @Test
  void windowsNamesAddWindowsForbiddenCharactersAndSuffixes() {
    assertTrue(RiverDirectoryNames.validWindows("record"));
    assertTrue(RiverDirectoryNames.validWindows("a".repeat(255)));

    assertFalse(RiverDirectoryNames.validWindows("a:b"));
    assertFalse(RiverDirectoryNames.validWindows("a\"b"));
    assertFalse(RiverDirectoryNames.validWindows("a<b"));
    assertFalse(RiverDirectoryNames.validWindows("a>b"));
    assertFalse(RiverDirectoryNames.validWindows("a|b"));
    assertFalse(RiverDirectoryNames.validWindows("a?b"));
    assertFalse(RiverDirectoryNames.validWindows("a*b"));
    assertFalse(RiverDirectoryNames.validWindows("record."));
    assertFalse(RiverDirectoryNames.validWindows("record "));
    assertFalse(RiverDirectoryNames.validWindows("a".repeat(256)));
  }
}
