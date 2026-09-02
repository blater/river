package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import java.nio.ByteBuffer;

/** Reuses one key buffer while applying and encoding one descriptor's tuple deltas. */
final class IndexedTupleDeltaCompiler {
  private final IndexedRelationalTupleSession tuples;
  private final ByteBuffer key = ByteBuffer.allocate(
      io.riverdb.format.btree.TupleKeyCodec.MAX_PHYSICAL_INDEX_KEY_BYTES);

  IndexedTupleDeltaCompiler(IndexedPageSet pages) {
    tuples = new IndexedRelationalTupleSession(pages);
  }

  StatusCode apply(
      IndexedTupleIntentJournal intents, int descriptor, int rootPageId) {
    StatusCode status = tuples.configure(
        intents.keyIdAt(descriptor), intents.schemaIdAt(descriptor),
        rootPageId, intents.shapeAt(descriptor));
    if (status.isOk()) status = applyOperation(intents, descriptor,
        IndexedRelationalMutation.TUPLE_DELETE);
    return status.isOk() ? applyOperation(intents, descriptor,
        IndexedRelationalMutation.TUPLE_INSERT) : status;
  }

  StatusCode append(
      IndexedTupleIntentJournal intents, int descriptor,
      IndexedRelationalMutation mutation, int suboperation, int outputDescriptor) {
    StatusCode status = append(intents, descriptor,
        IndexedRelationalMutation.TUPLE_DELETE,
        mutation, suboperation, outputDescriptor);
    return status.isOk() ? append(intents, descriptor,
        IndexedRelationalMutation.TUPLE_INSERT,
        mutation, suboperation, outputDescriptor) : status;
  }

  int rootPageId() { return tuples.rootPageId(); }

  int count(IndexedTupleIntentJournal intents, int descriptor) {
    int count = 0;
    for (int index = 0; index < intents.mutationCount(); index++) {
      int operation = intents.operationAt(index);
      if (intents.activeAt(index) && intents.descriptorAt(index) == descriptor
          && (operation == IndexedRelationalMutation.TUPLE_INSERT
              || operation == IndexedRelationalMutation.TUPLE_DELETE)) {
        count++;
      }
    }
    return count;
  }

  private StatusCode applyOperation(
      IndexedTupleIntentJournal intents, int descriptor, int operation) {
    for (int index = 0; index < intents.mutationCount(); index++) {
      if (!matches(intents, index, descriptor, operation)) continue;
      load(intents, index);
      StatusCode status = operation == IndexedRelationalMutation.TUPLE_DELETE
          ? tuples.delete(key) : tuples.insert(key);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private StatusCode append(
      IndexedTupleIntentJournal intents, int descriptor, int operation,
      IndexedRelationalMutation mutation, int suboperation, int outputDescriptor) {
    for (int index = 0; index < intents.mutationCount(); index++) {
      if (!matches(intents, index, descriptor, operation)) continue;
      load(intents, index);
      StatusCode status = mutation.appendTuple(
          suboperation, intents.ownerAt(descriptor), operation, outputDescriptor,
          intents.logicalRowIdAt(index), key, 0, key.remaining());
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private void load(IndexedTupleIntentJournal intents, int mutation) {
    key.clear();
    key.limit(intents.payloadLengthAt(mutation));
    intents.copyPayloadTo(mutation, key, 0);
    key.position(0);
  }

  private static boolean matches(
      IndexedTupleIntentJournal intents, int index, int descriptor, int operation) {
    return intents.descriptorAt(index) == descriptor
        && intents.activeAt(index)
        && intents.operationAt(index) == operation;
  }
}
