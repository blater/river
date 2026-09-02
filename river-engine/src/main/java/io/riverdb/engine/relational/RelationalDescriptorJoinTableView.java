package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Builds a binder-only legacy table view over an immutable catalog-v2 descriptor. */
public final class RelationalDescriptorJoinTableView {
  private static final int MAXIMUM_NAME_CHARS = 64;
  private final char[] nameChars = new char[MAXIMUM_NAME_CHARS];
  private final Name name = new Name();

  public StatusCode prepare(TableDescriptor descriptor, TableDefinition target) {
    if (descriptor == null || target == null || descriptor.columnCount() < 1) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int columns = descriptor.columnCount();
    StatusCode status = TableDefinitionCapacity.ensure(
        target, columns, 0, 0, descriptor.secondaryKeyCount());
    if (status.isOk()) status = TableDefinitionCapacity.ensureNames(target, columns);
    if (!status.isOk()) return status;
    TableDefinitionStateLoader.reset(target);
    target.tableId = descriptor.tableId() > Integer.MAX_VALUE ? 0 : (int) descriptor.tableId();
    target.columnCount = columns;
    ColumnDescriptorSet source = descriptor.columns();
    for (int column = 0; column < columns; column++) {
      int chars = source.copyNameChars(column, nameChars, 0);
      if (chars < 1 || chars > nameChars.length) {
        TableDefinitionStateLoader.reset(target);
        return StatusCode.CORRUPTION;
      }
      name.set(nameChars, chars);
      target.columnNames[column].set(name);
      target.typeDescriptors[column] = descriptor.typeDescriptorAt(column);
      if (!descriptor.isNullable(column)) target.notNullColumns.set(column);
    }
    publishSingleColumnIndexes(descriptor, target);
    KeyDescriptor primary = descriptor.primaryKey();
    target.primaryIndexColumn = primary == null || primary.partCount() == 0
        ? -1 : primary.columnOrdinalAt(0);
    target.identity = primary != null && primary.partCount() == 1
        && primary.columnOrdinalAt(0) == 0
        && primary.typeDescriptorAt(0) == SqlTypeDescriptor.BIGINT;
    target.schemaVersion = descriptor.catalogGeneration();
    target.durableSchemaId = descriptor.schemaId();
    target.durableRowLayoutId = descriptor.rowLayoutId();
    target.durableCatalogGeneration = descriptor.catalogGeneration();
    target.available = true;
    target.descriptorView = true;
    return StatusCode.OK;
  }

  private static void publishSingleColumnIndexes(
      TableDescriptor descriptor, TableDefinition target) {
    for (int index = 0; index < descriptor.secondaryKeyCount(); index++) {
      KeyDescriptor key = descriptor.secondaryKeyAt(index);
      if (key == null || key.partCount() != 1) continue;
      long durableId = key.keyId();
      int bindingId = durableId > 0 && durableId <= Integer.MAX_VALUE
          ? (int) durableId : index + 1;
      TableDefinitionIndexMutation.set(
          target,
          target.uniqueIndexCount++,
          bindingId,
          TableDefinition.INDEX_READY,
          key.columnOrdinalAt(0),
          key.isUnique(),
          key.kind() == KeyDescriptor.KIND_UNIQUE);
    }
  }

  private static final class Name implements CharSequence {
    private char[] chars;
    private int length;

    void set(char[] source, int count) {
      chars = source;
      length = count;
    }

    @Override public int length() { return length; }
    @Override public char charAt(int index) { return chars[index]; }
    @Override public CharSequence subSequence(int start, int end) {
      throw new UnsupportedOperationException();
    }
  }
}
