package io.riverdb.protocol;

import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RowResult;
import java.nio.ByteBuffer;

/** Encodes one command or row value vector into the response wire layout. */
final class ProtocolResponseValueEncoder {
  private ProtocolResponseValueEncoder() { }

  static int bytes(CommandResult command, RowResult row, int columns) {
    int bytes = 0;
    for (int index = 0; index < columns; index++) {
      int descriptor = descriptor(command, row, index);
      if (!SqlTypeDescriptor.isValid(descriptor)) return -1;
      if (SqlTypeDescriptor.typeId(descriptor) != SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        bytes += ProtocolDecimal128.bytes(descriptor);
        continue;
      }
      int length = isNull(command, row, index)
          ? 0 : ProtocolResponseTextEncoder.bytes(command, row, index);
      if (length < 0) return -1;
      bytes += Integer.BYTES + length;
    }
    return bytes;
  }

  static int nullBitmapBytes(int columns) {
    return (columns + Byte.SIZE - 1) >>> 3;
  }

  static int writeNulls(
      ByteBuffer target,
      int offset,
      CommandResult command,
      RowResult row,
      int columns) {
    for (int index = 0; index < nullBitmapBytes(columns); index++) {
      long word = command != null
          ? command.nullWord(index >>> 3) : row == null ? 0 : row.nullWord(index >>> 3);
      target.put(offset++, (byte) (word >>> ((index & 7) << 3)));
    }
    return offset;
  }

  static int writeTypes(
      ByteBuffer target,
      int offset,
      CommandResult command,
      RowResult row,
      int columns) {
    for (int index = 0; index < columns; index++) {
      target.putInt(offset, descriptor(command, row, index));
      offset += Integer.BYTES;
    }
    return offset;
  }

  static boolean writeValues(
      ByteBuffer target,
      int offset,
      CommandResult command,
      RowResult row,
      int columns) {
    for (int index = 0; index < columns; index++) {
      int descriptor = descriptor(command, row, index);
      if (SqlTypeDescriptor.typeId(descriptor) == SqlTypeDescriptor.TYPE_ID_VARCHAR) {
        boolean nullValue = isNull(command, row, index);
        int length = nullValue
            ? 0 : ProtocolResponseTextEncoder.bytes(command, row, index);
        if (length < 0) return false;
        target.putInt(offset, length);
        offset += Integer.BYTES;
        if (!nullValue) {
          offset = ProtocolResponseTextEncoder.write(target, offset, command, row, index);
          if (offset < 0) return false;
        }
        continue;
      }
      if (ProtocolDecimal128.isWide(descriptor)) {
        target.putLong(offset, decimalHigh(command, row, index));
        offset += Long.BYTES;
      }
      target.putLong(offset, value(command, row, index));
      offset += Long.BYTES;
    }
    return true;
  }

  private static int descriptor(CommandResult command, RowResult row, int index) {
    return command != null
        ? command.typeDescriptorAt(index)
        : row == null ? 0 : row.typeDescriptorAt(index);
  }

  private static long value(CommandResult command, RowResult row, int index) {
    return command != null
        ? command.valueAt(index) : row == null ? 0 : row.valueAt(index);
  }

  private static long decimalHigh(CommandResult command, RowResult row, int index) {
    return command != null
        ? command.decimalUnscaledHighAt(index)
        : row == null ? 0 : row.decimalUnscaledHighAt(index);
  }

  private static boolean isNull(CommandResult command, RowResult row, int index) {
    return command != null ? command.isNull(index) : row != null && row.isNull(index);
  }
}
