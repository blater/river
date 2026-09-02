package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.row.StoredTableRowCodec;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.format.row.StoredTableRowHeader;
import io.riverdb.format.row.StoredTableRowHeaderCodec;
import io.riverdb.storage.heap.HeapRowResult;

/** Reusable full-row decoder that resolves and owns exact retained historical layouts. */
final class RelationalDescriptorRowValidation {
  private final StoredTableRowCodec codec = new StoredTableRowCodec();
  private final StoredTableRowHeader header = new StoredTableRowHeader();
  private final SqlValueBuffer values = new SqlValueBuffer();
  private final RelationalDescriptorHistoricalValidation historical;
  private final RelationalDescriptorRowBytesValidation bytes =
      new RelationalDescriptorRowBytesValidation();
  private TableDescriptor current;

  RelationalDescriptorRowValidation(RelationalDatabaseServices databaseServices) {
    historical = new RelationalDescriptorHistoricalValidation(databaseServices);
  }

  StatusCode begin(TableDescriptor descriptor) {
    current = descriptor;
    return RelationalDescriptorShapeValidation.reserve(descriptor, values);
  }

  StatusCode decode(long logicalRowId, HeapRowResult row) {
    StatusCode status = bytes.copy(row);
    if (!status.isOk()) return status;
    status = StoredTableRowHeaderCodec.decode(bytes.value(), 0, logicalRowId, header);
    if (!status.isOk()) return StatusCode.CORRUPTION;
    TableDescriptor layout = current;
    if (header.rowLayoutId() != current.rowLayoutId()) {
      status = historical.open(current, header.rowLayoutId(), values);
      if (!status.isOk()) return status;
      layout = historical.value();
    }
    values.reset();
    status = codec.decode(
        layout, logicalRowId, bytes.value(), 0, row.length(), values);
    return finish(status, historical.close());
  }

  long primaryKey() {
    return values.valueAt(0);
  }

  SqlValueBuffer values() {
    return values;
  }

  StatusCode complete() {
    StatusCode status = historical.close();
    current = null;
    return status;
  }

  private static StatusCode finish(StatusCode status, StatusCode cleanup) {
    return status.isOk() ? cleanup : status;
  }
}
