package io.riverdb.engine.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RiverRuntimeConfigIoTest {
  @Test
  void probeCreateAndDeleteFailuresReturnIoDetail(@TempDir Path root) {
    assertProbeFailure(root, new RuntimeSpillProbe() {
      @Override
      public Path create(Path directory) throws IOException {
        throw new IOException("injected create failure");
      }

      @Override
      public void delete(Path probe) {}
    }, "cannot access spill directory");

    assertProbeFailure(root, new RuntimeSpillProbe() {
      @Override
      public Path create(Path directory) {
        return directory.resolve("injected-probe");
      }

      @Override
      public void delete(Path probe) throws IOException {
        throw new IOException("injected delete failure");
      }
    }, "cannot remove spill probe");
  }

  private static void assertProbeFailure(
      Path root,
      RuntimeSpillProbe probe,
      String message) {
    RiverRuntimeConfig.Result result = new RiverRuntimeConfig.Result();
    StatusDetail detail = new StatusDetail(512);

    assertEquals(
        StatusCode.IO_FAILURE,
        RiverRuntimeConfig.load(
            root,
            2_048_000_000L,
            root.toString(),
            result,
            detail,
            probe));
    assertNull(result.config());
    assertEquals(StatusCode.IO_FAILURE, detail.code());
    assertTrue(detail.asString().contains(message));
  }
}
