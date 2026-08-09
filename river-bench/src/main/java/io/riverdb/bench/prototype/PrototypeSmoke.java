package io.riverdb.bench.prototype;

import com.sun.management.ThreadMXBean;
import io.riverdb.base.error.StatusCode;
import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;

/** Short local P09 run which emits explicitly non-gating JSON evidence. */
public final class PrototypeSmoke {
  private static final int HOT_ITERATIONS = 10_000;
  private static final int IO_ITERATIONS = 32;

  private PrototypeSmoke() {
  }

  public static void main(String[] arguments) {
    Path outputDirectory = Path.of(arguments[0]);
    StatusCode status = run(outputDirectory);
    if (!status.isOk()) {
      throw new IllegalStateException("prototype smoke failed: " + status);
    }
    System.out.println(outputDirectory.resolve("results.json"));
    System.out.println(outputDirectory.resolve("manifest.json"));
  }

  static StatusCode run(Path outputDirectory) {
    try {
      Files.createDirectories(outputDirectory);
      AllocationMeter allocation = new AllocationMeter();
      Measurement wal = measureWal(allocation);
      Measurement walMpsc = measureWalMpsc(allocation);
      Measurement vector = measureVector(allocation);
      Measurement versions = measureVersions(allocation);
      Measurement versionAppendRead = measureVersionAppendRead(allocation);
      Measurement model = measureProtectionModel(allocation);
      Measurement[] pageIo = new Measurement[] {
        measurePageIo(8 * 1_024, allocation),
        measurePageIo(16 * 1_024, allocation),
        measurePageIo(32 * 1_024, allocation)
      };
      Measurement[] requiredMeasurements = new Measurement[] {
        wal,
        walMpsc,
        vector,
        versions,
        versionAppendRead,
        model,
        pageIo[0],
        pageIo[1],
        pageIo[2]
      };
      for (Measurement measurement : requiredMeasurements) {
        if (!measurement.status.isOk()) {
          return measurement.status;
        }
      }
      String results = resultsJson(
        wal,
        walMpsc,
        vector,
        versions,
        versionAppendRead,
        model,
        pageIo
      );
      String manifest = manifestJson(allocation.available);
      Files.writeString(
        outputDirectory.resolve("results.json"),
        results,
        StandardCharsets.UTF_8
      );
      Files.writeString(
        outputDirectory.resolve("manifest.json"),
        manifest,
        StandardCharsets.UTF_8
      );
      return StatusCode.OK;
    } catch (IOException failure) {
      return StatusCode.IO_FAILURE;
    }
  }

  private static Measurement measureWal(AllocationMeter allocation) {
    warmWal();
    var ring = new PreallocatedWalRing(1_024);
    var reservation = new WalReservation();
    var record = new WalRecord();
    long[] samples = new long[HOT_ITERATIONS];
    long allocationBefore = allocation.currentThreadBytes();
    long started = System.nanoTime();
    StatusCode status = StatusCode.OK;
    for (int iteration = 0; iteration < HOT_ITERATIONS; iteration++) {
      long operationStarted = System.nanoTime();
      status = ring.tryReserve(reservation);
      if (status.isOk()) {
        status = ring.encode(reservation, iteration, iteration * 17L);
      }
      if (status.isOk()) {
        status = ring.publish(reservation);
      }
      if (status.isOk()) {
        status = ring.poll(record);
      }
      samples[iteration] = System.nanoTime() - operationStarted;
      if (!status.isOk()) {
        break;
      }
    }
    long elapsed = System.nanoTime() - started;
    long allocated = allocation.delta(allocationBefore);

    var pressureRing = new PreallocatedWalRing(8);
    var claims = new WalReservation[9];
    for (int index = 0; index < claims.length; index++) {
      claims[index] = new WalReservation();
    }
    for (int index = 0; index < 8; index++) {
      pressureRing.tryReserve(claims[index]);
    }
    pressureRing.tryReserve(claims[8]);

    Measurement measurement = summarize("wal_ring", status, samples, elapsed, allocated);
    measurement.operations = ring.counters().consumed();
    measurement.bytes = ring.counters().encodedBytes();
    measurement.copiedBytes = ring.counters().copiedBytes();
    measurement.maximumOccupancy = pressureRing.counters().maximumOccupancy();
    measurement.backpressureEvents = pressureRing.counters().backpressureEvents();
    return measurement;
  }

  private static Measurement measureVector(AllocationMeter allocation) {
    var batch = new PrimitiveVectorBatch(4_096);
    for (int row = 0; row < batch.capacity(); row++) {
      batch.setRow(row, row + 1L, row * 37L & 65_535L);
    }
    for (int iteration = 0; iteration < 1_000; iteration++) {
      batch.scanBalanceAtLeast(iteration & 65_535L);
      batch.sumSelectedAccountIds();
    }
    long[] samples = new long[HOT_ITERATIONS];
    long allocationBefore = allocation.currentThreadBytes();
    long started = System.nanoTime();
    long rows = 0L;
    long sink = 0L;
    for (int iteration = 0; iteration < HOT_ITERATIONS; iteration++) {
      long operationStarted = System.nanoTime();
      batch.scanBalanceAtLeast(iteration & 65_535L);
      sink ^= batch.sumSelectedAccountIds();
      samples[iteration] = System.nanoTime() - operationStarted;
      rows += batch.rowCount();
    }
    long elapsed = System.nanoTime() - started;
    Measurement measurement = summarize(
      "primitive_vector_scan",
      StatusCode.OK,
      samples,
      elapsed,
      allocation.delta(allocationBefore)
    );
    measurement.operations = rows;
    measurement.checkValue = sink;
    return measurement;
  }

  private static Measurement measureWalMpsc(AllocationMeter allocation) {
    int producerIterations = HOT_ITERATIONS / 2;
    var ring = new PreallocatedWalRing(128);
    var start = new CountDownLatch(1);
    var holeReserved = new CountDownLatch(1);
    var secondPublished = new CountDownLatch(1);
    var releaseHole = new CountDownLatch(1);
    long[][] samples = new long[][] {
      new long[producerIterations], new long[producerIterations]
    };
    long[] allocated = new long[2];
    StatusCode[] producerStatuses = new StatusCode[] {StatusCode.OK, StatusCode.OK};
    Thread first = Thread.ofPlatform().unstarted(() -> runWalProducer(
      0,
      producerIterations,
      ring,
      start,
      holeReserved,
      secondPublished,
      releaseHole,
      samples[0],
      allocated,
      producerStatuses,
      allocation
    ));
    Thread second = Thread.ofPlatform().unstarted(() -> runWalProducer(
      1,
      producerIterations,
      ring,
      start,
      holeReserved,
      secondPublished,
      releaseHole,
      samples[1],
      allocated,
      producerStatuses,
      allocation
    ));
    first.start();
    second.start();
    long started = System.nanoTime();
    start.countDown();
    StatusCode status = StatusCode.OK;
    long delayedPublicationObserved = 0L;
    long consumed = 0L;
    var record = new WalRecord();
    try {
      if (!holeReserved.await(5L, TimeUnit.SECONDS)
          || !secondPublished.await(5L, TimeUnit.SECONDS)) {
        status = StatusCode.TIMEOUT;
      } else if (ring.publishedSequence() == -1L) {
        delayedPublicationObserved = 1L;
      } else {
        status = StatusCode.INVARIANT_BROKEN;
      }
      releaseHole.countDown();
      long expected = (long) producerIterations * 2L;
      while (status.isOk() && consumed < expected) {
        StatusCode pollStatus = ring.poll(record);
        if (pollStatus.isOk()) {
          consumed++;
        } else if (pollStatus == StatusCode.RETRY) {
          Thread.onSpinWait();
        } else {
          status = pollStatus;
        }
      }
      first.join(TimeUnit.SECONDS.toMillis(5L));
      second.join(TimeUnit.SECONDS.toMillis(5L));
      if (first.isAlive() || second.isAlive()) {
        status = StatusCode.TIMEOUT;
      } else if (!producerStatuses[0].isOk()) {
        status = producerStatuses[0];
      } else if (!producerStatuses[1].isOk()) {
        status = producerStatuses[1];
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      status = StatusCode.CANCELLED;
    } finally {
      releaseHole.countDown();
    }
    long elapsed = System.nanoTime() - started;
    long[] mergedSamples = new long[producerIterations * 2];
    System.arraycopy(samples[0], 0, mergedSamples, 0, producerIterations);
    System.arraycopy(
      samples[1],
      0,
      mergedSamples,
      producerIterations,
      producerIterations
    );
    Measurement measurement = summarize(
      "wal_ring_mpsc_delayed_hole",
      status,
      mergedSamples,
      elapsed,
      allocated[0] < 0L || allocated[1] < 0L ? -1L : allocated[0] + allocated[1]
    );
    measurement.operations = consumed;
    measurement.bytes = ring.counters().encodedBytes();
    measurement.copiedBytes = ring.counters().copiedBytes();
    measurement.maximumOccupancy = ring.counters().maximumOccupancy();
    measurement.backpressureEvents = ring.counters().backpressureEvents();
    measurement.delayedPublicationObserved = delayedPublicationObserved;
    measurement.saturationRecovered = verifySaturationRecovery();
    if (measurement.saturationRecovered == 0L && status.isOk()) {
      measurement.status = StatusCode.INVARIANT_BROKEN;
    }
    return measurement;
  }

  private static void runWalProducer(
      int producer,
      int iterations,
      PreallocatedWalRing ring,
      CountDownLatch start,
      CountDownLatch holeReserved,
      CountDownLatch secondPublished,
      CountDownLatch releaseHole,
      long[] samples,
      long[] allocated,
      StatusCode[] producerStatuses,
      AllocationMeter allocation
  ) {
    var reservation = new WalReservation();
    try {
      start.await();
      if (producer == 1) {
        holeReserved.await();
      }
      long allocationBefore = allocation.currentThreadBytes();
      for (int iteration = 0; iteration < iterations; iteration++) {
        long operationStarted = System.nanoTime();
        StatusCode status;
        do {
          status = ring.tryReserve(reservation);
          if (status == StatusCode.RESOURCE_EXHAUSTED) {
            Thread.onSpinWait();
          }
        } while (status == StatusCode.RESOURCE_EXHAUSTED);
        if (status.isOk()) {
          long transaction = ((long) producer << 32) | iteration;
          status = ring.encode(reservation, transaction, transaction * 17L);
        }
        if (status.isOk() && producer == 0 && iteration == 0) {
          holeReserved.countDown();
          releaseHole.await();
        }
        if (status.isOk()) {
          status = ring.publish(reservation);
        }
        if (status.isOk() && producer == 1 && iteration == 0) {
          secondPublished.countDown();
        }
        samples[iteration] = System.nanoTime() - operationStarted;
        if (!status.isOk()) {
          producerStatuses[producer] = status;
          break;
        }
      }
      allocated[producer] = allocation.delta(allocationBefore);
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      producerStatuses[producer] = StatusCode.CANCELLED;
    }
  }

  private static long verifySaturationRecovery() {
    var ring = new PreallocatedWalRing(2);
    var first = new WalReservation();
    var second = new WalReservation();
    var third = new WalReservation();
    var record = new WalRecord();
    if (!reserveEncodePublish(ring, first, 1L).isOk()
        || !reserveEncodePublish(ring, second, 2L).isOk()
        || ring.tryReserve(third) != StatusCode.RESOURCE_EXHAUSTED
        || !ring.poll(record).isOk()
        || record.transactionId() != 1L
        || !ring.tryReserve(third).isOk()
        || !ring.encode(third, 3L, 3L).isOk()
        || !ring.publish(third).isOk()
        || !ring.poll(record).isOk()
        || record.transactionId() != 2L
        || !ring.poll(record).isOk()
        || record.transactionId() != 3L
        || ring.occupancy() != 0L) {
      return 0L;
    }
    return 1L;
  }

  private static StatusCode reserveEncodePublish(
      PreallocatedWalRing ring,
      WalReservation reservation,
      long value
  ) {
    StatusCode status = ring.tryReserve(reservation);
    if (status.isOk()) {
      status = ring.encode(reservation, value, value);
    }
    if (status.isOk()) {
      status = ring.publish(reservation);
    }
    return status;
  }

  private static Measurement measureVersions(AllocationMeter allocation) {
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
    long[] samples = new long[HOT_ITERATIONS];
    long allocationBefore = allocation.currentThreadBytes();
    long started = System.nanoTime();
    long sink = 0L;
    for (int iteration = 0; iteration < HOT_ITERATIONS; iteration++) {
      long operationStarted = System.nanoTime();
      store.scanVisible(iteration & 511L);
      sink ^= store.sumVisibleValues();
      samples[iteration] = System.nanoTime() - operationStarted;
    }
    long elapsed = System.nanoTime() - started;
    Measurement measurement = summarize(
      "fixed_version_scan",
      StatusCode.OK,
      samples,
      elapsed,
      allocation.delta(allocationBefore)
    );
    measurement.operations = (long) HOT_ITERATIONS * store.size();
    measurement.bytes = store.encodedBytes();
    measurement.copiedBytes = store.copiedBytes();
    measurement.checkValue = sink;
    return measurement;
  }

  private static Measurement measureVersionAppendRead(AllocationMeter allocation) {
    var store = new FixedVersionStore(1_024);
    var record = new VersionRecord();
    for (int iteration = 0; iteration < 1_024; iteration++) {
      store.append(iteration, iteration, 0L, iteration * 13L, 0L);
      store.read(iteration, record);
    }
    store.clear();
    long[] samples = new long[HOT_ITERATIONS];
    long allocationBefore = allocation.currentThreadBytes();
    long started = System.nanoTime();
    long sink = 0L;
    StatusCode status = StatusCode.OK;
    for (int iteration = 0; iteration < HOT_ITERATIONS; iteration++) {
      if (store.size() == 1_024) {
        store.clear();
      }
      long operationStarted = System.nanoTime();
      status = store.append(iteration, iteration, 0L, iteration * 13L, 0L);
      if (status.isOk()) {
        status = store.read(store.size() - 1, record);
      }
      samples[iteration] = System.nanoTime() - operationStarted;
      sink ^= record.value();
      if (!status.isOk()) {
        break;
      }
    }
    long elapsed = System.nanoTime() - started;
    Measurement measurement = summarize(
      "fixed_version_append_read",
      status,
      samples,
      elapsed,
      allocation.delta(allocationBefore)
    );
    measurement.operations = HOT_ITERATIONS;
    measurement.bytes = (long) HOT_ITERATIONS * FixedVersionStore.RECORD_BYTES;
    measurement.copiedBytes = store.copiedBytes();
    measurement.checkValue = sink;
    return measurement;
  }

  private static Measurement measureProtectionModel(AllocationMeter allocation) {
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
    long elapsed = System.nanoTime() - started;
    Measurement measurement = summarize(
      "page_protection_model",
      StatusCode.OK,
      samples,
      elapsed,
      allocation.delta(allocationBefore)
    );
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

  private static Measurement measurePageIo(int pageSize, AllocationMeter allocation) {
    StatusCode warmStatus = warmPageIo(pageSize);
    if (!warmStatus.isOk()) {
      return summarize(
        "page_io_" + pageSize,
        warmStatus,
        new long[IO_ITERATIONS],
        1L,
        -1L
      );
    }
    var opened = new PageIoOpenResult();
    StatusCode status = PageIoPrototype.openTemp(pageSize, opened);
    long[] samples = new long[IO_ITERATIONS];
    if (!status.isOk()) {
      return summarize("page_io_" + pageSize, status, samples, 1L, -1L);
    }
    try (PageIoPrototype io = opened.value()) {
      io.prepare(0x51A7L);
      long allocationBefore = allocation.currentThreadBytes();
      long started = System.nanoTime();
      for (int iteration = 0; iteration < IO_ITERATIONS; iteration++) {
        long operationStarted = System.nanoTime();
        status = io.writePage(iteration & 7L);
        if (status.isOk()) {
          status = io.force();
        }
        if (status.isOk()) {
          status = io.readPage(iteration & 7L);
        }
        samples[iteration] = System.nanoTime() - operationStarted;
        if (!status.isOk()) {
          break;
        }
      }
      long elapsed = System.nanoTime() - started;
      Measurement measurement = summarize(
        "page_io_" + pageSize,
        status,
        samples,
        elapsed,
        allocation.delta(allocationBefore)
      );
      measurement.operations = IO_ITERATIONS;
      measurement.bytes = io.counters().readBytes() + io.counters().writtenBytes();
      measurement.copiedBytes = io.counters().copiedBytes();
      measurement.forceCalls = io.counters().forceCalls();
      measurement.pageSize = pageSize;
      return measurement;
    }
  }

  private static Measurement summarize(
      String name,
      StatusCode status,
      long[] samples,
      long elapsed,
      long allocated
  ) {
    Arrays.sort(samples);
    var measurement = new Measurement();
    measurement.name = name;
    measurement.status = status;
    measurement.elapsedNanos = elapsed;
    measurement.allocatedBytes = allocated;
    measurement.p50Nanos = percentile(samples, 50);
    measurement.p99Nanos = percentile(samples, 99);
    return measurement;
  }

  private static void warmWal() {
    var ring = new PreallocatedWalRing(1_024);
    var reservation = new WalReservation();
    var record = new WalRecord();
    for (int iteration = 0; iteration < 10_000; iteration++) {
      ring.tryReserve(reservation);
      ring.encode(reservation, iteration, iteration * 17L);
      ring.publish(reservation);
      ring.poll(record);
    }
  }

  private static StatusCode warmPageIo(int pageSize) {
    var opened = new PageIoOpenResult();
    StatusCode status = PageIoPrototype.openTemp(pageSize, opened);
    if (!status.isOk()) {
      return status;
    }
    try (PageIoPrototype io = opened.value()) {
      io.prepare(0x51A7L);
      for (int iteration = 0; iteration < 4; iteration++) {
        status = io.writePage(iteration);
        if (status.isOk()) {
          status = io.force();
        }
        if (status.isOk()) {
          status = io.readPage(iteration);
        }
        if (!status.isOk()) {
          return status;
        }
      }
      return StatusCode.OK;
    }
  }

  private static long percentile(long[] sorted, int percentile) {
    int index = (int) ((sorted.length - 1L) * percentile / 100L);
    return sorted[index];
  }

  private static String resultsJson(
      Measurement wal,
      Measurement walMpsc,
      Measurement vector,
      Measurement versions,
      Measurement versionAppendRead,
      Measurement model,
      Measurement[] pageIo
  ) {
    StringBuilder json = new StringBuilder(4_096);
    json.append("{\n");
    json.append("  \"schema_version\": 1,\n");
    json.append("  \"evidence_class\": \"developer_only_not_gate\",\n");
    json.append("  \"budget_status\": \"not_frozen\",\n");
    json.append("  \"gate_claim\": \"none_P05_G0_not_claimed\",\n");
    json.append("  \"measurements\": [\n");
    appendMeasurement(json, wal, true);
    appendMeasurement(json, walMpsc, true);
    appendMeasurement(json, vector, true);
    appendMeasurement(json, versions, true);
    appendMeasurement(json, versionAppendRead, true);
    appendMeasurement(json, model, true);
    for (int index = 0; index < pageIo.length; index++) {
      appendMeasurement(json, pageIo[index], index + 1 < pageIo.length);
    }
    json.append("  ]\n");
    json.append("}\n");
    return json.toString();
  }

  private static void appendMeasurement(
      StringBuilder json,
      Measurement measurement,
      boolean comma
  ) {
    double throughput = measurement.elapsedNanos == 0L
      ? 0.0
      : measurement.operations * 1_000_000_000.0 / measurement.elapsedNanos;
    json.append("    {\n");
    json.append("      \"name\": \"").append(measurement.name).append("\",\n");
    json.append("      \"status\": \"").append(measurement.status).append("\",\n");
    json.append("      \"operations\": ").append(measurement.operations).append(",\n");
    json.append("      \"elapsed_ns\": ").append(measurement.elapsedNanos).append(",\n");
    json.append("      \"throughput_ops_s\": ").append(throughput).append(",\n");
    json.append("      \"latency_p50_ns\": ").append(measurement.p50Nanos).append(",\n");
    json.append("      \"latency_p99_ns\": ").append(measurement.p99Nanos).append(",\n");
    json.append("      \"thread_allocated_bytes\": ")
      .append(measurement.allocatedBytes).append(",\n");
    json.append("      \"encoded_or_io_bytes\": ").append(measurement.bytes).append(",\n");
    json.append("      \"copied_bytes\": ").append(measurement.copiedBytes).append(",\n");
    json.append("      \"maximum_queue_occupancy\": ")
      .append(measurement.maximumOccupancy).append(",\n");
    json.append("      \"backpressure_events\": ")
      .append(measurement.backpressureEvents).append(",\n");
    json.append("      \"delayed_publication_observed\": ")
      .append(measurement.delayedPublicationObserved).append(",\n");
    json.append("      \"saturation_recovered\": ")
      .append(measurement.saturationRecovered).append(",\n");
    json.append("      \"force_calls\": ").append(measurement.forceCalls).append(",\n");
    json.append("      \"page_size\": ").append(measurement.pageSize).append(",\n");
    json.append("      \"fpi_total_bytes\": ")
      .append(measurement.firstPageImageBytes).append(",\n");
    json.append("      \"fpi_wal_bytes\": ")
      .append(measurement.firstPageImageWalBytes).append(",\n");
    json.append("      \"fpi_staging_bytes\": ")
      .append(measurement.firstPageImageStagingBytes).append(",\n");
    json.append("      \"fpi_data_bytes\": ")
      .append(measurement.firstPageImageDataBytes).append(",\n");
    json.append("      \"fpi_wal_force_calls\": ")
      .append(measurement.firstPageImageWalForces).append(",\n");
    json.append("      \"fpi_staging_force_calls\": ")
      .append(measurement.firstPageImageStagingForces).append(",\n");
    json.append("      \"fpi_data_force_calls\": ")
      .append(measurement.firstPageImageDataForces).append(",\n");
    json.append("      \"fpi_copied_bytes\": ")
      .append(measurement.firstPageImageCopiedBytes).append(",\n");
    json.append("      \"fpi_immutable_image_copies\": ")
      .append(measurement.firstPageImageCopies).append(",\n");
    json.append("      \"double_write_total_bytes\": ")
      .append(measurement.doubleWriteBytes).append(",\n");
    json.append("      \"double_write_wal_bytes\": ")
      .append(measurement.doubleWriteWalBytes).append(",\n");
    json.append("      \"double_write_staging_bytes\": ")
      .append(measurement.doubleWriteStagingBytes).append(",\n");
    json.append("      \"double_write_data_bytes\": ")
      .append(measurement.doubleWriteDataBytes).append(",\n");
    json.append("      \"double_write_wal_force_calls\": ")
      .append(measurement.doubleWriteWalForces).append(",\n");
    json.append("      \"double_write_staging_force_calls\": ")
      .append(measurement.doubleWriteStagingForces).append(",\n");
    json.append("      \"double_write_data_force_calls\": ")
      .append(measurement.doubleWriteDataForces).append(",\n");
    json.append("      \"double_write_copied_bytes\": ")
      .append(measurement.doubleWriteCopiedBytes).append(",\n");
    json.append("      \"double_write_staging_copies\": ")
      .append(measurement.doubleWriteCopies).append(",\n");
    json.append("      \"checkpoint_epochs\": ")
      .append(measurement.checkpointEpochs).append(",\n");
    json.append("      \"check_value\": ").append(measurement.checkValue).append("\n");
    json.append("    }");
    json.append(comma ? ",\n" : "\n");
  }

  private static String manifestJson(boolean allocationAvailable) throws IOException {
    String commit = commandOutput("git", "rev-parse", "HEAD");
    String dirty = commandOutput("git", "status", "--porcelain").isEmpty()
      ? "false" : "true";
    long reservationBytes = ClassLayout.parseInstance(new WalReservation()).instanceSize();
    long recordBytes = ClassLayout.parseInstance(new WalRecord()).instanceSize();
    int objectAlignment = VM.current().objectAlignment();
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    String jvmArguments = jsonStringArray(
      ManagementFactory.getRuntimeMXBean().getInputArguments().toArray(String[]::new),
      true
    );
    String[] collectorNames = ManagementFactory.getGarbageCollectorMXBeans().stream()
      .map(java.lang.management.GarbageCollectorMXBean::getName)
      .toArray(String[]::new);
    String collectors = jsonStringArray(collectorNames, false);
    long directCount = -1L;
    long directMemoryUsed = -1L;
    long directCapacity = -1L;
    for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
      if ("direct".equals(pool.getName())) {
        directCount = pool.getCount();
        directMemoryUsed = pool.getMemoryUsed();
        directCapacity = pool.getTotalCapacity();
      }
    }
    var fileStore = Files.getFileStore(Path.of(System.getProperty("java.io.tmpdir")));
    return "{\n"
      + "  \"schema_version\": 1,\n"
      + "  \"evidence_class\": \"developer_only_not_gate\",\n"
      + "  \"created_at\": \"" + Instant.now() + "\",\n"
      + "  \"river_commit\": \"" + jsonEscape(commit) + "\",\n"
      + "  \"worktree_dirty\": " + dirty + ",\n"
      + "  \"os\": \"" + jsonEscape(System.getProperty("os.name") + " "
      + System.getProperty("os.version")) + "\",\n"
      + "  \"architecture\": \"" + jsonEscape(System.getProperty("os.arch")) + "\",\n"
      + "  \"jdk\": \"" + jsonEscape(System.getProperty("java.runtime.version")) + "\",\n"
      + "  \"jvm\": \"" + jsonEscape(System.getProperty("java.vm.name")) + "\",\n"
      + "  \"jvm_arguments\": " + jvmArguments + ",\n"
      + "  \"gc_collectors\": " + collectors + ",\n"
      + "  \"heap_init_bytes\": " + heap.getInit() + ",\n"
      + "  \"heap_used_bytes\": " + heap.getUsed() + ",\n"
      + "  \"heap_committed_bytes\": " + heap.getCommitted() + ",\n"
      + "  \"heap_max_bytes\": " + heap.getMax() + ",\n"
      + "  \"direct_buffer_count_current\": " + directCount + ",\n"
      + "  \"direct_buffer_memory_used_current_bytes\": "
      + directMemoryUsed + ",\n"
      + "  \"direct_buffer_capacity_current_bytes\": " + directCapacity + ",\n"
      + "  \"temp_filesystem_name\": \"" + jsonEscape(fileStore.name()) + "\",\n"
      + "  \"temp_filesystem_type\": \"" + jsonEscape(fileStore.type()) + "\",\n"
      + "  \"available_processors\": "
      + Runtime.getRuntime().availableProcessors() + ",\n"
      + "  \"thread_allocation_measurement_available\": "
      + allocationAvailable + ",\n"
      + "  \"wal_reservation_instance_bytes_jol\": " + reservationBytes + ",\n"
      + "  \"wal_record_instance_bytes_jol\": " + recordBytes + ",\n"
      + "  \"object_alignment_bytes_jol\": " + objectAlignment + ",\n"
      + "  \"page_sizes_bytes\": [8192, 16384, 32768],\n"
      + "  \"io_scope\": \"owned_temporary_files_only\",\n"
      + "  \"durability_scope\": \"FileChannel.force(false)_mechanism_only\",\n"
      + "  \"iterations\": {\"hot\": " + HOT_ITERATIONS
      + ", \"io\": " + IO_ITERATIONS + "},\n"
      + "  \"limitations\": [\n"
      + "    \"single_developer_machine\",\n"
      + "    \"no_dedicated_runner_or_control_variance\",\n"
      + "    \"no_end_to_end_sql_or_crash_recovery\",\n"
      + "    \"small_mpsc_scenario_is_not_a_scaling_result\",\n"
      + "    \"filesystem_and_device_cache_policy_not_proven\",\n"
      + "    \"page_protection_is_accounting_model_only\",\n"
      + "    \"models_are_not_production_formats_or_APIs\",\n"
      + "    \"does_not_freeze_budgets_or_claim_P05_or_G0\"\n"
      + "  ]\n"
      + "}\n";
  }

  private static String commandOutput(String... command) {
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      byte[] output = process.getInputStream().readAllBytes();
      int exit = process.waitFor();
      return exit == 0 ? new String(output, StandardCharsets.UTF_8).trim() : "unavailable";
    } catch (IOException failure) {
      return "unavailable";
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      return "interrupted";
    }
  }

  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String jsonStringArray(String[] values, boolean redactProperties) {
    StringBuilder json = new StringBuilder();
    json.append('[');
    for (int index = 0; index < values.length; index++) {
      if (index > 0) {
        json.append(", ");
      }
      String value = redactProperties ? redactArgument(values[index]) : values[index];
      json.append('\"').append(jsonEscape(value)).append('\"');
    }
    json.append(']');
    return json.toString();
  }

  private static String redactArgument(String argument) {
    String lower = argument.toLowerCase(java.util.Locale.ROOT);
    if ((lower.contains("password")
        || lower.contains("secret")
        || lower.contains("token")
        || lower.contains("key"))
        && argument.indexOf('=') >= 0) {
      return argument.substring(0, argument.indexOf('=') + 1) + "<redacted>";
    }
    return argument;
  }

  private static final class AllocationMeter {
    private final ThreadMXBean bean;
    private final boolean available;

    AllocationMeter() {
      java.lang.management.ThreadMXBean candidate = ManagementFactory.getThreadMXBean();
      if (candidate instanceof ThreadMXBean extended
          && extended.isThreadAllocatedMemorySupported()) {
        bean = extended;
        if (!bean.isThreadAllocatedMemoryEnabled()) {
          bean.setThreadAllocatedMemoryEnabled(true);
        }
        available = bean.isThreadAllocatedMemoryEnabled();
      } else {
        bean = null;
        available = false;
      }
    }

    long currentThreadBytes() {
      return available ? bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) : -1L;
    }

    long delta(long before) {
      return available ? currentThreadBytes() - before : -1L;
    }
  }

  private static final class Measurement {
    String name;
    StatusCode status;
    long operations;
    long elapsedNanos;
    long p50Nanos;
    long p99Nanos;
    long allocatedBytes;
    long bytes;
    long copiedBytes;
    long maximumOccupancy;
    long backpressureEvents;
    long delayedPublicationObserved;
    long saturationRecovered;
    long forceCalls;
    long pageSize;
    long firstPageImageBytes;
    long firstPageImageWalBytes;
    long firstPageImageStagingBytes;
    long firstPageImageDataBytes;
    long firstPageImageWalForces;
    long firstPageImageStagingForces;
    long firstPageImageDataForces;
    long doubleWriteBytes;
    long doubleWriteWalBytes;
    long doubleWriteStagingBytes;
    long doubleWriteDataBytes;
    long doubleWriteWalForces;
    long doubleWriteStagingForces;
    long doubleWriteDataForces;
    long firstPageImageCopiedBytes;
    long doubleWriteCopiedBytes;
    long firstPageImageCopies;
    long doubleWriteCopies;
    long checkpointEpochs;
    long checkValue;
  }
}
