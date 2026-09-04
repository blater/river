package io.riverdb.engine.table;

import io.riverdb.base.error.StatusCode;
import io.riverdb.engine.runtime.DatabaseResourceGovernor;
import io.riverdb.tx.TransactionManager;

/** One authenticated database ownership boundary for every indexed transaction session. */
public final class IndexedSessionContext {
  private final TransactionManager manager;
  private final IndexedTable table;
  private final IndexedGroupCommitCoordinator groupCommit;
  private final IndexedVacuum vacuum;
  private final DatabaseResourceGovernor governor;
  private final IndexedSessionRegistry registry;
  private final int maximumWriteEntries;

  private IndexedSessionContext(
      TransactionManager transactionManager,
      IndexedTable indexedTable,
      IndexedGroupCommitCoordinator groupCommitCoordinator,
      IndexedVacuum indexedVacuum,
      DatabaseResourceGovernor resourceGovernor,
      IndexedSessionRegistry sessionRegistry) {
    manager = transactionManager;
    table = indexedTable;
    groupCommit = groupCommitCoordinator;
    vacuum = indexedVacuum;
    governor = resourceGovernor;
    registry = sessionRegistry;
    maximumWriteEntries = (int) governor.plan().maximumDeliveryWriteEntries();
  }

  public static StatusCode bind(
      TransactionManager manager,
      IndexedTable table,
      IndexedGroupCommitCoordinator groupCommit,
      IndexedVacuum vacuum,
      Result result) {
    if (result == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    result.reset();
    if (manager == null || table == null || vacuum == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    DatabaseResourceGovernor governor = table.resourceGovernor();
    if (governor == null
        || !table.matches(manager)
        || manager.maximumActiveTransactions() > governor.plan().maximumOwners()
        || !vacuum.matches(manager, table)
        || groupCommit != null && !groupCommit.matches(manager, table)) {
      return StatusCode.NOT_OWNER;
    }
    try {
      IndexedSessionRegistry registry =
          new IndexedSessionRegistry(governor.plan().maximumOwners());
      result.set(new IndexedSessionContext(
          manager, table, groupCommit, vacuum, governor, registry));
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  public StatusCode openSession(
      int maximumRowBytes, IndexedTransactionSessionOpenResult result) {
    if (maximumRowBytes <= 0 || result == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    result.reset();
    IndexedTransactionSession session;
    try {
      session = new IndexedTransactionSession(this, maximumRowBytes);
    } catch (OutOfMemoryError failure) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = registry.register(session);
    if (status.isOk()) result.set(session);
    return status;
  }

  public TransactionManager manager() { return manager; }
  public IndexedTable table() { return table; }
  public IndexedGroupCommitCoordinator groupCommit() { return groupCommit; }
  public IndexedVacuum vacuum() { return vacuum; }
  public DatabaseResourceGovernor governor() { return governor; }
  public IndexedSessionRegistry registry() { return registry; }
  int maximumWriteEntries() { return maximumWriteEntries; }

  public static final class Result {
    private IndexedSessionContext context;
    public void reset() { context = null; }
    void set(IndexedSessionContext value) { context = value; }
    public IndexedSessionContext context() { return context; }
  }
}
