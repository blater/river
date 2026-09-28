package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.storage.heap.HeapRowResult;

/** Reusable full-row decoder using the table's admitted physical layout. */
final class RelationalDescriptorRowValidation {
  private final RelationalDescriptorRowBuffer rows = new RelationalDescriptorRowBuffer();
  private final SqlValueBuffer values = new SqlValueBuffer();
  private TableDescriptor current;

  StatusCode begin(TableDescriptor descriptor) {
    current = descriptor;
    StatusCode status = RelationalDescriptorShapeValidation.reserve(descriptor, values);
    return status.isOk() ? rows.reserve(descriptor.encodedMaximumRowBytes()) : status;
  }

  StatusCode decode(HeapRowResult row) {
    values.reset();
    return rows.decode(current, row, values);
  }

  SqlValueBuffer values() {
    return values;
  }

  void complete() {
    current = null;
  }
}
