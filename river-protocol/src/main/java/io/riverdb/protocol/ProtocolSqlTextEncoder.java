package io.riverdb.protocol;

import io.riverdb.engine.api.TransactionProgramArguments;
import java.nio.ByteBuffer;

/** Encodes validated SQL and program text into caller-owned wire storage. */
final class ProtocolSqlTextEncoder {
  private static final int MAX_SQL_BYTES = io.riverdb.base.sql.SqlShapeLimits.MAX_SQL_TEXT_BYTES;

  private ProtocolSqlTextEncoder() { }

  static int sqlBytes(String text) {
    int bytes = 0;
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character < 0x80) {
        bytes++;
      } else if (character < 0x800) {
        bytes += 2;
      } else if (Character.isHighSurrogate(character)) {
        if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) return -1;
        bytes += 4;
      } else if (Character.isLowSurrogate(character)) {
        return -1;
      } else {
        bytes += 3;
      }
      if (bytes > MAX_SQL_BYTES) return bytes;
    }
    return bytes;
  }

  static int writeSql(ByteBuffer target, int output, String text) {
    for (int index = 0; index < text.length(); index++) {
      char character = text.charAt(index);
      if (character < 0x80) {
        target.put(output++, (byte) character);
      } else if (character < 0x800) {
        target.put(output++, (byte) (0xc0 | character >>> 6));
        target.put(output++, (byte) (0x80 | character & 0x3f));
      } else if (Character.isHighSurrogate(character)) {
        int scalar = Character.toCodePoint(character, text.charAt(++index));
        target.put(output++, (byte) (0xf0 | scalar >>> 18));
        target.put(output++, (byte) (0x80 | scalar >>> 12 & 0x3f));
        target.put(output++, (byte) (0x80 | scalar >>> 6 & 0x3f));
        target.put(output++, (byte) (0x80 | scalar & 0x3f));
      } else {
        target.put(output++, (byte) (0xe0 | character >>> 12));
        target.put(output++, (byte) (0x80 | character >>> 6 & 0x3f));
        target.put(output++, (byte) (0x80 | character & 0x3f));
      }
    }
    return output;
  }

  static int argumentBytes(TransactionProgramArguments arguments, int index) {
    int characters = arguments.textLengthAt(index);
    if (characters < 0) return -1;
    int bytes = 0;
    for (int character = 0; character < characters; character++) {
      char value = arguments.textCharacterAt(index, character);
      if (value < 0x80) bytes++;
      else if (value < 0x800) bytes += 2;
      else if (Character.isHighSurrogate(value)) {
        if (++character >= characters
            || !Character.isLowSurrogate(arguments.textCharacterAt(index, character))) return -1;
        bytes += 4;
      } else if (Character.isLowSurrogate(value)) {
        return -1;
      } else {
        bytes += 3;
      }
    }
    return bytes;
  }

  static int writeArgument(
      ByteBuffer target, int output, TransactionProgramArguments arguments, int index) {
    int characters = arguments.textLengthAt(index);
    for (int character = 0; character < characters; character++) {
      char value = arguments.textCharacterAt(index, character);
      if (value < 0x80) {
        target.put(output++, (byte) value);
      } else if (value < 0x800) {
        target.put(output++, (byte) (0xc0 | value >>> 6));
        target.put(output++, (byte) (0x80 | value & 0x3f));
      } else if (Character.isHighSurrogate(value)) {
        int scalar = Character.toCodePoint(value,
            arguments.textCharacterAt(index, ++character));
        target.put(output++, (byte) (0xf0 | scalar >>> 18));
        target.put(output++, (byte) (0x80 | scalar >>> 12 & 0x3f));
        target.put(output++, (byte) (0x80 | scalar >>> 6 & 0x3f));
        target.put(output++, (byte) (0x80 | scalar & 0x3f));
      } else {
        target.put(output++, (byte) (0xe0 | value >>> 12));
        target.put(output++, (byte) (0x80 | value >>> 6 & 0x3f));
        target.put(output++, (byte) (0x80 | value & 0x3f));
      }
    }
    return output;
  }
}
