package io.riverdb.base.type;

import io.riverdb.base.error.StatusCode;

/** Routes the supported ISO temporal forms to focused parsers. */
final class LocalTemporalParser {
  private LocalTemporalParser() { }

  static StatusCode date(CharSequence text, int start, int end, LocalTemporal.Value result) {
    return LocalTemporalDateParser.date(text, start, end, result);
  }

  static StatusCode time(CharSequence text, int start, int end, LocalTemporal.Value result) {
    return LocalTemporalClockParser.time(text, start, end, result);
  }

  static StatusCode timestamp(CharSequence text, int start, int end, LocalTemporal.Value result) {
    return LocalTemporalDateParser.timestamp(text, start, end, result);
  }

  static StatusCode timestampWithOffset(CharSequence text, int start, int end,
      LocalTemporal.Value result) {
    return LocalTemporalClockParser.timestampWithOffset(text, start, end, result);
  }
}
