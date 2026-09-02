package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlValueBuffer;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;
import java.nio.ByteBuffer;

/** One reusable before/after tuple plan shared by row-mutation admission and execution. */
final class RelationalDescriptorTupleDeltaPlan {
  static final int INSERT = 1;
  static final int DELETE = 2;
  static final int UPDATE = 3;
  private final RelationalDescriptorTupleDeltaStorage storage;
  private final RelationalDescriptorTupleDeltaPreparation preparation;
  private final RelationalDescriptorTupleDeltaRowBinding rowBinding =
      new RelationalDescriptorTupleDeltaRowBinding();
  private TableDescriptor table;
  private int kind;
  private int mutationCount;
  private int payloadBytes;

  RelationalDescriptorTupleDeltaPlan() {
    this(null, RelationalDescriptorTupleDeltaAllocator.STANDARD);
  }

  RelationalDescriptorTupleDeltaPlan(
      RelationalRetainedBudget budget,
      RelationalDescriptorTupleDeltaAllocator allocator) {
    storage = new RelationalDescriptorTupleDeltaStorage(budget, allocator);
    preparation = new RelationalDescriptorTupleDeltaPreparation(this, storage);
  }

  StatusCode insert(TableDescriptor descriptor, SqlValueBuffer values, long logicalRowId) {
    return preparation.prepare(INSERT, descriptor, null, values, logicalRowId);
  }

  StatusCode delete(TableDescriptor descriptor, SqlValueBuffer values, long logicalRowId) {
    return preparation.prepare(DELETE, descriptor, values, null, logicalRowId);
  }

  StatusCode update(
      TableDescriptor descriptor, SqlValueBuffer before,
      SqlValueBuffer after, long logicalRowId) {
    return preparation.prepare(UPDATE, descriptor, before, after, logicalRowId);
  }

  StatusCode bindLogicalRowId(long logicalRowId) {
    return rowBinding.bind(this, logicalRowId);
  }

  void reset() {
    storage.reset();
    table = null;
    kind = 0;
    mutationCount = 0;
    payloadBytes = 0;
  }

  void publish(TableDescriptor descriptor, int operation, int mutations, int payload) {
    table = descriptor;
    kind = operation;
    mutationCount = mutations;
    payloadBytes = payload;
  }

  boolean matches(TableDescriptor descriptor) { return table == descriptor; }
  int kind() { return kind; }
  int keyCount() { return storage.keyCount(); }
  KeyDescriptor keyAt(int index) { return storage.keyAt(index); }
  int beforeOffsetAt(int index) { return storage.beforeOffsetAt(index); }
  int beforeLengthAt(int index) { return storage.beforeLengthAt(index); }
  int afterOffsetAt(int index) { return storage.afterOffsetAt(index); }
  int afterLengthAt(int index) { return storage.afterLengthAt(index); }
  boolean changedAt(int index) {
    return kind != UPDATE || beforeOffsetAt(index) != afterOffsetAt(index);
  }
  ByteBuffer bytes() { return storage.bytes(); }
  ByteBuffer userKey(int index, boolean after) { return storage.userKey(index, after); }
  int userLength(int index, boolean after) { return storage.userLength(index, after); }
  int mutationCount() { return mutationCount; }
  int payloadBytes() { return payloadBytes; }
}
