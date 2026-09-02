package io.riverdb.engine.schema.catalog;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.EmbeddedDatabase;
import io.riverdb.engine.schema.TableDescriptor;
import io.riverdb.engine.schema.cache.SchemaCache;
import io.riverdb.engine.schema.cache.SchemaPin;
import io.riverdb.engine.table.IndexedTransactionSession;
import io.riverdb.format.catalog.CatalogKeyspace;
import io.riverdb.tx.api.TransactionState;

/** Failure-atomic catalog-v2 table definition creation and pinned open lifecycle. */
public final class CatalogTableLifecycle {
  private final EmbeddedDatabase embedded;
  private final SchemaCache cache;
  private CatalogIdAllocator allocator;
  private CatalogAllocationLifecycle allocation;
  private CatalogTransactions transactions;
  private CatalogTableCreator creator;
  private CatalogTableSuccessor successor;
  private CatalogSuccessorLifecycle successorLifecycle;
  private CatalogTableDrop drop;
  private CatalogTableOpener opener;
  private CatalogHistoricalTableOpener historicalOpener;
  private CatalogStartupValidator startup;

  public CatalogTableLifecycle(EmbeddedDatabase database, SchemaCache schemaCache) {
    embedded = database;
    cache = schemaCache;
  }

  /** Creates the independent allocation authority in a new database. */
  public synchronized StatusCode initialize() {
    StatusCode status = ensureInitialized();
    return status.isOk() ? allocation.initialize() : status;
  }

  /** Validates the recovered allocation authority before sessions are admitted. */
  public synchronized StatusCode validate() {
    StatusCode status = ensureInitialized();
    if (status.isOk()) status = allocation.validate();
    return status.isOk() ? startup.validate() : status;
  }

  /**
   * Persists an admitted shape under newly reserved identities and returns its initial cache pin.
   * The provisional descriptor remains caller-owned and is never published.
   */
  public synchronized StatusCode create(
      TableDescriptor provisional, SchemaPin pin, StatusDetail detail) {
    StatusCode status = ensureInitialized();
    return status.isOk() ? creator.create(provisional, pin, detail) : status;
  }

  /** Builds privately and stages the READY publication in the caller's active transaction. */
  public synchronized StatusCode prepare(
      TableDescriptor provisional,
      IndexedTransactionSession publicationSession,
      CatalogPreparedTable prepared,
      StatusDetail detail) {
    StatusCode admitted = admitPublication(publicationSession, detail);
    if (!admitted.isOk()) return admitted;
    StatusCode status = ensureInitialized();
    return status.isOk()
        ? creator.prepare(provisional, publicationSession, prepared, detail) : status;
  }

  /**
   * Builds one private metadata successor and stages its authoritative head update.
   * An unpublished pin is an execution overlay, not a durable predecessor: the intent
   * keyspace admits only one private generation per object and outer transaction.
   */
  public synchronized StatusCode prepareSuccessor(
      SchemaPin current,
      TableDescriptor proposed,
      IndexedTransactionSession publicationSession,
      CatalogPreparedTable prepared,
      StatusDetail detail) {
    StatusCode status = ensureInitialized();
    return status.isOk()
        ? successorLifecycle.prepare(
            current, proposed, publicationSession, prepared, detail, true) : status;
  }

  /** Builds a private successor without staging READY or the authoritative head update. */
  public synchronized StatusCode prepareSuccessorBuild(
      SchemaPin current,
      TableDescriptor proposed,
      IndexedTransactionSession publicationSession,
      CatalogPreparedTable prepared,
      StatusDetail detail) {
    StatusCode status = ensureInitialized();
    return status.isOk()
        ? successorLifecycle.prepare(
            current, proposed, publicationSession, prepared, detail, false) : status;
  }

  /** Stages READY and the authoritative head update after private data backfill completes. */
  public synchronized StatusCode stagePreparedSuccessor(
      IndexedTransactionSession publicationSession,
      CatalogPreparedTable prepared,
      StatusDetail detail) {
    StatusCode status = ensureInitialized();
    return status.isOk()
        ? successorLifecycle.stage(publicationSession, prepared, detail) : status;
  }

  /** Completes cache publication or cancellation after the caller transaction terminates. */
  public synchronized StatusCode finish(
      CatalogPreparedTable prepared, TransactionState outcome) {
    StatusCode status = ensureInitialized();
    return status.isOk() ? creator.finish(prepared, outcome, null) : status;
  }

  /** Stages an authoritative tombstone in the caller's schema transaction. */
  public synchronized StatusCode prepareDrop(
      SchemaPin current,
      IndexedTransactionSession publicationSession,
      StatusDetail detail) {
    if (detail != null) detail.reset();
    StatusCode status = admitPublication(publicationSession, detail);
    if (status.isOk()) status = ensureInitialized();
    if (status.isOk()) status = drop.prepare(current, publicationSession);
    if (!status.isOk() && detail != null) detail.set(status);
    return status;
  }

  /** Reserves durable record identities in the caller transaction for related catalog data. */
  public synchronized StatusCode reserveRecords(
      IndexedTransactionSession publicationSession,
      int recordCount,
      CatalogRecordRange result) {
    StatusCode status = admitPublication(publicationSession, null);
    if (status.isOk()) status = ensureInitialized();
    return status.isOk()
        ? allocator.reserveRecords(publicationSession, recordCount, result) : status;
  }

  /** Resolves one authoritative object head and atomically acquires its descriptor pin. */
  public synchronized StatusCode open(long objectId, SchemaPin pin, StatusDetail detail) {
    StatusCode status = ensureInitialized();
    return status.isOk() ? opener.open(objectId, pin, detail) : status;
  }

  /** Resolves the newest durable generation carrying one historical physical row layout. */
  public synchronized StatusCode openRetained(
      long objectId, long rowLayoutId, SchemaPin pin, StatusDetail detail) {
    StatusCode status = ensureInitialized();
    return status.isOk()
        ? historicalOpener.open(objectId, rowLayoutId, 0, pin, detail) : status;
  }

  /** Resolves one exact durable historical catalog generation and physical row layout. */
  public synchronized StatusCode openHistorical(
      long objectId, long rowLayoutId, long catalogGeneration,
      SchemaPin pin, StatusDetail detail) {
    StatusCode status = ensureInitialized();
    return status.isOk()
        ? historicalOpener.open(
            objectId, rowLayoutId, catalogGeneration, pin, detail) : status;
  }

  /** Authoritative object-ID ceiling shared with relational physical namespace allocation. */
  public static long maximumObjectId() {
    return CatalogKeyspace.MAXIMUM_RELATIONAL_OBJECT_ID;
  }

  public synchronized StatusCode close() {
    return transactions == null ? StatusCode.OK : transactions.close();
  }

  private StatusCode admitPublication(
      IndexedTransactionSession session, StatusDetail detail) {
    return CatalogPublicationAdmission.validate(embedded, session, detail);
  }

  private StatusCode ensureInitialized() {
    if (transactions != null) return StatusCode.OK;
    try {
      allocator = new CatalogIdAllocator();
      CatalogDefinitionStore definitions = new CatalogDefinitionStore();
      CatalogDefinitionWriter writer = new CatalogDefinitionWriter();
      CatalogObjectHeadStore heads = new CatalogObjectHeadStore();
      CatalogIntentStore intents = new CatalogIntentStore();
      transactions = new CatalogTransactions(embedded);
      allocation = new CatalogAllocationLifecycle(transactions, allocator);
      CatalogBuildCleaner cleaner = new CatalogBuildCleaner(
          transactions, intents, definitions);
      creator = new CatalogTableCreator(
          cache, transactions, allocator, definitions, writer, heads, intents,
          cleaner);
      successor = new CatalogTableSuccessor(
          cache, transactions, allocator, definitions, writer, heads, intents,
          cleaner);
      successorLifecycle = new CatalogSuccessorLifecycle(embedded, cache, successor);
      drop = new CatalogTableDrop(cache, heads, definitions);
      opener = new CatalogTableOpener(cache, transactions, definitions);
      historicalOpener = new CatalogHistoricalTableOpener(cache, transactions, definitions);
      startup = new CatalogStartupValidator(
          transactions, cleaner, definitions);
      return StatusCode.OK;
    } catch (OutOfMemoryError error) {
      allocator = null;
      allocation = null;
      transactions = null;
      creator = null;
      successor = null;
      successorLifecycle = null;
      drop = null;
      opener = null;
      historicalOpener = null;
      startup = null;
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }
}
