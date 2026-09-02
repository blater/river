package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
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
      SqlValueBuffer before, SqlValueBuffer after, long logicalRowId) {
    plan.reset();
    encoder.clear();
    mutations = 0;
    payload = 0;
    if (!valid(operation, table, before, after, logicalRowId)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int keys = RelationalDescriptorKeySet.count(table);
    if (keys == 0) {
      plan.publish(table, operation, 0, 0);
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
    plan.publish(table, operation, mutations, payload);
    return StatusCode.OK;
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
      int index, int operation, SqlValueBuffer before,
      SqlValueBuffer after, long logicalRowId) {
    KeyDescriptor key = storage.keyAt(index);
    StatusCode status = operation == RelationalDescriptorTupleDeltaPlan.INSERT
        ? StatusCode.OK : encoder.encodePhysical(key, before, logicalRowId);
    if (status.isOk() && operation != RelationalDescriptorTupleDeltaPlan.INSERT) {
      storage.copyBefore(index, encoder.bytes(), encoder.length());
    }
    if (status.isOk() && operation != RelationalDescriptorTupleDeltaPlan.DELETE) {
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
    int added = operation == RelationalDescriptorTupleDeltaPlan.UPDATE ? 2 : 1;
    long nextPayload = (long) payload + (operation == RelationalDescriptorTupleDeltaPlan.DELETE
        ? storage.beforeLengthAt(index) : operation == RelationalDescriptorTupleDeltaPlan.INSERT
            ? storage.afterLengthAt(index)
            : storage.beforeLengthAt(index) + storage.afterLengthAt(index));
    if (mutations > Integer.MAX_VALUE - added || nextPayload > Integer.MAX_VALUE) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    mutations += added;
    payload = (int) nextPayload;
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
      SqlValueBuffer before, SqlValueBuffer after, long rowId) {
    if (table == null || rowId <= 0) return false;
    if (before != null && before.count() != table.columnCount()) return false;
    if (after != null && after.count() != table.columnCount()) return false;
    return operation == RelationalDescriptorTupleDeltaPlan.INSERT && before == null && after != null
        || operation == RelationalDescriptorTupleDeltaPlan.DELETE && before != null && after == null
        || operation == RelationalDescriptorTupleDeltaPlan.UPDATE && before != null && after != null;
  }
}
