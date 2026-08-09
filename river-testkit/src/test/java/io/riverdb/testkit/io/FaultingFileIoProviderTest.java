package io.riverdb.testkit.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.platform.fault.FaultAction;
import io.riverdb.platform.fault.FaultOperation;
import io.riverdb.platform.fault.FaultPoint;
import io.riverdb.platform.fault.FaultPointRegistry;
import io.riverdb.platform.fault.FaultPointSlot;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.platform.file.ForceMode;
import io.riverdb.platform.file.IoResult;
import io.riverdb.platform.file.OpenFileResult;
import io.riverdb.testkit.crash.CrashPointController;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class FaultingFileIoProviderTest {
  @Test
  void forceChoosesTheStateThatSurvivesCrash() {
    Fixture fixture = new Fixture(4);
    DurableFile file = fixture.open();
    assertEquals(StatusCode.OK, write(file, 0, new byte[] {1, 2}));
    assertEquals(StatusCode.OK, file.force(ForceMode.CONTENT_AND_METADATA));
    assertEquals(StatusCode.OK, write(file, 2, new byte[] {3, 4}));

    assertEquals(StatusCode.OK, fixture.provider.crash());
    assertEquals(StatusCode.OK, fixture.provider.restart());

    DurableFile reopened = fixture.open();
    assertArrayEquals(new byte[] {1, 2}, read(reopened, 0, 4, StatusCode.OK));
  }

  @Test
  void shortAndPartialWritesHaveDistinctStatuses() {
    Fixture shortFixture = new Fixture(2);
    assertEquals(
        StatusCode.OK,
        shortFixture.controller.addRule(
            shortFixture.points.write(),
            FaultOperation.WRITE,
            1,
            1,
            FaultAction.SHORT_WRITE,
            2));
    IoResult result = new IoResult();
    StatusCode status = shortFixture.open().write(
        0, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), result);
    assertEquals(StatusCode.OK, status);
    assertEquals(2, result.bytesTransferred());

    Fixture partialFixture = new Fixture(2);
    assertEquals(
        StatusCode.OK,
        partialFixture.controller.addRule(
            partialFixture.points.write(),
            FaultOperation.WRITE,
            1,
            1,
            FaultAction.PARTIAL_WRITE,
            3));
    status = partialFixture.open().write(
        0, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}), result);
    assertEquals(StatusCode.IO_FAILURE, status);
    assertEquals(3, result.bytesTransferred());
  }

  @Test
  void shortReadReturnsOnlyTheScriptedPrefix() {
    Fixture fixture = new Fixture(2);
    assertEquals(
        StatusCode.OK,
        fixture.controller.addRule(
            fixture.points.read(),
            FaultOperation.READ,
            1,
            1,
            FaultAction.SHORT_READ,
            2));
    DurableFile file = fixture.open();
    assertEquals(StatusCode.OK, write(file, 0, new byte[] {1, 2, 3, 4}));

    assertArrayEquals(new byte[] {1, 2}, read(file, 0, 4, StatusCode.OK));
  }

  @Test
  void failedForceDoesNotPublishVolatileBytes() {
    Fixture fixture = new Fixture(2);
    assertEquals(
        StatusCode.OK,
        fixture.controller.addRule(
            fixture.points.force(),
            FaultOperation.FORCE,
            1,
            1,
            FaultAction.FORCE_FAILURE,
            0));
    DurableFile file = fixture.open();
    assertEquals(StatusCode.OK, write(file, 0, new byte[] {9, 8, 7}));
    assertEquals(StatusCode.IO_FAILURE, file.force(ForceMode.CONTENT));

    assertEquals(StatusCode.OK, fixture.provider.crash());
    assertEquals(StatusCode.OK, fixture.provider.restart());
    assertArrayEquals(new byte[0], read(fixture.open(), 0, 3, StatusCode.OK));
  }

  @Test
  void diskFullIsBoundedAndReportsTransferredPrefix() {
    Fixture fixture = new Fixture(2);
    assertEquals(
        StatusCode.OK,
        fixture.controller.addRule(
            fixture.points.write(),
            FaultOperation.WRITE,
            1,
            1,
            FaultAction.DISK_FULL,
            1));
    IoResult result = new IoResult();

    StatusCode status = fixture.open().write(
        0, ByteBuffer.wrap(new byte[] {4, 5, 6}), result);

    assertEquals(StatusCode.RESOURCE_EXHAUSTED, status);
    assertEquals(1, result.bytesTransferred());
  }

  @Test
  void tornWritePrefixCanSurviveReopen() {
    Fixture fixture = new Fixture(2);
    assertEquals(
        StatusCode.OK,
        fixture.controller.addRule(
            fixture.points.write(),
            FaultOperation.WRITE,
            1,
            1,
            FaultAction.TORN_WRITE,
            2));
    assertEquals(StatusCode.IO_FAILURE, write(fixture.open(), 0, new byte[] {6, 7, 8, 9}));

    assertEquals(StatusCode.OK, fixture.provider.crash());
    assertEquals(StatusCode.OK, fixture.provider.restart());

    assertArrayEquals(new byte[] {6, 7}, read(fixture.open(), 0, 4, StatusCode.OK));
  }

  @Test
  void corruptionCancellationAndRestartAreObservable() {
    Fixture corruption = new Fixture(2);
    assertEquals(
        StatusCode.OK,
        corruption.controller.addRule(
            corruption.points.read(),
            FaultOperation.READ,
            1,
            1,
            FaultAction.CORRUPT_READ,
            1));
    DurableFile file = corruption.open();
    assertEquals(StatusCode.OK, write(file, 0, new byte[] {0, 1}));
    assertArrayEquals(new byte[] {1, 0}, read(file, 0, 2, StatusCode.CORRUPTION));

    Fixture cancellation = new Fixture(2);
    assertEquals(
        StatusCode.OK,
        cancellation.controller.addRule(
            cancellation.points.read(),
            FaultOperation.READ,
            1,
            1,
            FaultAction.CANCEL,
            0));
    assertArrayEquals(
        new byte[0],
        read(cancellation.open(), 0, 1, StatusCode.CANCELLED));

    Fixture restart = new Fixture(2);
    assertEquals(
        StatusCode.OK,
        restart.controller.addRule(
            restart.points.read(),
            FaultOperation.READ,
            1,
            1,
            FaultAction.RESTART,
            0));
    DurableFile stale = restart.open();
    assertArrayEquals(new byte[0], read(stale, 0, 1, StatusCode.CANCELLED));
    assertEquals(StatusCode.CANCELLED, write(stale, 0, new byte[] {1}));
    restart.controller.reset();
    assertEquals(StatusCode.OK, write(restart.open(), 0, new byte[] {2}));
  }

  private static StatusCode write(DurableFile file, long position, byte[] bytes) {
    IoResult result = new IoResult();
    return file.write(position, ByteBuffer.wrap(bytes), result);
  }

  private static byte[] read(
      DurableFile file,
      long position,
      int requested,
      StatusCode expectedStatus) {
    IoResult result = new IoResult();
    ByteBuffer target = ByteBuffer.allocate(requested);
    StatusCode status = file.read(position, target, result);
    assertEquals(expectedStatus, status);
    byte[] bytes = new byte[result.bytesTransferred()];
    target.flip();
    target.get(bytes);
    return bytes;
  }

  private static final class Fixture {
    private final CrashPointController controller;
    private final FileFaultPoints points;
    private final FaultingFileIoProvider provider;

    private Fixture(int ruleCapacity) {
      FaultPointRegistry registry = new FaultPointRegistry(6);
      points = new FileFaultPoints(
          point(registry, "file.open"),
          point(registry, "file.read"),
          point(registry, "file.write"),
          point(registry, "file.force"),
          point(registry, "file.truncate"),
          point(registry, "file.close"));
      controller = new CrashPointController(ruleCapacity);
      provider = new FaultingFileIoProvider(2, 64, 4, controller, points);
    }

    private DurableFile open() {
      OpenFileResult result = new OpenFileResult();
      assertEquals(StatusCode.OK, provider.open("wal-0001", result));
      return result.file();
    }

    private static FaultPoint point(FaultPointRegistry registry, String name) {
      FaultPointSlot slot = new FaultPointSlot();
      assertEquals(StatusCode.OK, registry.register(name, slot));
      return slot.value();
    }
  }
}
