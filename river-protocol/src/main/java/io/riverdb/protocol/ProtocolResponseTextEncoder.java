package io.riverdb.protocol;

import io.riverdb.engine.api.CommandResult;
import io.riverdb.engine.api.RowResult;
import java.nio.ByteBuffer;

/** Copies canonical result UTF-8 into response fields without decoding or allocating. */
final class ProtocolResponseTextEncoder {
  private ProtocolResponseTextEncoder() { }

  static int bytes(CommandResult command, RowResult row, int index) {
    return command != null
        ? command.encodedTextLengthAt(index) : row.encodedTextLengthAt(index);
  }

  static int write(
      ByteBuffer target, int offset,
      CommandResult command, RowResult row, int index) {
    int copied = command != null
        ? command.copyEncodedTextAt(index, target, offset)
        : row.copyEncodedTextAt(index, target, offset);
    return copied < 0 ? -1 : offset + copied;
  }
}
