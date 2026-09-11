package io.riverdb.engine.table;


import static io.riverdb.engine.table.IndexedRelationalWalMutationFixtures.*;
import static io.riverdb.engine.table.IndexedRelationalWalStorageFixtures.*;
import io.riverdb.base.error.StatusCode;
import io.riverdb.format.btree.TupleIndexRootRecord;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.PageCodec;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.storage.btree.BTreeFreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.btree.TupleBTreePageReference;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.zip.CRC32C;



/** Registry validation and checkpoint assertions for relational WAL tests. */
final class IndexedRelationalWalRegistryFixtures {
  static TupleIndexRootRecord registryRecord(
      IndexedTableStore store, long keyId) {
    HeapRowResult row = new HeapRowResult();
    requireOk(store.kernel.fetchByKeyAt(
        store.lastCommitSequence, CatalogKeyspace.INDEX_ROOT_SPACE, keyId, row));
    ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
    requireOk(row.copyTo(bytes));
    bytes.flip();
    TupleIndexRootRecord record = new TupleIndexRootRecord();
    requireOk(TupleIndexRootRecordCodec.decode(bytes, 0, record, new CRC32C()));
    return record;
  }

  static void assertReadyRegistry(
      IndexedTableStore store, long keyId, long owner, int rootPageId,
      long generation) {
    TupleIndexRootRecord record = registryRecord(store, keyId);
    check(record.state() == TupleIndexRootRecordCodec.STATE_READY
        && record.rootPageId() == rootPageId && record.ownerObjectId() == owner
        && record.schemaId() == KEY_SCHEMA_ID && record.generation() == generation
        && record.privateOwner() == 0 && record.cleanupCursor() == 0,
        "batched READY registry mismatch");
  }

  static void assertAbsentRegistry(
      IndexedTableStore store, long keyId, long owner, long generation) {
    TupleIndexRootRecord record = registryRecord(store, keyId);
    check(record.state() == TupleIndexRootRecordCodec.STATE_ABSENT
        && record.rootPageId() == 0 && record.ownerObjectId() == owner
        && record.schemaId() == KEY_SCHEMA_ID && record.generation() == generation
        && record.privateOwner() == 0 && record.cleanupCursor() == 0,
        "batched ABSENT registry mismatch");
  }

  static void assertRecoveredRegistry(IndexedTableStore store) {
    assertRecoveredRegistry(store, 1_000, 4, OWNER_OBJECT_ID);
  }

  static void assertRecoveredRegistry(
      IndexedTableStore store, long keyId, int rootPageId, long ownerObjectId) {
    assertRecoveredRegistry(store, keyId, rootPageId, ownerObjectId, 3);
  }

  static void assertRecoveredRegistry(
      IndexedTableStore store, long keyId, int rootPageId,
      long ownerObjectId, long generation) {
    assertRecoveredRegistry(
        store, keyId, rootPageId, ownerObjectId, generation, keyId);
  }

  static void assertRecoveredRegistry(
      IndexedTableStore store, long keyId, int rootPageId,
      long ownerObjectId, long generation, long schemaId) {
    HeapRowResult row = new HeapRowResult();
    requireOk(store.kernel.fetchByKeyAt(
        store.lastCommitSequence, CatalogKeyspace.INDEX_ROOT_SPACE, keyId, row));
    ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
    requireOk(row.copyTo(bytes));
    bytes.flip();
    TupleIndexRootRecord record = new TupleIndexRootRecord();
    requireOk(TupleIndexRootRecordCodec.decode(bytes, 0, record, new CRC32C()));
    check(record.state() == TupleIndexRootRecordCodec.STATE_READY
        && record.rootPageId() == rootPageId && record.generation() == generation
        && record.ownerObjectId() == ownerObjectId
        && record.schemaId() == schemaId
        && record.privateOwner() == 0, "registry replay mismatch");
  }

  static void assertRecoveredRegistryState(
      IndexedTableStore store, int state, int rootPageId,
      long generation, long privateOwner) {
    int cleanupCursor = state == TupleIndexRootRecordCodec.STATE_DROPPING
        && rootPageId == 0 ? BTreeRootPage.FIRST_REUSABLE_PAGE_ID : 0;
    assertRecoveredRegistryState(
        store, state, rootPageId, generation, privateOwner, cleanupCursor);
  }

  static void assertRecoveredRegistryState(
      IndexedTableStore store, int state, int rootPageId,
      long generation, long privateOwner, int cleanupCursor) {
    HeapRowResult row = new HeapRowResult();
    requireOk(store.kernel.fetchByKeyAt(
        store.lastCommitSequence, CatalogKeyspace.INDEX_ROOT_SPACE, 1_000, row));
    ByteBuffer bytes = ByteBuffer.allocate(TupleIndexRootRecordCodec.BYTES);
    requireOk(row.copyTo(bytes));
    bytes.flip();
    TupleIndexRootRecord record = new TupleIndexRootRecord();
    requireOk(TupleIndexRootRecordCodec.decode(bytes, 0, record, new CRC32C()));
    check(record.state() == state && record.rootPageId() == rootPageId
        && record.generation() == generation && record.privateOwner() == privateOwner
        && record.cleanupCursor() == cleanupCursor,
        "registry lifecycle replay mismatch");
  }

  static void assertRecoveredFreeChain(IndexedPageSet pages, int count) {
    ByteBuffer metadata = pages.currentPayloadUnchecked(IndexedTableKernel.ROOT_META_PAGE_ID);
    check(BTreeRootPage.freePageCount(metadata) == count,
        "WAL-only cleanup recovered the wrong free-page count");
    int pageId = BTreeRootPage.freePageHead(metadata);
    for (int expected = BTreeRootPage.FIRST_REUSABLE_PAGE_ID + count - 1;
        expected >= BTreeRootPage.FIRST_REUSABLE_PAGE_ID; expected--) {
      check(pageId == expected && pages.payloadKind(pageId) == PageCodec.PAYLOAD_KIND_FREE
              && pages.ownerKeyId(pageId) == 0,
          "WAL-only cleanup recovered a wrong free-page identity");
      pageId = BTreeFreePage.nextPageId(pages.currentPayloadUnchecked(pageId));
    }
    check(pageId == 0, "WAL-only cleanup recovered a trailing free-page link");
  }

  static void checkDuplicateReachability(IndexedPageSet pages, int nextPageId) {
    IndexedTupleValidationProvider provider = new IndexedTupleValidationProvider(pages);
    TupleBTreePageReference reference = new TupleBTreePageReference();
    requireOk(provider.configure(4, 1_000, nextPageId));
    requireOk(provider.visit(4));
    requireOk(provider.pin(4, false, reference));
    requireOk(provider.release(reference));
    reference.reset();
    requireOk(provider.configure(4, 1_000, nextPageId));
    check(provider.visit(4) == StatusCode.CORRUPTION,
        "tuple page reached twice across graphs");
  }

  static void checkValidationAllocationFailures(
      IndexedTableStore store, IndexedPageSet pages) throws Exception {
    FailingPagedAllocator scalarFailure = new FailingPagedAllocator();
    replaceValidator(
        store,
        pages,
        new PagedBooleanArray(IndexedTableLimits.MAX_PAGES, scalarFailure),
        new PagedBooleanArray(IndexedTableLimits.MAX_PAGES));
    check(store.flush() == StatusCode.RESOURCE_EXHAUSTED,
        "scalar visitation allocation failure escaped flush");
    scalarFailure.allowAllocations();
    requireOk(store.flush());

    FailingPagedAllocator tupleFailure = new FailingPagedAllocator();
    replaceValidator(
        store,
        pages,
        new PagedBooleanArray(IndexedTableLimits.MAX_PAGES),
        new PagedBooleanArray(IndexedTableLimits.MAX_PAGES, tupleFailure));
    check(store.flush() == StatusCode.RESOURCE_EXHAUSTED,
        "tuple visitation allocation failure escaped flush");
    tupleFailure.allowAllocations();
    requireOk(store.flush());
  }

  static void replaceValidator(
      IndexedTableStore store,
      IndexedPageSet pages,
      PagedBooleanArray scalarVisited,
      PagedBooleanArray tupleVisited) throws Exception {
    Field kernelField = IndexedTableStore.class.getDeclaredField("kernel");
    kernelField.setAccessible(true);
    IndexedTableKernel kernel = (IndexedTableKernel) kernelField.get(store);
    Field componentsField = IndexedTableKernel.class.getDeclaredField("components");
    componentsField.setAccessible(true);
    IndexedKernelComponents components =
        (IndexedKernelComponents) componentsField.get(kernel);
    Field validatorField = IndexedKernelComponents.class.getDeclaredField("validator");
    validatorField.setAccessible(true);
    validatorField.set(
        components,
        new IndexedTableValidator(
            pages, kernel.versionState(), scalarVisited, tupleVisited));
  }

static final class FailingPagedAllocator implements IndexedPagedArrayAllocator {
    private boolean failing = true;
    private void allowAllocations() { failing = false; }
    @Override public byte[] allocateBytes(int size) {
      if (failing) throw new OutOfMemoryError("injected");
      return new byte[size];
    }
    @Override public int[] allocateInts(int size) { return new int[size]; }
    @Override public long[] allocateLongs(int size) { return new long[size]; }
  }
}
