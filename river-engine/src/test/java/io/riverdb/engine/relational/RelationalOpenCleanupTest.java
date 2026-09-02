package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import org.junit.jupiter.api.Test;

final class RelationalOpenCleanupTest {
  @Test
  void preservesOpenFailureWhenEveryCleanupSucceeds() {
    StatusDetail detail = new StatusDetail(128);
    detail.set(StatusCode.RESOURCE_EXHAUSTED).append("schema cache full");
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, RelationalOpenCleanup.result(
        StatusCode.RESOURCE_EXHAUSTED, StatusCode.OK, StatusCode.OK, detail));
    assertEquals("schema cache full", detail.asString());
  }

  @Test
  void preservesPrimaryFailureAndReportsTheFirstCleanupFailure() {
    StatusDetail detail = new StatusDetail(128);
    detail.set(StatusCode.CORRUPTION).append("invalid catalog root");
    assertEquals(StatusCode.CORRUPTION, RelationalOpenCleanup.result(
        StatusCode.CORRUPTION, StatusCode.IO_FAILURE, StatusCode.INVARIANT_BROKEN, detail));
    assertEquals(StatusCode.CORRUPTION, detail.code());
    assertEquals(
        "invalid catalog root; cleanup also failed: IO_FAILURE", detail.asString());
  }

  @Test
  void reportsASecondCleanupFailureWhenTheFirstCleanupSucceeds() {
    StatusDetail detail = new StatusDetail(128);
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, RelationalOpenCleanup.result(
        StatusCode.RESOURCE_EXHAUSTED, StatusCode.OK,
        StatusCode.INVARIANT_BROKEN, detail));
    assertEquals(StatusCode.RESOURCE_EXHAUSTED, detail.code());
    assertEquals("cleanup also failed: INVARIANT_BROKEN", detail.asString());
  }
}
