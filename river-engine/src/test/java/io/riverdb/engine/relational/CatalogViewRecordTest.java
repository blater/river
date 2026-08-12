package io.riverdb.engine.relational;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class CatalogViewRecordTest {
  @Test
  void roundTripsBoundedViewDefinition() {
    ByteBuffer encoded = ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES);
    String query = "SELECT id, amount FROM events WHERE amount>=100";
    CatalogRecord.encodeView(encoded, "valuable", query, 7);
    HeapRowResult row = new HeapRowResult();
    row.set(encoded, 1, 0, encoded.remaining());
    ViewDefinition decoded = new ViewDefinition();
    assertEquals(
        StatusCode.OK,
        CatalogRecord.decodeView(
            row,
            ByteBuffer.allocateDirect(CatalogRecord.MAXIMUM_BYTES),
            "valuable",
            decoded));
    assertEquals(query.length(), decoded.length());
    assertEquals(7, decoded.baseTableId());
    for (int index = 0; index < query.length(); index++) {
      assertEquals(query.charAt(index), decoded.charAt(index));
    }
  }
}
