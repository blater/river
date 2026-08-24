package io.riverdb.protocol;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RowResult;
import java.nio.ByteBuffer;

/** Encodes bounded UTF-16 text values as canonical UTF-8 response fields. */
final class ProtocolResponseTextEncoder {
  private ProtocolResponseTextEncoder() { }

  static int bytes(CommandResult command, RowResult row, int index, int characters, int descriptor) {
    if (characters < 0 || characters > CommandResult.MAXIMUM_TEXT_CHARACTERS) return -1;
    int bytes = 0;
    int scalars = 0;
    for (int character = 0; character < characters; character++) {
      char first = textCharacter(command, row, index, character);
      int scalar = first;
      if (Character.isHighSurrogate(first)) {
        if (++character >= characters) return -1;
        char second = textCharacter(command, row, index, character);
        if (!Character.isLowSurrogate(second)) return -1;
        scalar = Character.toCodePoint(first, second);
      } else if (Character.isLowSurrogate(first)) return -1;
      if (++scalars > SqlTypeDescriptor.parameterOne(descriptor)) return -1;
      bytes += utf8Bytes(scalar);
    }
    return bytes;
  }

  static int write(ByteBuffer target, int offset, CommandResult command, RowResult row,
      int index, int characters) {
    for (int character = 0; character < characters; character++) {
      char first = textCharacter(command, row, index, character);
      int scalar = Character.isHighSurrogate(first)
          ? Character.toCodePoint(first, textCharacter(command, row, index, ++character)) : first;
      offset = writeScalar(target, offset, scalar);
    }
    return offset;
  }

  private static char textCharacter(CommandResult command, RowResult row, int index, int character) {
    return command != null ? command.textCharacterAt(index, character)
        : row.textCharacterAt(index, character);
  }

  private static int writeScalar(ByteBuffer target, int offset, int scalar) {
    if (scalar <= 0x7f) target.put(offset++, (byte) scalar);
    else if (scalar <= 0x7ff) {
      target.put(offset++, (byte) (0xc0 | scalar >>> 6));
      target.put(offset++, (byte) (0x80 | scalar & 0x3f));
    } else if (scalar <= 0xffff) {
      target.put(offset++, (byte) (0xe0 | scalar >>> 12));
      target.put(offset++, (byte) (0x80 | scalar >>> 6 & 0x3f));
      target.put(offset++, (byte) (0x80 | scalar & 0x3f));
    } else {
      target.put(offset++, (byte) (0xf0 | scalar >>> 18));
      target.put(offset++, (byte) (0x80 | scalar >>> 12 & 0x3f));
      target.put(offset++, (byte) (0x80 | scalar >>> 6 & 0x3f));
      target.put(offset++, (byte) (0x80 | scalar & 0x3f));
    }
    return offset;
  }

  private static int utf8Bytes(int scalar) {
    return scalar <= 0x7f ? 1 : scalar <= 0x7ff ? 2 : scalar <= 0xffff ? 3 : 4;
  }
}
