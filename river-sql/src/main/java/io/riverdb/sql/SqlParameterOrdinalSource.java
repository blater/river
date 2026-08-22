package io.riverdb.sql;

/** Maps a synthetic query-block marker back to its statement-global ordinal. */
interface SqlParameterOrdinalSource {
  int parameterOrdinal(int offset);

  static int ordinal(CharSequence source, int marker) {
    int ordinal = 0;
    boolean quoted = false;
    for (int index = 0; index < marker; index++) {
      char character = source.charAt(index);
      if (character == '\'' && quoted && index + 1 < marker
          && source.charAt(index + 1) == '\'') {
        index++;
      } else if (character == '\'') {
        quoted = !quoted;
      } else if (!quoted && character == '?') {
        ordinal++;
      }
    }
    return ordinal;
  }
}
