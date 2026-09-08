package io.riverdb.server;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.file.FileSizeResult;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.riverd.RiverFile;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Full SHA-256 binding used only during bootstrap/recovery/control publication. */
final class AuditDigest {
  private AuditDigest() { }

  static byte[] file(RiverFile file) {
    FileSizeResult size = new FileSizeResult();
    if (file == null || !file.size(size).isOk() || size.sizeBytes() < 0) return null;
    return bytes(file, size.sizeBytes());
  }

  static byte[] prefix(RiverFile file, long length) {
    if (file == null || length < 0) return null;
    FileSizeResult size = new FileSizeResult();
    if (!file.size(size).isOk() || size.sizeBytes() < length) return null;
    return bytes(file, length);
  }

  private static byte[] bytes(RiverFile file, long length) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      ByteBuffer buffer = ByteBuffer.allocate(8192);
      IoResult io = new IoResult();
      long position = 0;
      while (position < length) {
        buffer.clear();
        buffer.limit((int) Math.min(buffer.capacity(), length - position));
        io.reset();
        StatusCode status = file.read(position, buffer, io);
        if (!status.isOk() || io.bytesTransferred() <= 0) return null;
        buffer.flip();
        digest.update(buffer);
        position += io.bytesTransferred();
      }
      return digest.digest();
    } catch (NoSuchAlgorithmException impossible) {
      return null;
    }
  }

  static byte[] name(String name) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return digest.digest(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException impossible) {
      return null;
    }
  }
}
