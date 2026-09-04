## Core implementation techniques

To score highly in TPC-C, a database needs much more than deadlock detection. TPC-C is a high-concurrency, write-heavy OLTP workload with strict transactional correctness, durable commits, and several deliberately hot data items.

The workload is approximately:

| Transaction | Mix | Main pressure |
|---|---:|---|
| New-Order | 45% | Inserts, inventory updates, sequence generation |
| Payment | 43% | Updates to customer, warehouse, and district balances |
| Order-Status | 4% | Recent-order lookups |
| Delivery | 4% | Batch processing and status updates |
| Stock-Level | 4% | Aggregation and range scans |

## Core implementation techniques

### 1. Efficient concurrency control

The lock manager or MVCC implementation is critical.

Important techniques include:

- **Fine-grained row or key-range locking** rather than page/table locks.
- **Short lock hold times**, especially around index and sequence updates.
- **Efficient lock-table data structures**, often hash-based.
- **Lock compatibility checks with low contention**.
- **Fast lock acquisition and release paths**.
- **Intent locks** for hierarchical locking.
- **Predicate or key-range protection** where required by the isolation level.
- **Deadlock detection or prevention**.

Deadlock detection is necessary in a general-purpose engine, but avoiding deadlocks is usually better for throughput. Transactions should acquire locks in a consistent order wherever possible. If deadlocks still occur, detection must be fast and victim selection should minimize wasted work—for example, aborting a young or inexpensive transaction.

MVCC can help read-heavy operations such as Order-Status and Stock-Level, but it does not eliminate contention among the many concurrent updates in New-Order and Payment. It also requires efficient version cleanup and conflict handling.

### 2. Hot-row and hot-key management

TPC-C intentionally creates contention around a relatively small number of records:

- Warehouse and district rows
- District order-number counters
- Customer balances
- Stock quantities
- Recently created orders

A naïve implementation can serialize transactions on these records. High-performance systems therefore optimize:

- **Atomic counter allocation**
- **Per-partition or per-district synchronization**
- **Short critical sections**
- **Cache-line and latch separation**
- **Avoidance of a single global sequence lock**
- **Partition-local metadata**
- **Efficient handling of frequently updated rows**

For example, using one global mutex for all order-number allocation would create an unnecessary bottleneck. Synchronization should be localized as much as the transaction semantics permit.

### 3. WAL, logging, and group commit

TPC-C requires committed transactions to survive failures, so a fast but non-durable commit is not valid.

The write path generally needs:

- **Write-ahead logging**
- **Sequential log appends**
- **Efficient log buffers**
- **Log records that avoid unnecessary copying**
- **Parallel log flush support**
- **Group commit**
- **Asynchronous page flushing**
- **Fast commit acknowledgment after the required durability point**

Group commit is especially important: several transactions can share one durable log flush instead of each issuing an independent flush. The implementation must balance batching against commit latency, because excessive batching can violate response-time requirements.

### 4. Buffer-pool and cache efficiency

TPC-C is often limited by memory access and synchronization rather than raw arithmetic.

A strong buffer manager typically provides:

- A large, carefully sized buffer pool
- Low-contention page-table lookups
- Efficient replacement policies
- Separate treatment for hot and cold pages
- Prefetching for predictable scans
- Dirty-page flushing in the background
- Avoidance of buffer-pool thrashing
- Partitioned buffer metadata and latches

The database should keep frequently accessed warehouse, district, customer, stock, and index pages resident. However, simply allocating all memory to the buffer pool can be counterproductive if it leaves insufficient memory for the OS, log buffers, connections, or query execution.

### 5. Appropriate indexes and access paths

TPC-C is sensitive to index design. Typical useful indexes include indexes for:

- Customer lookup by warehouse, district, and customer identifier
- Customer lookup by last name
- Order lookup by customer
- Recent-order lookup
- Order-line lookup by order identifier
- Stock lookup by item and warehouse
- Delivery status scans

Indexes must be selective enough to avoid unnecessary scans but inexpensive enough to maintain during the write-heavy New-Order workload. Over-indexing can reduce TPC-C performance because every insert or update must maintain additional index entries.

The engine also needs fast support for:

- Point lookups
- Ordered lookups
- Short range scans
- Insert-heavy B-tree operations
- Concurrent page splits
- Index latch management

### 6. Transaction execution and plan specialization

TPC-C has a small, known set of transaction types. A general SQL optimizer can spend more time planning than necessary if every statement is optimized repeatedly.

High-performance implementations commonly use:

- Prepared statements
- Plan caching
- Parameterized execution
- Minimal parse overhead
- Specialized execution paths for common operations
- Efficient stored-procedure execution
- Reduced object allocation
- Batched index and row operations
- Direct execution of simple predicates

The implementation must still execute the required logical operations correctly. Replacing general SQL execution with specialized code is useful only if the benchmark rules allow it and the behavior remains equivalent.

### 7. Efficient insert and update paths

New-Order creates order and order-line records while updating stock. That stresses:

- Heap or clustered-table insertion
- Multiple secondary indexes
- Page allocation
- B-tree splits
- Transaction-local undo/redo state
- Row version creation
- Constraint checking
- Foreign-key or referential-integrity mechanisms, where applicable

High scores require efficient append and update paths, good page fill behavior, and minimal index-maintenance contention. Poor page layout can cause excessive random I/O and page splits.

### 8. Resource management

Resource management matters, particularly on many-core machines.

Useful techniques include:

- Connection pooling
- Worker-thread pools
- Efficient request queues
- CPU affinity or NUMA-aware scheduling
- Partitioned lock managers
- Per-core data structures
- Avoiding excessive context switching
- Admission control
- Memory quotas
- Separate resources for logging and query execution
- Backpressure when queues become overloaded

More client connections do not necessarily mean more throughput. Beyond a certain point, they increase lock contention, cache misses, scheduling overhead, and memory pressure. The optimal concurrency level usually needs to be tuned experimentally.

### 9. Partitioning and locality

Partitioning can improve scalability when it preserves transaction locality.

TPC-C naturally has warehouse and district dimensions, so useful techniques include:

- Partitioning tables by warehouse
- Keeping related rows physically close
- Partition-local indexes
- Routing requests to the partition owning the relevant warehouse
- Avoiding cross-partition transactions where possible
- NUMA-aware placement

However, partitioning does not automatically solve contention. Some transactions touch multiple warehouses, and poor partitioning can introduce distributed transactions or uneven load.

### 10. Delivery and batch-processing optimization

Delivery differs from the other transactions because it performs work across multiple districts and orders. It benefits from:

- Efficient “oldest undelivered order” lookup
- Status indexes
- Batch row processing
- Set-oriented updates where valid
- Reduced repeated scans
- Careful lock ordering
- Avoidance of long-running locks

Delivery must not monopolize resources or create lock conflicts that harm the much more frequent New-Order and Payment transactions.

### 11. Recovery and checkpointing

Recovery mechanisms must be strong but should not interfere excessively with foreground transactions.

Important details include:

- Efficient checkpoints
- Incremental or fuzzy checkpointing
- Background dirty-page writes
- Low-impact log truncation
- Parallel recovery structures
- Avoiding long stop-the-world pauses
- Correct rollback of aborted transactions

A checkpoint that blocks all transaction processing can create latency spikes and reduce the measured throughput.


# What usually separates a merely correct system from a high-scoring one?

The biggest differences are generally:

1. **Low-contention lock and latch implementation**
2. **Fast durable commit using group commit**
3. **Efficient handling of hot rows and counters**
4. **Good buffer-pool and index locality**
5. **Low-overhead transaction execution**
6. **Careful concurrency tuning**
7. **Minimal interference from checkpoints, vacuuming, or background maintenance**
8. **NUMA-aware parallelism**

Deadlock detection is therefore important, but it is usually a defensive mechanism. The highest-performing systems try to prevent most deadlocks through lock ordering, partitioning, short transactions, and localized synchronization.

Finally, TPC-C results also depend heavily on the surrounding system: storage latency, log-device performance, memory capacity, CPU topology, filesystem or direct-I/O behavior, client-driver efficiency, and database configuration. The database engine must remain ACID-compliant; disabling durability, weakening isolation improperly, or omitting required transaction behavior may improve numbers but would not constitute a valid TPC-C result.


For a small system (e.g. **12-core laptop with 8–16 GB of RAM**), the priorities change. Large-scale NUMA and distributed partitioning are less important than reducing transaction overhead, write latency, and contention on a few hot records.

Assuming a single database instance and a local SSD, I would rank the features like this:

| Rank | Feature | Priority on this system | What to optimize |
|---:|---|---|---|
| 1 | Durable WAL and group commit | Very high | Sequential log writes, batching, log-buffer sizing, commit latency |
| 2 | Fine-grained concurrency control | Very high | Row/key locks, short lock duration, low-contention lock tables |
| 3 | Hot-row and counter handling | Very high | District order counters, stock rows, customer and balance updates |
| 4 | Buffer-pool and memory management | Very high | Keep the working set cached without starving logs and execution |
| 5 | Correct, minimal indexing | High | Fast lookups with minimal insert/update maintenance |
| 6 | Prepared execution and plan caching | High | Avoid repeated parsing, planning, allocation, and catalog lookups |
| 7 | Efficient page and row layout | High | Compact rows, good clustering, low fragmentation, efficient inserts |
| 8 | Thread and connection management | High | Avoid oversubscription, use bounded workers and efficient queues |
| 9 | Checkpoint and background-I/O control | Medium-high | Prevent stalls from checkpoints, flushing, and maintenance |
| 10 | Limited locality and partitioning | Medium | Partition internal structures and hot metadata; full NUMA design is usually secondary |

## 1. WAL and group commit

On a small or mid-sized system, commit latency can dominate. The engine should:

- Append log records sequentially.
- Keep log records in memory until a flush is needed.
- Allow multiple transactions to join one flush.
- Separate log writing from data-page flushing.
- Use a dedicated log device or at least a well-provisioned SSD.
- Avoid forcing data pages to disk at every commit.

The key path should look approximately like:

```text
modify rows
  → append WAL records
  → flush WAL to the durability point
  → acknowledge commit
  → flush dirty data pages later
```

Do not make every transaction perform an independent synchronous data-page write. That turns random data I/O into the bottleneck.

For this machine size, the best log implementation may matter more than sophisticated query parallelism.

## 2. Fine-grained locking or MVCC

Use row-level or key-level concurrency control. Page-level locks can cause unrelated transactions to block each other.

The lock manager should have:

- Compact lock entries
- Hash-partitioned lock tables
- Fast lock acquisition
- Short wait queues
- Efficient deadlock detection
- Consistent lock ordering
- Cheap rollback of aborted transactions

On a 12-core system, a single global lock-manager mutex can already become a significant bottleneck. It is usually worth partitioning the lock table into several independent buckets or domains.

MVCC may help with Order-Status and Stock-Level reads, but write-write conflicts remain important. MVCC also introduces version cleanup, undo storage, and visibility-check overhead, so it is not automatically superior for this workload.

## 3. Hot-row handling

This is likely one of the most important areas for TPC-C.

Particularly sensitive objects include:

- District order-number counters
- Stock records
- Customer balances
- Warehouse and district balances
- Recently inserted orders
- Frequently modified index pages

Avoid designs such as:

```text
one global mutex
  → allocate every order number
```

Prefer localized synchronization, such as a separate counter or lock domain per warehouse/district where the semantics allow it.

Also minimize the time spent holding hot-row locks:

1. Read the required values.
2. Compute changes outside the critical section where possible.
3. Acquire locks in a consistent order.
4. Perform the minimal update.
5. Release promptly.

The exact transaction semantics must be preserved; the objective is to remove unnecessary work, not to eliminate required synchronization.

## 4. Buffer-pool sizing and memory management

With small systems (8–16 GB of RAM), memory allocation needs to be deliberate. Reserve memory for:

- The buffer pool
- WAL buffers
- Connection and worker state
- Sort and execution memory
- Lock tables
- Operating-system cache, if used
- Background processes

Do not simply assign nearly all RAM to the buffer pool. A reasonable starting point might be:

- **8 GB system:** roughly 3–5 GB for database caching
- **16 GB system:** roughly 5–10 GB for database caching

This should be user configurable and appropriate for the context - the assignment should be lower if the DB is embedded in an application or if it is on a consumer machine which will also run user-applications rather than a dedicated server for instance.
It should be shown on server startup, unless started in "quiet" mode.

The exact amount depends on whether the database uses direct I/O, how much memory the operating system needs, and how large the benchmark database is.

The buffer manager should retain:

- Warehouse and district rows
- Stock rows
- Customer pages
- Recent order pages
- Frequently used index pages

It should also avoid allowing a large Stock-Level scan to evict the hot pages needed by New-Order and Payment.

## 5. Minimal, well-designed indexes

For this machine, indexes should be designed around the actual TPC-C access paths rather than added indiscriminately.

Useful indexes generally cover:

- Customer by warehouse, district, and customer ID
- Customer by last name
- Orders by customer
- Order lines by order ID
- Stock by item and warehouse
- Undelivered orders by district/status

Every additional index increases the cost of inserts and updates. Since New-Order is write-heavy, an index that saves a small lookup but adds substantial maintenance cost may reduce total throughput.

The important measurements are:

- Lookup latency
- Index-page contention
- Insert cost
- Page-split frequency
- Buffer-pool residency

## 6. Prepared plans and low-overhead execution

On a small system, CPU overhead from the SQL front end can be visible because the transaction shapes are repetitive.

Use:

- Prepared statements
- Cached execution plans
- Parameterized queries
- Reused expression and tuple objects
- Reduced parser and planner activity
- Efficient stored-procedure or RPC paths
- Low-overhead client/database communication

Avoid repeatedly performing catalog lookups, allocating temporary objects, or recompiling the same statements.

For TPC-C, a small optimized execution engine can matter more than a sophisticated general-purpose optimizer.

## 7. Physical row and page design

The working set should be compact enough to remain memory-resident where possible.

Important details include:

- Compact row headers
- Appropriate data types
- Good clustering for related records
- Efficient append paths for orders and order lines
- Reasonable page fill factors
- Low fragmentation
- Efficient handling of B-tree page splits
- Avoidance of unnecessary row movement

For example, placing frequently accessed columns and metadata efficiently can reduce cache and memory bandwidth usage. But excessive clustering or aggressive fill factors can increase page splits and update costs.

## 8. Thread and connection management

Twelve cores do not imply that dozens or hundreds of active database workers will improve throughput.

Use:

- A bounded worker pool
- Connection pooling
- Efficient request queues
- Limited active concurrency
- Low-overhead context switching
- Separate handling for foreground transactions and background tasks

Excessive concurrency causes:

- More lock waits
- More cache misses
- More scheduler activity
- Larger memory usage
- Longer queues
- Higher tail latency

A useful tuning approach is to test several concurrency levels—for example, 8, 12, 16, 24, and 32 active workers—and measure both throughput and response-time percentiles.

## 9. Checkpointing and background I/O

On a system with limited RAM and storage bandwidth, background flushing can interfere with foreground transactions.

Prefer:

- Incremental or fuzzy checkpoints
- Smooth dirty-page flushing
- Write-rate throttling
- Separate log and data I/O where possible
- No long global pauses
- Deferred maintenance outside the measured run

The engine should avoid a pattern like:

```text
run quickly
  → accumulate many dirty pages
  → perform a huge blocking checkpoint
  → stall all transactions
```

A steady background write rate is usually better.

## 10. Lightweight locality and partitioning

For a small (8-16 core system), full NUMA-aware architecture is usually not the first priority, especially on a single-socket machine. Still, modest partitioning helps:

- Partition lock-table buckets.
- Partition buffer-manager metadata.
- Use per-worker or per-core statistics.
- Use separate request queues.
- Avoid a single global allocator or sequence mutex.
- Keep frequently updated counters separate to prevent false sharing.

If the cpu cores are split across two CPU sockets, NUMA locality becomes more relevant. Then thread affinity, local memory allocation, and per-socket structures should move higher in the priority list.

## Recommended optimization order

For this system size, I would implement and measure in this order:

1. Verify the WAL and durable-commit path.
2. Measure lock waits, deadlocks, and latch contention.
3. Identify hot rows and hot index pages.
4. Ensure the working set fits efficiently in memory.
5. Check query plans and remove unnecessary indexes.
6. Add prepared-plan and execution-path caching.
7. Tune worker concurrency.
8. Smooth checkpoint and background I/O behavior.
9. Optimize page layout and insertion paths.
10. Add NUMA or deeper partitioning only if profiling shows a locality bottleneck.

The biggest likely wins are **group commit**, **hotspot reduction**, **lock-manager scalability**, and **buffer-pool efficiency**. On a 12-core, 16 GB system, these will generally matter more than elaborate sharding or advanced NUMA scheduling.

