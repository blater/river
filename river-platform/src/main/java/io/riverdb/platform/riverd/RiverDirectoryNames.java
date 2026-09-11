package io.riverdb.platform.riverd;

/** Validates one directory entry under the platform's pathname contract. */
public final class RiverDirectoryNames {
  private RiverDirectoryNames() {
  }

  public static boolean validPosix(String name) {
    return valid(name, false);
  }

  public static boolean validWindows(String name) {
    return valid(name, true);
  }

  private static boolean valid(String name, boolean windows) {
    if (name == null || name.isBlank() || name.length() > 255
        || name.equals(".") || name.equals("..")) return false;
    if (windows && (name.endsWith(".") || name.endsWith(" "))) return false;
    for (int index = 0; index < name.length(); index++) {
      char value = name.charAt(index);
      if (value == '/' || value == '\\' || value == 0 || value == '\r' || value == '\n'
          || (windows && (value == ':' || value == '"' || value == '<' || value == '>'
          || value == '|' || value == '?' || value == '*'))
          || Character.getType(value) == Character.CONTROL) return false;
    }
    return true;
  }
}
