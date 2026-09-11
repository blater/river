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

import static io.riverdb.engine.table.IndexedRelationalWalCheckpointFixtures.*;

/** Storage and encoding operations for relational WAL scenarios. */
final class IndexedRelationalWalCheckpointStorageFixtures {
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

  static IndexedPageSet pageSet(IndexedTableStore store) throws Exception {
    Field field = IndexedTableStore.class.getDeclaredField("pages");
    field.setAccessible(true);
    return (IndexedPageSet) field.get(store);
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

  static void requireOk(StatusCode status) {
    if (!status.isOk()) throw new AssertionError("expected OK, got " + status);
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
