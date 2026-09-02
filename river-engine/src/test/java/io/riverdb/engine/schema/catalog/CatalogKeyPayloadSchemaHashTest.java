package io.riverdb.engine.schema.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.FormatBytes;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class CatalogKeyPayloadSchemaHashTest {
  @Test
  void requiresOneWholeSchemaHashAcrossKeyChunks() {
    TableDescriptor table = CatalogIndexRootTableFixture.table();
    ByteBuffer primary = encode(table, 0);
    ByteBuffer secondary = encode(table, 1);
    CatalogIndexSchemaHashState hash = new CatalogIndexSchemaHashState();
    CatalogKeyAccumulator keys = new CatalogKeyAccumulator();
    assertEquals(StatusCode.OK, CatalogKeyPayloadCodec.decodeInto(primary, 0,
        primary.limit(), 1, table.columns(), keys, hash));
    assertEquals(StatusCode.OK, CatalogKeyPayloadCodec.decodeInto(secondary, 0,
        secondary.limit(), 1, table.columns(), keys, hash));
    assertTrue(hash.matches(table));
    hash.reset();
    keys.reset();
    assertEquals(StatusCode.OK, CatalogKeyPayloadCodec.decodeInto(primary, 0,
        primary.limit(), 1, table.columns(), keys, hash));
    FormatBytes.putLong(secondary, 8, CatalogKeyPayloadCodec.indexSchemaHash(table) + 1);
    assertEquals(StatusCode.CORRUPTION, CatalogKeyPayloadCodec.decodeInto(secondary, 0,
        secondary.limit(), 1, table.columns(), keys, hash));
  }

  private static ByteBuffer encode(TableDescriptor table, int first) {
    CatalogPayloadSize size = new CatalogPayloadSize();
    assertEquals(StatusCode.OK, CatalogKeyPayloadCodec.payloadBytes(table, first, 1, size));
    ByteBuffer bytes = ByteBuffer.allocate(size.bytes());
    assertEquals(StatusCode.OK, CatalogKeyPayloadCodec.encode(table, first, 1, bytes, 0));
    return bytes;
  }
}
