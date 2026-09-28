package io.riverdb.engine.table;

import io.riverdb.base.id.DatabaseIncarnation;
import io.riverdb.base.id.WalGeneration;
import io.riverdb.engine.runtime.DatabasePageCachePlan;
import io.riverdb.platform.file.DurableFile;

/** Composition root wiring the cache's independent frame policy modules. */
final class IndexedPageFrameCacheOperations {
  private final IndexedPageFrameIo io;
  private final IndexedPreparedPageBatch prepared;
  private final IndexedCurrentPageFrameStore current;
  private final IndexedStagingPageFrameStore staging;
  private final IndexedPageMutationStager mutationStager;
  private final IndexedPageVacuum vacuum;
  private final IndexedPageFramePinning pinning;
  private final IndexedPageFrameCodec codec;
  private final IndexedPageMetadataAccess metadata;
  private final IndexedPageDurabilityChains durability;
  private final IndexedPageFrameLifecycle lifecycle;
  private final IndexedPageValidationAccess validation;
  private final IndexedOperationPagePins operationPins;

  IndexedPageFrameCacheOperations(
      IndexedPageFrameCache owner, DurableFile backingFile, DurableFile stagingFile,
      DatabaseIncarnation database, WalGeneration generation,
      IndexedPageState state, DatabasePageCachePlan config) {
    io = new IndexedPageFrameIo(backingFile, stagingFile, database, generation, state);
    prepared = new IndexedPreparedPageBatch(config);
    current = new IndexedCurrentPageFrameStore(
        owner, state, io, config.currentFrames(), config.currentMapCapacity());
    staging = new IndexedStagingPageFrameStore(
        owner, state, io, current, prepared,
        config.stagingFrames(), config.stagingMapCapacity());
    mutationStager = new IndexedPageMutationStager(
        owner, state, io, current, staging, prepared);
    vacuum = new IndexedPageVacuum(owner, state, io, current, staging);
    pinning = new IndexedPageFramePinning(owner, state, current);
    codec = new IndexedPageFrameCodec(owner, state, io, current, staging);
    metadata = new IndexedPageMetadataAccess(owner, state, current, staging, prepared);
    durability = new IndexedPageDurabilityChains(owner, config.currentFrames());
    lifecycle = new IndexedPageFrameLifecycle(
        owner, prepared, current, staging, durability);
    validation = new IndexedPageValidationAccess(owner, current, staging, prepared);
    operationPins = new IndexedOperationPagePins(owner);
  }

  void syncFrameViews(IndexedPageFrameCache owner) {
    owner.currentFrames = current.frames();
    owner.stagingFrames = staging.frames();
    owner.currentMap = current.map();
    owner.stagingMap = staging.map();
  }

  IndexedPageFrameIo io() { return io; }
  IndexedPreparedPageBatch prepared() { return prepared; }
  IndexedCurrentPageFrameStore current() { return current; }
  IndexedStagingPageFrameStore staging() { return staging; }
  IndexedPageMutationStager mutationStager() { return mutationStager; }
  IndexedPageVacuum vacuum() { return vacuum; }
  IndexedPageFramePinning pinning() { return pinning; }
  IndexedPageFrameCodec codec() { return codec; }
  IndexedPageMetadataAccess metadata() { return metadata; }
  IndexedPageDurabilityChains durability() { return durability; }
  IndexedPageFrameLifecycle lifecycle() { return lifecycle; }
  IndexedPageValidationAccess validation() { return validation; }
  IndexedOperationPagePins operationPins() { return operationPins; }
}
