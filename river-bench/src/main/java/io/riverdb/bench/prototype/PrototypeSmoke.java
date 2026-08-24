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
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;

/** Short local P09 run which emits explicitly non-gating JSON evidence. */
public final class PrototypeSmoke {
  static final int HOT_ITERATIONS = 10_000;
  static final int IO_ITERATIONS = 32;

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
    return PrototypeHotMeasurements.wal(allocation, HOT_ITERATIONS);
  }

  private static Measurement measureVector(AllocationMeter allocation) {
    return PrototypeHotMeasurements.vector(allocation, HOT_ITERATIONS);
  }

  private static Measurement measureWalMpsc(AllocationMeter allocation) {
    return PrototypeWalMpsc.measure(allocation, HOT_ITERATIONS);
  }

  private static Measurement measureVersions(AllocationMeter allocation) {
    return PrototypeStorageMeasurements.versions(allocation, HOT_ITERATIONS);
  }

  private static Measurement measureVersionAppendRead(AllocationMeter allocation) {
    return PrototypeStorageMeasurements.appendRead(allocation, HOT_ITERATIONS);
  }

  private static Measurement measureProtectionModel(AllocationMeter allocation) {
    return PrototypeStorageMeasurements.protection(allocation);
  }

  private static Measurement measurePageIo(int pageSize, AllocationMeter allocation) {
    return PrototypeStorageMeasurements.pageIo(pageSize, allocation);
  }

  static Measurement summarize(
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

  static void warmWal() {
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

  static StatusCode warmPageIo(int pageSize) {
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

  static final class AllocationMeter {
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

  static final class Measurement {
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
