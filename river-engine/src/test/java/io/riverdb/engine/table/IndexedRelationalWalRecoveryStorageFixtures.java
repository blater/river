package io.riverdb.engine.table;

import static io.riverdb.engine.TestDatabaseResources.databasePlan;
import static io.riverdb.engine.TestDatabaseResources.databaseProviderLease;
import static io.riverdb.engine.TestDatabaseResources.runtimeRoot;
import static io.riverdb.tx.TransactionManager.DEFAULT_LOCK_WAIT_TIMEOUT_NANOS;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.concurrent.FatalStateFence;
import io.riverdb.base.error.StatusCode;
import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.base.tuple.TupleShape;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.EmbeddedDatabaseOpenResult;
import io.riverdb.engine.EmbeddedSessionOpenResult;
import io.riverdb.format.btree.TupleIndexRootRecord;
import io.riverdb.format.btree.TupleIndexRootRecordCodec;
import io.riverdb.format.btree.TupleBTreePageCodec;
import io.riverdb.format.btree.TupleKeyBuilder;
import io.riverdb.format.btree.TupleKeyCodec;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.format.page.PageCodec;
import io.riverdb.format.wal.WalRecordCodec;
import io.riverdb.platform.file.nio.NioDirectoryOpenResult;
import io.riverdb.platform.file.nio.NioDurableDirectory;
import io.riverdb.platform.file.nio.NioIoCounters;
import io.riverdb.storage.heap.HeapRowResult;
import io.riverdb.storage.btree.BTreeFreePage;
import io.riverdb.storage.btree.BTreeRootPage;
import io.riverdb.storage.btree.TupleBTree;
import io.riverdb.storage.btree.TupleBTreeInsertPreflightResult;
import io.riverdb.storage.btree.TupleBTreePageReference;
import io.riverdb.storage.btree.BTreeStructuralLimits;
import io.riverdb.storage.btree.TupleBTreeTreeWorkspace;
import io.riverdb.tx.TransactionManager;
import io.riverdb.tx.api.IsolationLevel;
import io.riverdb.tx.api.TransactionOutcome;
import io.riverdb.tx.api.TransactionState;
import io.riverdb.wal.local.LocalWal;
import io.riverdb.wal.local.LocalWalAppendResult;
import io.riverdb.wal.local.LocalWalForceTarget;
import io.riverdb.wal.local.LocalWalGroupAppendResult;
import io.riverdb.wal.local.LocalWalLogicalStream;
import io.riverdb.wal.local.LocalWalOpenResult;
import io.riverdb.wal.local.LocalWalReadResult;
import io.riverdb.wal.local.LocalWalRecordBatch;
import io.riverdb.wal.local.LocalWalReservation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static io.riverdb.engine.table.IndexedRelationalWalRecoveryFixtures.*;

/** Storage and encoding operations for relational WAL scenarios. */
final class IndexedRelationalWalRecoveryStorageFixtures {
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
    requireOk(store.fetchByKey(CatalogKeyspace.INDEX_ROOT_SPACE, keyId, row));
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
    requireOk(store.fetchByKey(CatalogKeyspace.INDEX_ROOT_SPACE, 1_000, row));
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

  static NioDurableDirectory openDirectory(Path root) {
    return openDirectory(root, new NioIoCounters());
  }

  static NioDurableDirectory openDirectory(Path root, NioIoCounters counters) {
    NioDirectoryOpenResult result = new NioDirectoryOpenResult();
    requireOk(NioDurableDirectory.openExisting(
        root, new FatalStateFence(), counters, 8, result));
    return result.directory();
  }

  static LocalWal openWal(NioDurableDirectory directory, boolean existing) {
    LocalWalOpenResult result = new LocalWalOpenResult();
    requireOk(LocalWal.open(directory, DATABASE, GENERATION, result));
    return result.wal();
  }

  static void crashWal(LocalWal wal) throws Exception {
    Field file = LocalWal.class.getDeclaredField("file");
    file.setAccessible(true);
    requireOk(((io.riverdb.platform.file.DurableFile) file.get(wal)).close());
  }

  static void appendBaseSuboperations(
      IndexedRelationalMutationBuffer mutations, int count) {
    requireOk(mutations.appendLogicalRowFloor(OWNER_OBJECT_ID, count + 1L));
    for (int index = 0; index < count; index++) {
      requireOk(mutations.appendSuboperation(
          OWNER_OBJECT_ID, -1, index, 1,
          0, 0, SCALAR_ROOT, SCALAR_ROOT,
          NEXT_PAGE, NEXT_PAGE, 0, 0, index, index + 1L,
          IndexedRelationalSuboperations.REGISTRY_ABSENT,
          IndexedRelationalSuboperations.REGISTRY_ABSENT, 0, 0));
    }
  }

  static long descriptorHash(int[] descriptors) {
    TupleShape.Result result = new TupleShape.Result();
    requireOk(TupleShape.create(descriptors, result));
    return result.value().descriptorHash();
  }

  static TupleShape shape(int[] descriptors) {
    TupleShape.Result result = new TupleShape.Result();
    requireOk(TupleShape.create(descriptors, result));
    return result.value();
  }

  static IndexedPageSet pageSet(IndexedTableStore store) throws Exception {
    Field field = IndexedTableStore.class.getDeclaredField("pages");
    field.setAccessible(true);
    return (IndexedPageSet) field.get(store);
  }

  static ByteBuffer physicalTuple(int[] descriptors, long logicalRowId, char value) {
    ByteBuffer result = ByteBuffer.allocate(3_080);
    TupleKeyBuilder builder = new TupleKeyBuilder();
    requireOk(builder.beginIndex(result, 0, descriptors.length));
    requireOk(builder.addText(descriptors[0], repeated(value, 255)));
    requireOk(builder.addText(descriptors[1], repeated(value, 255)));
    requireOk(builder.addText(descriptors[2], repeated(value, 250)));
    requireOk(builder.finishPhysical(logicalRowId));
    result.limit(builder.keyBytes());
    result.position(0);
    return result;
  }

  static String repeated(char value, int count) {
    char[] characters = new char[count];
    for (int index = 0; index < count; index++) characters[index] = value;
    return new String(characters);
  }

  static IndexedTransactionSession session(
      IndexedSessionContext context, int maximumRowBytes) {
    IndexedTransactionSessionOpenResult result =
        new IndexedTransactionSessionOpenResult();
    requireOk(context.openSession(maximumRowBytes, result));
    return result.session();
  }

  static void requireOk(StatusCode status) {
    if (!status.isOk()) throw new AssertionError("expected OK, got " + status);
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
