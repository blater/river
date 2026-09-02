package io.riverdb.bench.tpcc;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;

/** Observable process memory/GC state; engine-private counters are not inferred. */
record TpccProcessObservation(
    long heapUsed,
    long heapCommitted,
    long heapMaximum,
    long peakPoolUsed,
    long gcCollections,
    long gcMillis) {
  static TpccProcessObservation capture() {
    Runtime runtime = Runtime.getRuntime();
    long peak = 0;
    for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
      if (pool.getPeakUsage() != null) peak = Math.max(peak, pool.getPeakUsage().getUsed());
    }
    long collections = 0;
    long millis = 0;
    for (GarbageCollectorMXBean collector : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (collector.getCollectionCount() >= 0) collections += collector.getCollectionCount();
      if (collector.getCollectionTime() >= 0) millis += collector.getCollectionTime();
    }
    return new TpccProcessObservation(runtime.totalMemory() - runtime.freeMemory(),
        runtime.totalMemory(), runtime.maxMemory(), peak, collections, millis);
  }
}
