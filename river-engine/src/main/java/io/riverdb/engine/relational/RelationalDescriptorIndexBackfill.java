package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedRelationalMutation;
import io.riverdb.engine.table.IndexedTransactionSession;

/** Streams one fixed descriptor-table snapshot into bounded private BUILDING batches. */
public final class RelationalDescriptorIndexBackfill {
  private static final int BATCH_ROWS = 128;
  private final RelationalSession owner;
  private final RelationalDescriptorTableAccess rows;
  private final RelationalDescriptorIndexBuildSession build;
  private final RelationalDescriptorScanCursor cursor = new RelationalDescriptorScanCursor();
  private final RelationalRowIdentityResult identity = new RelationalRowIdentityResult();
  private final SqlValueBuffer values = new SqlValueBuffer();
  private final RelationalTupleKeyEncoder encoder = new RelationalTupleKeyEncoder();
  private boolean exhausted;
  private int batchRows;

  RelationalDescriptorIndexBackfill(
      RelationalSession relational,
      RelationalDescriptorTableAccess tableRows,
      RelationalDescriptorIndexBuildSession buildSession) {
    owner = relational;
    rows = tableRows;
    build = buildSession;
  }

  public StatusCode measure(
      SchemaPin pin, int secondaryOrdinal, RelationalIndexBackfillPlan result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    TableDescriptor table = table(pin);
    KeyDescriptor key = key(table, secondaryOrdinal);
    if (key == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.set(table.tableId(), table.schemaId(), key.keyId());
    return StatusCode.OK;
  }

  public StatusCode stage(
      SchemaPin pin, int secondaryOrdinal, RelationalIndexBackfillPlan plan) {
    TableDescriptor table = table(pin);
    KeyDescriptor key = key(table, secondaryOrdinal);
    if (build == null || key == null || plan == null
        || !plan.matches(table.tableId(), table.schemaId(), key.keyId())) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    exhausted = false;
    StatusCode status = cursor.reset();
    if (status.isOk()) status = rows.beginScan(pin, cursor);
    while (status.isOk() && !exhausted) status = stageBatch(table, key);
    return close(status);
  }

  private StatusCode stageBatch(TableDescriptor table, KeyDescriptor key) {
    batchRows = 0;
    StatusCode status = build.begin(BATCH_ROWS, maximumBatchBytes(key));
    while (status.isOk() && batchRows < BATCH_ROWS && !exhausted) {
      status = rows.nextScan(cursor, values, identity);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        exhausted = true;
      } else if (status.isOk()) {
        if (batchRows == 0) status = build.indexed().stageTupleIndexBuildingBatch(
            table.tableId(), key.keyId(), key.keyId(), table.schemaId(), key.shape());
        if (status.isOk()) status = stageRow(table, key);
      }
    }
    return build.finish(status, batchRows > 0);
  }

  private StatusCode stageRow(TableDescriptor table, KeyDescriptor key) {
    IndexedTransactionSession session = build.indexed();
    StatusCode status = validateUnique(session, table, key);
    if (status.isOk()) {
      status = encoder.encodePhysical(key, values, identity.logicalRowId());
    }
    if (status.isOk()) status = session.appendTupleMutation(
        IndexedRelationalMutation.TUPLE_INSERT,
        table.tableId(), key.keyId(), key.keyId(), key.shape(),
        identity.logicalRowId(), encoder.bytes(), 0, encoder.length());
    if (status.isOk()) batchRows++;
    return status;
  }

  private StatusCode validateUnique(
      IndexedTransactionSession session, TableDescriptor table, KeyDescriptor key) {
    if (!key.isUnique()) return StatusCode.OK;
    StatusCode status = encoder.encodeUser(key, values);
    if (!status.isOk() || encoder.containsNull()) return status;
    return session.validateTupleBuildingUniquePrefix(
        table.tableId(), key.keyId(), key.keyId(), table.schemaId(), key.shape(),
        encoder.bytes(), 0, encoder.length(), identity.logicalRowId());
  }

  StatusCode closeSession() {
    return build == null ? StatusCode.OK : build.close();
  }

  private static int maximumBatchBytes(KeyDescriptor key) {
    int bytes = key.shape().maximumPhysicalEncodedBytes();
    return bytes <= Integer.MAX_VALUE / BATCH_ROWS ? bytes * BATCH_ROWS : -1;
  }

  private TableDescriptor table(SchemaPin pin) {
    return RelationalDescriptorPin.validTable(owner, pin);
  }

  private static KeyDescriptor key(TableDescriptor table, int ordinal) {
    KeyDescriptor key = table == null ? null : table.secondaryKeyAt(ordinal);
    return key != null && key.keyId() > 0 ? key : null;
  }

  private StatusCode close(StatusCode status) {
    if (!cursor.isActive()) return status;
    StatusCode closed = rows.closeScan(cursor);
    return status.isOk() ? closed : status;
  }
}
