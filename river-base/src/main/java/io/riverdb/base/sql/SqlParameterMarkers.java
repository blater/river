package io.riverdb.base.sql;

/** Shared lexical count of parameter markers outside SQL text literals. */
public final class SqlParameterMarkers {
  private SqlParameterMarkers() { }

  public static int count(CharSequence sql) {
    if (sql == null || sql.isEmpty()) return -1;
    int count = 0;
    boolean quoted = false;
    for (int index = 0; index < sql.length(); index++) {
      char character = sql.charAt(index);
      if (character == '\'') {
        if (quoted && index + 1 < sql.length() && sql.charAt(index + 1) == '\'') index++;
        else quoted = !quoted;
      } else if (!quoted && character == '?') {
        count++;
      }
    }
    return quoted ? -1 : count;
  }
}
