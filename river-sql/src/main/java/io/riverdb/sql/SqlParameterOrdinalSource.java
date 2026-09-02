package io.riverdb.sql;

/** Maps a synthetic query-block marker back to its statement-global ordinal. */
interface SqlParameterOrdinalSource {
  int parameterOrdinal(int offset);

  static int originalOrdinal(
      CharSequence source, int marker, SqlParameterMarkers markers) {
    if (source instanceof SqlParameterOrdinalSource mapped) {
      return mapped.parameterOrdinal(marker);
    }
    return markers == null ? -1 : markers.ordinalAt(marker);
  }
}
