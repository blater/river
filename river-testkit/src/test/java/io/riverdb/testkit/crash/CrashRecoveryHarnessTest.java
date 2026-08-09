package io.riverdb.testkit.crash;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultPoint;
import io.riverdb.platform.fault.FaultPointRegistry;
import io.riverdb.platform.fault.FaultPointSlot;
import io.riverdb.platform.fault.NoOpFaultInjector;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.testkit.io.FaultingFileIoProvider;
import io.riverdb.testkit.io.FileFaultPoints;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class CrashRecoveryHarnessTest {
  @Test
  void repeatsWriteForceCrashReopenVerification() {
    FaultingFileIoProvider provider = provider();
    CrashRecoveryHarness harness = new CrashRecoveryHarness(provider);
    CrashRunReport report = new CrashRunReport();
    IoResult ioResult = new IoResult();

    StatusCode status = harness.run(
        "data-0001",
        8,
        (cycle, file) -> {
          ioResult.reset();
          StatusCode writeStatus = file.write(
              cycle,
              ByteBuffer.wrap(new byte[] {(byte) (cycle + 1)}),
              ioResult);
          if (!writeStatus.isOk()) {
            return writeStatus;
          }
          return file.force(ForceMode.CONTENT_AND_METADATA);
        },
        (cycle, reopened) -> {
          ByteBuffer target = ByteBuffer.allocate(cycle + 1);
          ioResult.reset();
          StatusCode readStatus = reopened.read(0, target, ioResult);
          if (!readStatus.isOk()) {
            return readStatus;
          }
          if (ioResult.bytesTransferred() != cycle + 1) {
            return StatusCode.CORRUPTION;
          }
          target.flip();
          for (int index = 0; index <= cycle; index++) {
            if (target.get() != (byte) (index + 1)) {
              return StatusCode.CORRUPTION;
            }
          }
          return StatusCode.OK;
        },
        report);

    assertEquals(StatusCode.OK, status);
    assertEquals(8, report.completedCycles());
    assertEquals(-1, report.failedCycle());
  }

  private static FaultingFileIoProvider provider() {
    FaultPointRegistry registry = new FaultPointRegistry(6);
    FileFaultPoints points = new FileFaultPoints(
        point(registry, "file.open"),
        point(registry, "file.read"),
        point(registry, "file.write"),
        point(registry, "file.force"),
        point(registry, "file.truncate"),
        point(registry, "file.close"));
    return new FaultingFileIoProvider(1, 64, 4, NoOpFaultInjector.INSTANCE, points);
  }

  private static FaultPoint point(FaultPointRegistry registry, String name) {
    FaultPointSlot slot = new FaultPointSlot();
    assertEquals(StatusCode.OK, registry.register(name, slot));
    return slot.value();
  }
}
