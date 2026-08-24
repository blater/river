package io.riverdb.bench.prototype;

import io.riverdb.base.error.StatusCode;

/** Measures version storage, page protection, and temporary page I/O prototypes. */
final class PrototypeStorageMeasurements {
  private PrototypeStorageMeasurements() { }

  static PrototypeSmoke.Measurement versions(
      PrototypeSmoke.AllocationMeter allocation,
      int hotIterations) {
    var store = new FixedVersionStore(8_192);
    for (int record = 0; record < 8_192; record++) {
      long begin = record & 511L;
      long end = (record & 7) == 0 ? 0L : begin + 96L;
      store.append(record, begin, end, record * 13L, 0L);
    }
    for (int iteration = 0; iteration < 1_000; iteration++) {
      store.scanVisible(iteration & 511L);
      store.sumVisibleValues();
    }
    long[] samples = new long[hotIterations];
    long allocationBefore = allocation.currentThreadBytes();
    long started = System.nanoTime();
    long sink = 0L;
    for (int iteration = 0; iteration < hotIterations; iteration++) {
      long operationStarted = System.nanoTime();
      store.scanVisible(iteration & 511L);
      sink ^= store.sumVisibleValues();
      samples[iteration] = System.nanoTime() - operationStarted;
    }
    PrototypeSmoke.Measurement measurement = PrototypeSmoke.summarize(
        "fixed_version_scan", StatusCode.OK, samples, System.nanoTime() - started,
        allocation.delta(allocationBefore));
    measurement.operations = (long) hotIterations * store.size();
    measurement.bytes = store.encodedBytes();
    measurement.copiedBytes = store.copiedBytes();
    measurement.checkValue = sink;
    return measurement;
  }

  static PrototypeSmoke.Measurement appendRead(
      PrototypeSmoke.AllocationMeter allocation,
      int hotIterations) {
    var store = new FixedVersionStore(1_024);
    var record = new VersionRecord();
    for (int iteration = 0; iteration < 1_024; iteration++) {
      store.append(iteration, iteration, 0L, iteration * 13L, 0L);
      store.read(iteration, record);
    }
    store.clear();
    long[] samples = new long[hotIterations];
    long allocationBefore = allocation.currentThreadBytes();
    long started = System.nanoTime();
    long sink = 0L;
    StatusCode status = StatusCode.OK;
    for (int iteration = 0; iteration < hotIterations; iteration++) {
      if (store.size() == 1_024) store.clear();
      long operationStarted = System.nanoTime();
      status = store.append(iteration, iteration, 0L, iteration * 13L, 0L);
      if (status.isOk()) status = store.read(store.size() - 1, record);
      samples[iteration] = System.nanoTime() - operationStarted;
      sink ^= record.value();
      if (!status.isOk()) break;
    }
    PrototypeSmoke.Measurement measurement = PrototypeSmoke.summarize(
        "fixed_version_append_read", status, samples, System.nanoTime() - started,
        allocation.delta(allocationBefore));
    measurement.operations = hotIterations;
    measurement.bytes = (long) hotIterations * FixedVersionStore.RECORD_BYTES;
    measurement.copiedBytes = store.copiedBytes();
    measurement.checkValue = sink;
    return measurement;
  }

  static PrototypeSmoke.Measurement protection(
      PrototypeSmoke.AllocationMeter allocation) {
    var model = new PageProtectionModel(4_096, 16 * 1_024, 128, 64);
    var result = new PageProtectionResult();
    for (int iteration = 0; iteration < 50; iteration++) {
      model.firstPageImageEpochs(4, 1_024, iteration, result);
      model.doubleWriteEpochs(4, 1_024, iteration, result);
    }
    long[] samples = new long[2_000];
    long allocationBefore = allocation.currentThreadBytes();
    long started = System.nanoTime();
    long sink = 0L;
    for (int iteration = 0; iteration < samples.length; iteration++) {
      long operationStarted = System.nanoTime();
      if ((iteration & 1) == 0) {
        model.firstPageImageEpochs(4, 1_024, iteration, result);
      } else {
        model.doubleWriteEpochs(4, 1_024, iteration, result);
      }
      samples[iteration] = System.nanoTime() - operationStarted;
      sink ^= result.totalBytes();
    }
    PrototypeSmoke.Measurement measurement = PrototypeSmoke.summarize(
        "page_protection_model", StatusCode.OK, samples, System.nanoTime() - started,
        allocation.delta(allocationBefore));
    measurement.operations = (long) samples.length * 4_096L;
    measurement.checkValue = sink;
    model.firstPageImageEpochs(4, 1_024, 17L, result);
    measurement.firstPageImageBytes = result.totalBytes();
    measurement.firstPageImageWalBytes = result.walBytes();
    measurement.firstPageImageStagingBytes = result.stagingBytes();
    measurement.firstPageImageDataBytes = result.dataBytes();
    measurement.firstPageImageWalForces = result.walForceCalls();
    measurement.firstPageImageStagingForces = result.stagingForceCalls();
    measurement.firstPageImageDataForces = result.dataForceCalls();
    measurement.firstPageImageCopiedBytes = result.copiedBytes();
    measurement.firstPageImageCopies = result.immutableImageCopies();
    measurement.checkpointEpochs = result.checkpointEpochs();
    model.doubleWriteEpochs(4, 1_024, 17L, result);
    measurement.doubleWriteBytes = result.totalBytes();
    measurement.doubleWriteWalBytes = result.walBytes();
    measurement.doubleWriteStagingBytes = result.stagingBytes();
    measurement.doubleWriteDataBytes = result.dataBytes();
    measurement.doubleWriteWalForces = result.walForceCalls();
    measurement.doubleWriteStagingForces = result.stagingForceCalls();
    measurement.doubleWriteDataForces = result.dataForceCalls();
    measurement.doubleWriteCopiedBytes = result.copiedBytes();
    measurement.doubleWriteCopies = result.stagingCopies();
    return measurement;
  }

  static PrototypeSmoke.Measurement pageIo(
      int pageSize,
      PrototypeSmoke.AllocationMeter allocation) {
    StatusCode warmStatus = PrototypeSmoke.warmPageIo(pageSize);
    if (!warmStatus.isOk()) {
      return PrototypeSmoke.summarize(
          "page_io_" + pageSize, warmStatus, new long[PrototypeSmoke.IO_ITERATIONS], 1L, -1L);
    }
    var opened = new PageIoOpenResult();
    StatusCode status = PageIoPrototype.openTemp(pageSize, opened);
    long[] samples = new long[PrototypeSmoke.IO_ITERATIONS];
    if (!status.isOk()) {
      return PrototypeSmoke.summarize("page_io_" + pageSize, status, samples, 1L, -1L);
    }
    try (PageIoPrototype io = opened.value()) {
      io.prepare(0x51A7L);
      long allocationBefore = allocation.currentThreadBytes();
      long started = System.nanoTime();
      for (int iteration = 0; iteration < samples.length; iteration++) {
        long operationStarted = System.nanoTime();
        status = io.writePage(iteration & 7L);
        if (status.isOk()) status = io.force();
        if (status.isOk()) status = io.readPage(iteration & 7L);
        samples[iteration] = System.nanoTime() - operationStarted;
        if (!status.isOk()) break;
      }
      PrototypeSmoke.Measurement measurement = PrototypeSmoke.summarize(
          "page_io_" + pageSize, status, samples, System.nanoTime() - started,
          allocation.delta(allocationBefore));
      measurement.operations = PrototypeSmoke.IO_ITERATIONS;
      measurement.bytes = io.counters().readBytes() + io.counters().writtenBytes();
      measurement.copiedBytes = io.counters().copiedBytes();
      measurement.forceCalls = io.counters().forceCalls();
      measurement.pageSize = pageSize;
      return measurement;
    }
  }
}
