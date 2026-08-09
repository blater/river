package io.riverdb.bench.prototype;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class PageIoPrototypeTest {
  @ParameterizedTest
  @ValueSource(ints = {8 * 1024, 16 * 1024, 32 * 1024})
  void positionalRoundTripAndForceUseOwnedTempFile(int pageSize) {
    var opened = new PageIoOpenResult();
    assertEquals(StatusCode.OK, PageIoPrototype.openTemp(pageSize, opened));

    try (PageIoPrototype io = opened.value()) {
      io.prepare(77L);
      assertEquals(StatusCode.OK, io.writePage(3L));
      assertEquals(StatusCode.OK, io.force());
      assertEquals(StatusCode.OK, io.readPage(3L));
      assertEquals(77L, io.readLong(0));
      assertEquals(77L ^ pageSize - Long.BYTES, io.readLong(pageSize - Long.BYTES));
      assertEquals(pageSize, io.counters().writtenBytes());
      assertEquals(pageSize, io.counters().readBytes());
      assertEquals(0L, io.counters().copiedBytes());
      assertEquals(1L, io.counters().forceCalls());
    }
  }
}
