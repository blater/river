package io.riverdb.protocol;

import io.riverdb.base.error.StatusCode;
import java.util.Arrays;

/** Reusable strict UTF-8 decoder for one bounded frame payload range. */
final class ProtocolUtf8Decoder {
  private final char[] characters;
  private String text;
  private int usedCharacters;
  private ProtocolFrame frame;
  private int payloadOffset;
  private int payloadBytes;
  private int input;
  private int output;

  ProtocolUtf8Decoder(int maximumBytes) {
    characters = new char[maximumBytes];
  }

  StatusCode decode(ProtocolFrame frame, int payloadOffset, int payloadBytes) {
    reset();
    if (frame == null || frame.isResponse() || payloadOffset < 0
        || payloadBytes <= 0
        || payloadOffset > frame.payloadBytes() - payloadBytes) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (payloadBytes > characters.length) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    this.frame = frame;
    this.payloadOffset = payloadOffset;
    this.payloadBytes = payloadBytes;
    input = 0;
    output = 0;
    while (input < payloadBytes) {
      StatusCode status = decodeScalar();
      if (!status.isOk()) return invalid();
    }
    text = new String(characters, 0, output);
    releaseSource();
    return StatusCode.OK;
  }

  String text() {
    return text;
  }

  void reset() {
    Arrays.fill(characters, 0, usedCharacters, '\0');
    usedCharacters = 0;
    text = null;
    releaseSource();
  }

  private StatusCode invalid() {
    reset();
    return StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private static boolean continuation(int value) {
    return value >= 0x80 && value <= 0xbf;
  }

  private StatusCode decodeScalar() {
    int first = nextByte();
    if (first < 0x80) {
      append((char) first);
      return StatusCode.OK;
    }
    if (first >= 0xc2 && first <= 0xdf) return decodeTwo(first);
    if (first >= 0xe0 && first <= 0xef) return decodeThree(first);
    return first >= 0xf0 && first <= 0xf4
        ? decodeFour(first) : StatusCode.INVALID_EXTERNAL_INPUT;
  }

  private StatusCode decodeTwo(int first) {
    if (remaining() < 1) return StatusCode.INVALID_EXTERNAL_INPUT;
    int second = nextByte();
    if (!continuation(second)) return StatusCode.INVALID_EXTERNAL_INPUT;
    append((char) ((first & 0x1f) << 6 | second & 0x3f));
    return StatusCode.OK;
  }

  private StatusCode decodeThree(int first) {
    if (remaining() < 2) return StatusCode.INVALID_EXTERNAL_INPUT;
    int second = nextByte();
    int third = nextByte();
    boolean validSecond = continuation(second)
        && (first != 0xe0 || second >= 0xa0)
        && (first != 0xed || second < 0xa0);
    if (!validSecond || !continuation(third)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    append((char) (
        (first & 0x0f) << 12 | (second & 0x3f) << 6 | third & 0x3f));
    return StatusCode.OK;
  }

  private StatusCode decodeFour(int first) {
    if (remaining() < 3) return StatusCode.INVALID_EXTERNAL_INPUT;
    int second = nextByte();
    int third = nextByte();
    int fourth = nextByte();
    boolean validSecond = continuation(second)
        && (first != 0xf0 || second >= 0x90)
        && (first != 0xf4 || second < 0x90);
    if (!validSecond || !continuation(third) || !continuation(fourth)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int codePoint = ((first & 0x07) << 18
        | (second & 0x3f) << 12
        | (third & 0x3f) << 6
        | fourth & 0x3f) - 0x10000;
    append((char) (0xd800 | codePoint >>> 10));
    append((char) (0xdc00 | codePoint & 0x3ff));
    return StatusCode.OK;
  }

  private int nextByte() {
    return frame.payloadByteAt(payloadOffset + input++) & 0xff;
  }

  private int remaining() {
    return payloadBytes - input;
  }

  private void append(char character) {
    characters[output++] = character;
    usedCharacters = output;
  }

  private void releaseSource() {
    frame = null;
    payloadOffset = 0;
    payloadBytes = 0;
    input = 0;
    output = 0;
  }
}
