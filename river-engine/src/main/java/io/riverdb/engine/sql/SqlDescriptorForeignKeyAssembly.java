package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Builds the local FK descriptor and its exact supporting indexes. */
final class SqlDescriptorForeignKeyAssembly {
  private final KeyDescriptor.Result key = new KeyDescriptor.Result();
  private final TableDescriptor.Result result = new TableDescriptor.Result();
  private KeyDescriptor[] secondary;
  private KeyDescriptor[] foreign;
  private int secondaryCount;
  private int foreignCount;

  StatusCode begin(TableDescriptor source, int foreignKeys) {
    secondaryCount = 0;
    foreignCount = 0;
    result.reset();
    try {
      secondary = new KeyDescriptor[source.secondaryKeyCount() + foreignKeys];
      foreign = new KeyDescriptor[foreignKeys];
      for (int index = 0; index < source.secondaryKeyCount(); index++) {
        secondary[secondaryCount++] = source.secondaryKeyAt(index);
      }
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode add(
      TableDescriptor source, CharSequence name, int count, int[] localParts,
      long referencedKeyId, int ordinal, StatusDetail detail) {
    int[] parts;
    try {
      parts = new int[count];
      System.arraycopy(localParts, 0, parts, 0, count);
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = KeyDescriptor.createNamedUnbound(
        KeyDescriptor.KIND_FOREIGN, false, source.columns(), parts,
        referencedKeyId, name == null || name.length() == 0 ? null : name, key, detail);
    if (status.isOk()) foreign[foreignCount++] = key.value();
    if (status.isOk()) status = KeyDescriptor.createNamedUnbound(
        KeyDescriptor.KIND_SECONDARY, false, source.columns(), parts, 0,
        "_river_fk_" + ordinal, key, detail);
    if (status.isOk()) secondary[secondaryCount++] = key.value();
    return status;
  }

  StatusCode finish(TableDescriptor source, StatusDetail detail) {
    return TableDescriptor.createProposedSuccessor(
        source.tableId(), source.rowLayoutId(), source.catalogGeneration(),
        source.columns(), source.primaryKey(), secondary, foreign, result, detail);
  }

  TableDescriptor descriptor() { return result.value(); }
}
