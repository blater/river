package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Rebinds immutable key metadata to an ordinal-compatible successor column set. */
final class RelationalDescriptorKeyCopy {
  private final KeyDescriptor.Result result = new KeyDescriptor.Result();

  StatusCode table(
      TableDescriptor current,
      ColumnDescriptorSet columns,
      TableDescriptor.Result successor,
      StatusDetail detail) {
    KeyDescriptor primary = current.primaryKey() == null
        ? null : key(current.primaryKey(), columns, null, detail);
    if (current.primaryKey() != null && primary == null) return status(detail);
    KeyDescriptor[] secondary;
    KeyDescriptor[] foreign;
    try {
      secondary = new KeyDescriptor[current.secondaryKeyCount()];
      foreign = new KeyDescriptor[current.foreignKeyCount()];
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    for (int index = 0; index < secondary.length; index++) {
      secondary[index] = key(current.secondaryKeyAt(index), columns, null, detail);
      if (secondary[index] == null) return status(detail);
    }
    for (int index = 0; index < foreign.length; index++) {
      foreign[index] = key(current.foreignKeyAt(index), columns, null, detail);
      if (foreign[index] == null) return status(detail);
    }
    return TableDescriptor.createProposedSuccessor(
        current.tableId(), current.rowLayoutId(), current.catalogGeneration(),
        columns, primary, secondary, foreign, successor, detail);
  }

  KeyDescriptor key(
      KeyDescriptor source,
      ColumnDescriptorSet columns,
      CharSequence renamedName,
      StatusDetail detail) {
    int[] ordinals;
    try {
      ordinals = new int[source.partCount()];
    } catch (OutOfMemoryError error) {
      if (detail != null) detail.set(StatusCode.RESOURCE_EXHAUSTED);
      return null;
    }
    for (int part = 0; part < ordinals.length; part++) {
      ordinals[part] = source.columnOrdinalAt(part);
    }
    CharSequence name = renamedName != null ? renamedName : source.name();
    StatusCode status = name == null
        ? KeyDescriptor.create(
            source.keyId(), source.kind(), source.isUnique(), columns,
            ordinals, source.referencedKeyId(), result, detail)
        : KeyDescriptor.createNamed(
            source.keyId(), source.kind(), source.isUnique(), columns,
            ordinals, source.referencedKeyId(), name, result, detail);
    return status.isOk() ? result.value() : null;
  }

  static StatusCode status(StatusDetail detail) {
    return detail == null || detail.code().isOk()
        ? StatusCode.INVALID_EXTERNAL_INPUT : detail.code();
  }
}
