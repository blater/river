package io.riverdb.engine.runtime.materialized;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import org.junit.jupiter.api.Test;

final class SqlMaterializedPageMappingTest {
  @Test
  void mapsLongLogicalOffsetsAcrossPageHeaders() {
    SqlMaterializedPageLocation location = new SqlMaterializedPageLocation();
    long logicalOffset = (long) Integer.MAX_VALUE + 10;

    assertEquals(StatusCode.OK, SqlMaterializedPageMapping.map(logicalOffset, 64, location));
    long expectedPage = logicalOffset / 32;
    int expectedPayloadOffset = (int) (logicalOffset % 32);
    assertEquals(expectedPage, location.pageNumber());
    assertEquals(64 + expectedPage * 64, location.filePosition());
    assertEquals(32 + expectedPayloadOffset, location.payloadOffset());
    assertEquals(32 - expectedPayloadOffset, location.payloadRemaining());
  }

  @Test
  void rejectsPhysicalOverflowBeforeMutatingResult() {
    SqlMaterializedPageLocation location = new SqlMaterializedPageLocation();
    assertEquals(StatusCode.OK, SqlMaterializedPageMapping.map(17, 64, location));
    long page = location.pageNumber();
    long position = location.filePosition();

    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        SqlMaterializedPageMapping.map(Long.MAX_VALUE, 64, location));
    assertEquals(page, location.pageNumber());
    assertEquals(position, location.filePosition());
    assertEquals(
        StatusCode.RESOURCE_EXHAUSTED,
        SqlMaterializedPageMapping.physicalPosition(Long.MAX_VALUE, 64, location));
  }

  @Test
  void rejectsInvalidInputsWithoutArithmetic() {
    SqlMaterializedPageLocation location = new SqlMaterializedPageLocation();
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        SqlMaterializedPageMapping.map(-1, 64, location));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        SqlMaterializedPageMapping.map(0, 32, location));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        SqlMaterializedPageMapping.physicalPosition(-1, 64, location));
  }
}
