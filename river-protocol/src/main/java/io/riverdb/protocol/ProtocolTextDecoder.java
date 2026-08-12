package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;

/** Reusable strict UTF-8 decoder for bounded request text. */
public final class ProtocolTextDecoder {
  private final char[] characters;
  private String text;

  public ProtocolTextDecoder(int maximumBytes) {
    characters = new char[maximumBytes];
  }

  public StatusCode decode(ProtocolFrame frame) {
    text = null;
    if (frame == null || frame.isResponse() || frame.payloadBytes() == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (frame.payloadBytes() > characters.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    int input = 0;
    int output = 0;
    while (input < frame.payloadBytes()) {
      int first = frame.payloadByteAt(input++) & 0xff;
      if (first < 0x80) {
        characters[output++] = (char) first;
        continue;
      }
      if (first >= 0xc2 && first <= 0xdf) {
        if (input >= frame.payloadBytes()) {
          return invalid();
        }
        int second = frame.payloadByteAt(input++) & 0xff;
        if (!continuation(second)) {
          return invalid();
        }
        characters[output++] = (char) ((first & 0x1f) << 6 | second & 0x3f);
        continue;
      }
      if (first >= 0xe0 && first <= 0xef) {
        if (input + 1 >= frame.payloadBytes()) {
          return invalid();
        }
        int second = frame.payloadByteAt(input++) & 0xff;
        int third = frame.payloadByteAt(input++) & 0xff;
        boolean validSecond = continuation(second)
            && (first != 0xe0 || second >= 0xa0)
            && (first != 0xed || second < 0xa0);
        if (!validSecond || !continuation(third)) {
          return invalid();
        }
        characters[output++] = (char) (
            (first & 0x0f) << 12 | (second & 0x3f) << 6 | third & 0x3f);
        continue;
      }
      if (first >= 0xf0 && first <= 0xf4) {
        if (input + 2 >= frame.payloadBytes()) {
          return invalid();
        }
        int second = frame.payloadByteAt(input++) & 0xff;
        int third = frame.payloadByteAt(input++) & 0xff;
        int fourth = frame.payloadByteAt(input++) & 0xff;
        boolean validSecond = continuation(second)
            && (first != 0xf0 || second >= 0x90)
            && (first != 0xf4 || second < 0x90);
        if (!validSecond || !continuation(third) || !continuation(fourth)) {
          return invalid();
        }
        int codePoint = (first & 0x07) << 18
            | (second & 0x3f) << 12
            | (third & 0x3f) << 6
            | fourth & 0x3f;
        codePoint -= 0x10000;
        characters[output++] = (char) (0xd800 | codePoint >>> 10);
        characters[output++] = (char) (0xdc00 | codePoint & 0x3ff);
        continue;
      }
      return invalid();
    }
    text = new String(characters, 0, output);
    return StatusCode.OK;
  }

  public String text() {
    return text;
  }

  private StatusCode invalid() {
    text = null;
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static boolean continuation(int value) {
    return value >= 0x80 && value <= 0xbf;
  }
}
