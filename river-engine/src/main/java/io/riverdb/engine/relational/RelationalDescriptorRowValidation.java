package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.storage.heap.HeapRowResult;

/** Reusable full-row view using the table's admitted physical layout. */
final class RelationalDescriptorRowValidation {
  private final StoredTableRowView values = new StoredTableRowView();
  private TableDescriptor current;

  StatusCode begin(TableDescriptor descriptor) {
    current = descriptor;
    return RelationalDescriptorShapeValidation.validate(descriptor);
  }

  StatusCode decode(HeapRowResult row) {
    values.reset();
    return values.bindFetched(current, row, null, null);
  }

  SqlValueAccess values() {
    return values;
  }

  void complete() {
    values.reset();
    current = null;
  }
}
