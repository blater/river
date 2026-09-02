package io.riverdb.format.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.sql.SqlShapeLimits;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;

final class CatalogBuildByteChargeTest {
  @Test
  void intentAndAdmissionShareTheExactBuildMaximum() {
    long maximum = CatalogBuildByteCharge.maximum();
    assertEquals(
        SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES
            + (long) SqlShapeLimits.MAX_SCHEMA_CHUNKS
                * CatalogDefinitionRecordCodec.HEADER_BYTES
            + CatalogDefinitionManifestCodec.BYTES
            + CatalogObjectHeadCodec.BYTES
            + CatalogBuildIntentCodec.BYTES,
        maximum);
    assertEquals(StatusCode.OK, encode(maximum));
    assertEquals(StatusCode.INVALID_EXTERNAL_INPUT, encode(maximum + 1));
  }

  private static StatusCode encode(long catalogBytes) {
    ByteBuffer bytes = ByteBuffer.allocate(CatalogBuildIntentCodec.BYTES);
    return CatalogBuildIntentCodec.encode(bytes, 0,
        CatalogBuildIntentCodec.STATE_BUILDING,
        1, 1, 1, 1, 1, 2,
        SqlShapeLimits.MAX_SCHEMA_CHUNKS,
        0, 0, SqlShapeLimits.MAX_ENCODED_SCHEMA_BYTES,
        catalogBytes, new CRC32C());
  }
}
