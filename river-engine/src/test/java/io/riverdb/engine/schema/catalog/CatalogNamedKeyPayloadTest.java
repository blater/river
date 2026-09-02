package io.riverdb.engine.schema.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

final class CatalogNamedKeyPayloadTest {
  @Test
  void namedCompositeSecondaryRoundTripsAndParticipatesInSchemaHash() {
    TableDescriptor table = table("by_pair", null);
    CatalogPayloadSize size = new CatalogPayloadSize();
    assertEquals(StatusCode.OK, CatalogKeyPayloadCodec.payloadBytes(table, 0, 2, size));
    ByteBuffer payload = ByteBuffer.allocate(size.bytes());
    assertEquals(StatusCode.OK, CatalogKeyPayloadCodec.encode(table, 0, 2, payload, 0));

    CatalogKeyAccumulator keys = new CatalogKeyAccumulator();
    CatalogIndexSchemaHashState hash = new CatalogIndexSchemaHashState();
    assertEquals(StatusCode.OK, CatalogKeyPayloadCodec.decodeInto(
        payload, 0, payload.capacity(), 3, table.columns(), keys, hash));
    KeyDescriptor[] secondary = keys.secondary();
    assertEquals(1, secondary.length);
    assertTrue(secondary[0].matchesName("by_pair"));
    assertEquals(2, secondary[0].partCount());
    assertTrue(hash.matches(table));
  }

  @Test
  void malformedPersistedNameAndDuplicatePublishedNameAreRejected() {
    TableDescriptor table = table("named", null);
    CatalogPayloadSize size = new CatalogPayloadSize();
    assertEquals(StatusCode.OK, CatalogKeyPayloadCodec.payloadBytes(table, 1, 1, size));
    ByteBuffer payload = ByteBuffer.allocate(size.bytes());
    assertEquals(StatusCode.OK, CatalogKeyPayloadCodec.encode(table, 1, 1, payload, 0));
    int nameStart = CatalogKeyPayloadCodec.HEADER_BYTES
        + CatalogKeyPayloadCodec.KEY_BYTES + 2 * Integer.BYTES;
    payload.put(nameStart, (byte) 0xc0);
    assertEquals(StatusCode.CORRUPTION, CatalogKeyPayloadCodec.decodeInto(
        payload, 0, payload.capacity(), 2, table.columns(),
        new CatalogKeyAccumulator(), new CatalogIndexSchemaHashState()));

    TableDescriptor.Result duplicate = new TableDescriptor.Result();
    assertEquals(StatusCode.CONFLICT, TableDescriptor.create(
        11, 12, 14, table.columns(), table.primaryKey(),
        new KeyDescriptor[] {table.secondaryKeyAt(0), named(
            23, "named", table.columns(), new int[] {1})},
        null, duplicate, null));
  }

  private static TableDescriptor table(String name, String secondName) {
    ColumnDescriptorSet.Result columns = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT, SqlTypeDescriptor.BIGINT},
        new CharSequence[] {"a", "b"}, new boolean[2], columns));
    KeyDescriptor.Result primary = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.create(
        21, KeyDescriptor.KIND_PRIMARY, true, columns.value(), new int[] {0},
        0, primary, null));
    KeyDescriptor first = named(22, name, columns.value(), new int[] {0, 1});
    KeyDescriptor[] secondary = secondName == null
        ? new KeyDescriptor[] {first}
        : new KeyDescriptor[] {first, named(23, secondName, columns.value(), new int[] {1})};
    TableDescriptor.Result result = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        11, 12, 14, columns.value(), primary.value(), secondary, null, result, null));
    return result.value();
  }

  private static KeyDescriptor named(
      long id, String name, ColumnDescriptorSet columns, int[] ordinals) {
    KeyDescriptor.Result result = new KeyDescriptor.Result();
    assertEquals(StatusCode.OK, KeyDescriptor.createNamed(
        id, KeyDescriptor.KIND_SECONDARY, false, columns, ordinals,
        0, name, result, null));
    return result.value();
  }
}
