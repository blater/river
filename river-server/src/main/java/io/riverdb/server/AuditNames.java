package io.riverdb.server;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Encodes the three canonical control names with NUL separators. */
final class AuditNames {
  private AuditNames() { }

  static ByteBuffer encode(String oldName, String newName, String archiveName) {
    byte[] oldBytes = oldName.getBytes(StandardCharsets.UTF_8);
    byte[] newBytes = newName.getBytes(StandardCharsets.UTF_8);
    byte[] archiveBytes = archiveName.getBytes(StandardCharsets.UTF_8);
    ByteBuffer result = ByteBuffer.allocate(
        oldBytes.length + newBytes.length + archiveBytes.length + 3);
    result.put(oldBytes).put((byte) 0).put(newBytes).put((byte) 0)
        .put(archiveBytes).put((byte) 0).flip();
    return result;
  }
}
