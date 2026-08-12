package io.riverdb.bench.prototype;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PageProtectionModelTest {
  @Test
  void checkpointStormComparisonIsDeterministic() {
    var model = new PageProtectionModel(64, 16 * 1024, 128, 16);
    var first = new PageProtectionResult();
    var repeated = new PageProtectionResult();
    var doubleWrite = new PageProtectionResult();

    model.firstPageImage(1_000, 991L, first);
    model.firstPageImage(1_000, 991L, repeated);
    model.doubleWrite(1_000, 991L, doubleWrite);

    assertEquals(first.totalBytes(), repeated.totalBytes());
    assertEquals(first.forceCalls(), repeated.forceCalls());
    assertEquals(first.uniqueDirtyPages(), doubleWrite.uniqueDirtyPages());
    assertEquals(1_000L, first.dirties());
    assertEquals(first.dirties(), first.firstDirtyPages() + first.redirties());
    assertEquals(first.firstDirtyPages(), first.immutableImageCopies());
    assertEquals(first.copiedBytes(), first.immutableImageCopyBytes());
    assertEquals(0L, first.stagingCopies());
    assertTrue(first.walBytes() > doubleWrite.walBytes());
    assertEquals(0L, first.stagingBytes());
    assertTrue(doubleWrite.stagingBytes() > 0L);
    assertEquals(doubleWrite.firstDirtyPages(), doubleWrite.stagingCopies());
    assertEquals(doubleWrite.copiedBytes(), doubleWrite.stagingCopyBytes());
    assertEquals(0L, doubleWrite.immutableImageCopies());
    assertEquals(first.dataBytes(), doubleWrite.dataBytes());
    assertEquals(first.walForceCalls(), doubleWrite.walForceCalls());
    assertEquals(0L, first.stagingForceCalls());
    assertTrue(doubleWrite.stagingForceCalls() > 0L);
    assertTrue(doubleWrite.forceCalls() > first.forceCalls());
  }

  @Test
  void redirtyStormWritesOneCheckpointImagePerPage() {
    var model = new PageProtectionModel(1, 8 * 1024, 64, 4);
    var first = new PageProtectionResult();
    var doubleWrite = new PageProtectionResult();

    model.firstPageImage(10, 1L, first);
    model.doubleWrite(10, 1L, doubleWrite);

    assertEquals(1L, first.firstDirtyPages());
    assertEquals(9L, first.redirties());
    assertEquals(8 * 1024L, first.dataBytes());
    assertEquals(8 * 1024L, doubleWrite.stagingBytes());
    assertTrue(first.walBytes() > doubleWrite.walBytes());
    assertTrue(doubleWrite.forceCalls() > first.forceCalls());
  }

  @Test
  void repeatedEpochsTakeOneImmutableOrStagingCopyPerFirstDirty() {
    var model = new PageProtectionModel(1, 8 * 1024, 64, 4);
    var first = new PageProtectionResult();
    var doubleWrite = new PageProtectionResult();

    model.firstPageImageEpochs(3, 10, 1L, first);
    model.doubleWriteEpochs(3, 10, 1L, doubleWrite);

    assertEquals(3L, first.checkpointEpochs());
    assertEquals(3L, first.firstDirtyPages());
    assertEquals(27L, first.redirties());
    assertEquals(3L, first.immutableImageCopies());
    assertEquals(3L * 8L * 1024L, first.immutableImageCopyBytes());
    assertEquals(3L, doubleWrite.stagingCopies());
    assertEquals(3L * 8L * 1024L, doubleWrite.stagingCopyBytes());
    assertEquals(3L, first.dataForceCalls());
    assertEquals(3L, doubleWrite.stagingForceCalls());
  }
}
