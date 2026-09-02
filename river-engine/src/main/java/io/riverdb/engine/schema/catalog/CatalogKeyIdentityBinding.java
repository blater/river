package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.schema.ColumnDescriptorSet;
import io.riverdb.engine.schema.KeyDescriptor;

final class CatalogKeyIdentityBinding {
  private CatalogKeyIdentityBinding() { }

  static StatusCode bind(KeyDescriptor source, ColumnDescriptorSet columns, long id,
      KeyDescriptor.Result result, StatusDetail detail) {
    return bind(source, columns, id, source.referencedKeyId(), result, detail);
  }

  static StatusCode bind(
      KeyDescriptor source, ColumnDescriptorSet columns, long id, long referencedKeyId,
      KeyDescriptor.Result result, StatusDetail detail) {
    int[] ordinals;
    try {
      ordinals = new int[source.partCount()];
    } catch (OutOfMemoryError error) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    for (int index = 0; index < ordinals.length; index++) {
      ordinals[index] = source.columnOrdinalAt(index);
    }
    return source.hasName()
        ? KeyDescriptor.createNamed(id, source.kind(), source.isUnique(),
            columns, ordinals, referencedKeyId, source.name(), result, detail)
        : KeyDescriptor.create(id, source.kind(), source.isUnique(),
            columns, ordinals, referencedKeyId, result, detail);
  }
}
