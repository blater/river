package io.riverdb.engine.relational;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.schema.KeyDescriptor;
import io.riverdb.engine.schema.TableDescriptor;

/** Allocation-free O(K log K) physical-key ordering by durable key identity. */
final class RelationalDescriptorTupleDeltaKeyOrder {
  StatusCode collectAndSort(
      TableDescriptor table, RelationalDescriptorTupleDeltaStorage storage, int count) {
    for (int index = 0; index < count; index++) {
      storage.keyAt(index, RelationalDescriptorKeySet.at(table, index));
    }
    storage.keyCount(count);
    for (int root = count / 2 - 1; root >= 0; root--) sift(storage, root, count);
    for (int end = count - 1; end > 0; end--) {
      swap(storage, 0, end);
      sift(storage, 0, end);
    }
    long previous = 0;
    for (int index = 0; index < count; index++) {
      long keyId = storage.keyAt(index).keyId();
      if (keyId <= previous) return StatusCode.CORRUPTION;
      previous = keyId;
    }
    return StatusCode.OK;
  }

  private static void sift(
      RelationalDescriptorTupleDeltaStorage storage, int root, int end) {
    while (root * 2 + 1 < end) {
      int child = root * 2 + 1;
      if (child + 1 < end
          && storage.keyAt(child).keyId() < storage.keyAt(child + 1).keyId()) child++;
      if (storage.keyAt(root).keyId() >= storage.keyAt(child).keyId()) return;
      swap(storage, root, child);
      root = child;
    }
  }

  private static void swap(
      RelationalDescriptorTupleDeltaStorage storage, int left, int right) {
    KeyDescriptor value = storage.keyAt(left);
    storage.keyAt(left, storage.keyAt(right));
    storage.keyAt(right, value);
  }
}
