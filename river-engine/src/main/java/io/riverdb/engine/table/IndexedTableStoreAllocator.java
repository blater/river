package io.riverdb.engine.table;

import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.platform.file.DurableDirectory;
import io.riverdb.platform.file.DurableFile;
import io.riverdb.wal.local.LocalWal;

/** Allocation boundary used to fault-test unpublished indexed-store construction. */
@FunctionalInterface
interface IndexedTableStoreAllocator {
  IndexedTableStoreAllocator SYSTEM = IndexedTableStore::new;

  IndexedTableStore allocate(
      DurableDirectory directory,
      DurableFile pages,
      DurableFile rows,
      DurableFile versions,
      LocalWal wal,
      DatabaseIncarnation database,
      WalGeneration generation,
      IndexedPageCacheConfig pageCacheConfig);
}
