package io.riverdb.engine.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.cache.SchemaAdmission;
import io.riverdb.engine.schema.cache.SchemaCache;
import io.riverdb.engine.schema.cache.SchemaPin;
import org.junit.jupiter.api.Test;

final class KeyDescriptorNameChargeTest {
  @Test
  void namedKeyChargeIncludesTheRetainedString() {
    ColumnDescriptorSet columns = columns();
    KeyDescriptor unnamed = key(1, null, columns);
    String maximumName = "n".repeat(KeyDescriptor.MAXIMUM_NAME_LENGTH);
    KeyDescriptor named = key(1, maximumName, columns);
    assertEquals(
        SchemaByteCharge.string(maximumName.length()),
        named.byteCharge() - unnamed.byteCharge());
  }

  @Test
  void sixtyFourNamedKeysFitOnlyWithinTheirAccountedCacheBudget() {
    ColumnDescriptorSet columns = columns();
    KeyDescriptor[] secondary = new KeyDescriptor[TableDescriptor.MAXIMUM_SECONDARY_KEYS];
    for (int index = 0; index < secondary.length; index++) {
      secondary[index] = key(index + 1, "index_" + index, columns);
    }
    TableDescriptor.Result table = new TableDescriptor.Result();
    assertEquals(StatusCode.OK, TableDescriptor.create(
        1, 1, 1, columns, null, secondary, null, table, null));

    SchemaCache.Result cache = new SchemaCache.Result();
    assertEquals(StatusCode.OK,
        SchemaCache.create(1, table.value().byteCharge(), cache, null));
    SchemaAdmission admission = new SchemaAdmission();
    assertEquals(StatusCode.OK,
        cache.value().reserveSuccessor(table.value(), 0, admission));
    SchemaPin pin = new SchemaPin();
    assertEquals(StatusCode.OK, admission.publish(table.value(), pin));
    assertTrue(pin.isActive());
    assertEquals(table.value().byteCharge(), cache.value().usedBytes());
    assertEquals(StatusCode.OK, pin.release());
  }

  private static ColumnDescriptorSet columns() {
    ColumnDescriptorSet.Result result = new ColumnDescriptorSet.Result();
    assertEquals(StatusCode.OK, ColumnDescriptorSet.create(
        new int[] {SqlTypeDescriptor.BIGINT},
        new CharSequence[] {"id"}, new boolean[] {false}, result));
    return result.value();
  }

  private static KeyDescriptor key(
      long id, String name, ColumnDescriptorSet columns) {
    KeyDescriptor.Result result = new KeyDescriptor.Result();
    StatusCode status = name == null
        ? KeyDescriptor.create(id, KeyDescriptor.KIND_SECONDARY, false,
            columns, new int[] {0}, 0, result, null)
        : KeyDescriptor.createNamed(id, KeyDescriptor.KIND_SECONDARY, false,
            columns, new int[] {0}, 0, name, result, null);
    assertEquals(StatusCode.OK, status);
    return result.value();
  }
}
