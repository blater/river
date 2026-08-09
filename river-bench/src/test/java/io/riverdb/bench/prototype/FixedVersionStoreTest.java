package io.riverdb.bench.prototype;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class FixedVersionStoreTest {
  @Test
  void appendAndVisibilityScanReuseFixedStorageAndSelection() {
    var store = new FixedVersionStore(3);
    var record = new VersionRecord();
    assertEquals(StatusCode.OK, store.append(10L, 1L, 5L, 100L, 0L));
    assertEquals(StatusCode.OK, store.append(11L, 5L, 0L, 200L, 0L));
    assertEquals(StatusCode.OK, store.append(12L, 2L, 8L, 300L, 0L));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, store.append(13L, 3L, 0L, 400L, 0L));

    assertEquals(StatusCode.OK, store.read(1, record));
    assertEquals(11L, record.rowId());
    assertEquals(5L, record.beginSequence());
    assertEquals(0L, record.endSequence());
    assertEquals(200L, record.value());
    assertEquals(0L, record.flags());
    assertEquals(StatusCode.INVARIANT_BROKEN, store.read(3, record));

    assertEquals(2, store.scanVisible(3L));
    assertEquals(400L, store.sumVisibleValues());
    assertEquals(10L, store.selectedRowId(0));
    assertEquals(0L, store.copiedBytes());

    assertEquals(2, store.scanVisible(6L));
    assertEquals(500L, store.sumVisibleValues());
  }
}
