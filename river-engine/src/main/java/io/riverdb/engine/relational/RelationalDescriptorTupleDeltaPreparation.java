package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import java.nio.ByteBuffer;

/** Builds one exact tuple plan with each before/after physical key encoded at most once. */
final class RelationalDescriptorTupleDeltaPreparation {
  private final RelationalDescriptorTupleDeltaPlan plan;
  private final RelationalDescriptorTupleDeltaStorage storage;
  private final RelationalDescriptorTupleDeltaKeyOrder order =
      new RelationalDescriptorTupleDeltaKeyOrder();
  private final RelationalTupleKeyEncoder encoder = new RelationalTupleKeyEncoder();
  private int mutations;
  private int payload;

  RelationalDescriptorTupleDeltaPreparation(
      RelationalDescriptorTupleDeltaPlan target,
      RelationalDescriptorTupleDeltaStorage retained) {
    plan = target;
    storage = retained;
  }

  StatusCode prepare(
      int operation, TableDescriptor table,
      SqlValueAccess before, SqlValueAccess after,
      long logicalRowId, int rowBytes) {
    plan.reset();
    encoder.clear();
    mutations = 0;
    payload = 0;
    if (!valid(operation, table, before, after, logicalRowId)
        || rowBytes < 0 || rowBytes > TableSchema.MAXIMUM_ROW_BYTES
        || operation != RelationalDescriptorTupleDeltaPlan.DELETE && rowBytes == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int keys = RelationalDescriptorKeySet.count(table);
    if (keys == 0) {
      plan.publish(table, operation, 0, 0, -1, false);
      return StatusCode.OK;
    }
    StatusCode status = reserve(table, keys, operation == RelationalDescriptorTupleDeltaPlan.UPDATE);
    if (status.isOk()) status = order.collectAndSort(table, storage, keys);
    for (int index = 0; status.isOk() && index < keys; index++) {
      status = encode(index, operation, before, after, logicalRowId);
    }
    if (!status.isOk()) {
      plan.reset();
      return status;
    }
    return measure(table, operation, rowBytes);
  }

  private StatusCode reserve(TableDescriptor table, int keys, boolean update) {
    long bytes = 0;
    int userBytes = 0;
    for (int index = 0; index < keys; index++) {
      KeyDescriptor key = RelationalDescriptorKeySet.at(table, index);
      bytes += (update ? 2L : 1L) * key.shape().maximumPhysicalEncodedBytes();
      userBytes = Math.max(userBytes, key.maximumEncodedBytes());
    }
    return bytes > Integer.MAX_VALUE ? StatusCode.RESOURCE_EXHAUSTED
        : storage.reserve(keys, (int) bytes, userBytes);
  }

  private StatusCode encode(
      int index, int operation, SqlValueAccess before,
      SqlValueAccess after, long logicalRowId) {
    KeyDescriptor key = storage.keyAt(index);
    StatusCode status = encodeBefore(index, key, operation, before, logicalRowId);
    if (!status.isOk()) return status;
    if (operation != RelationalDescriptorTupleDeltaPlan.DELETE) {
      status = encoder.encodePhysical(key, after, logicalRowId);
    }
    if (!status.isOk()) return status;
    if (operation == RelationalDescriptorTupleDeltaPlan.UPDATE && same(index)) {
      storage.shareAfter(index);
      return StatusCode.OK;
    }
    if (operation != RelationalDescriptorTupleDeltaPlan.DELETE) {
      storage.copyAfter(index, encoder.bytes(), encoder.length());
    }
    return StatusCode.OK;
  }

  private StatusCode encodeBefore(
      int index, KeyDescriptor key, int operation, SqlValueAccess before, long logicalRowId) {
    if (operation == RelationalDescriptorTupleDeltaPlan.INSERT) return StatusCode.OK;
    StatusCode status = encoder.encodePhysical(key, before, logicalRowId);
    if (status.isOk()) storage.copyBefore(index, encoder.bytes(), encoder.length());
    return status;
  }

  private StatusCode measure(TableDescriptor table, int operation, int rowBytes) {
    int primary = -1;
    for (int index = 0; index < storage.keyCount(); index++) {
      if (storage.keyAt(index).kind() == KeyDescriptor.KIND_PRIMARY
          || table.primaryKey() == null
              && storage.keyAt(index).kind() == KeyDescriptor.KIND_INTERNAL_IDENTITY) {
        primary = index;
      }
    }
    boolean moved = operation == RelationalDescriptorTupleDeltaPlan.UPDATE
        && primary >= 0 && storage.beforeOffsetAt(primary) != storage.afterOffsetAt(primary);
    int locatorBytes = primary < 0 ? 0 : storage.afterLengthAt(primary);
    long totalMutations = 0;
    long totalPayload = 0;
    for (int index = 0; index < storage.keyCount(); index++) {
      boolean clustered = index == primary;
      boolean changed = operation != RelationalDescriptorTupleDeltaPlan.UPDATE
          || storage.beforeOffsetAt(index) != storage.afterOffsetAt(index);
      if (operation == RelationalDescriptorTupleDeltaPlan.DELETE) {
        totalMutations++;
        totalPayload += storage.beforeLengthAt(index);
      } else if (operation == RelationalDescriptorTupleDeltaPlan.INSERT) {
        totalMutations++;
        totalPayload += storage.afterLengthAt(index)
            + (clustered ? rowBytes : locatorBytes);
      } else if (changed) {
        totalMutations += 2;
        totalPayload += storage.beforeLengthAt(index) + storage.afterLengthAt(index)
            + (clustered ? rowBytes : locatorBytes);
      } else if (clustered || moved) {
        totalMutations++;
        totalPayload += storage.afterLengthAt(index)
            + (clustered ? rowBytes : locatorBytes);
      }
    }
    if (totalMutations > Integer.MAX_VALUE || totalPayload > Integer.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    mutations = (int) totalMutations;
    payload = (int) totalPayload;
    plan.publish(table, operation, mutations, payload, primary, moved);
    return StatusCode.OK;
  }

  private boolean same(int index) {
    int length = encoder.length();
    if (storage.beforeLengthAt(index) != length) return false;
    int offset = storage.beforeOffsetAt(index);
    ByteBuffer retained = storage.bytes();
    ByteBuffer candidate = encoder.bytes();
    for (int cursor = 0; cursor < length; cursor++) {
      if (retained.get(offset + cursor) != candidate.get(cursor)) return false;
    }
    return true;
  }

  private static boolean valid(
      int operation, TableDescriptor table,
      SqlValueAccess before, SqlValueAccess after, long rowId) {
    if (table == null || rowId <= 0) return false;
    return validBuffer(before, table) && validBuffer(after, table)
        && validOperation(operation, before, after);
  }

  private static boolean validBuffer(SqlValueAccess values, TableDescriptor table) {
    return values == null || values.count() == table.columnCount();
  }

  private static boolean validOperation(
      int operation, SqlValueAccess before, SqlValueAccess after) {
    if (operation == RelationalDescriptorTupleDeltaPlan.INSERT) {
      return before == null && after != null;
    }
    if (operation == RelationalDescriptorTupleDeltaPlan.DELETE) {
      return before != null && after == null;
    }
    return operation == RelationalDescriptorTupleDeltaPlan.UPDATE
        && before != null && after != null;
  }
}
