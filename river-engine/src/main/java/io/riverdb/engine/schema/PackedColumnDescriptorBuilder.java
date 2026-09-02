package io.riverdb.engine.schema;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import java.nio.ByteBuffer;

/** Caller-owned assembly of validated packed UTF-8 columns without per-name objects. */
public final class PackedColumnDescriptorBuilder {
  private final PackedColumnDescriptorStorage storage;

  public PackedColumnDescriptorBuilder() {
    this(PackedColumnArrayAllocator.STANDARD);
  }

  PackedColumnDescriptorBuilder(PackedColumnArrayAllocator allocator) {
    storage = new PackedColumnDescriptorStorage(allocator);
  }

  public StatusCode begin(int columns, int maximumNameBytes) {
    return storage.begin(columns, maximumNameBytes);
  }

  public StatusCode reserve(int addedColumns, int addedNameBytes) {
    return storage.reserve(addedColumns, addedNameBytes);
  }

  public StatusCode putReserved(
      int relativeColumn,
      int descriptor,
      boolean nullable,
      ByteBuffer source,
      int sourceOffset,
      int nameBytes,
      int relativeNameOffset) {
    return storage.put(relativeColumn, descriptor, nullable, source, sourceOffset,
        nameBytes, relativeNameOffset);
  }

  public StatusCode publishReserved(int addedColumns, int addedNameBytes) {
    return storage.publish(addedColumns, addedNameBytes);
  }

  public StatusCode finish(ColumnDescriptorSet.Result result, StatusDetail detail) {
    return PackedColumnDescriptorPublication.finish(storage, result, detail);
  }

  public StatusCode finish(
      ColumnConstraintDescriptorSet constraints,
      ColumnDescriptorSet.Result result,
      StatusDetail detail) {
    return PackedColumnDescriptorPublication.finish(storage, constraints, result, detail);
  }

  public int count() {
    return storage.count();
  }

  public void reset() {
    storage.reset();
  }
}
