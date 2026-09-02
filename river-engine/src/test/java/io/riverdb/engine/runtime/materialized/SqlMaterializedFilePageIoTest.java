package io.riverdb.engine.runtime.materialized;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class SqlMaterializedFilePageIoTest {
  @Test
  void transfersCompletePagesAndClassifiesShortRead(@TempDir Path root) throws Exception {
    Path path = root.resolve("pages.bin");
    try (FileChannel channel = FileChannel.open(
        path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ,
        StandardOpenOption.WRITE)) {
      SqlMaterializedFilePageIo io = new SqlMaterializedFilePageIo(7, channel);
      ByteBuffer source = ByteBuffer.allocateDirect(64);
      source.putLong(0, 0x1122334455667788L);

      assertEquals(StatusCode.OK, io.write(64, source));
      ByteBuffer target = ByteBuffer.allocateDirect(64);
      assertEquals(StatusCode.OK, io.read(64, target));
      assertEquals(0x1122334455667788L, target.getLong(0));

      channel.truncate(96);
      assertEquals(StatusCode.CORRUPTION, io.read(64, target));
      assertEquals(7, io.fileIdentity());
    }
  }
}
