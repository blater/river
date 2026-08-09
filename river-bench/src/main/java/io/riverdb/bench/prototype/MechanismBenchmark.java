package io.riverdb.bench.prototype;

import io.riverdb.base.error.StatusCode;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/** JMH mechanism comparisons; these are developer evidence, not product gates. */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class MechanismBenchmark {
  @State(Scope.Thread)
  public static class WalState {
    final PreallocatedWalRing ring = new PreallocatedWalRing(1_024);
    final WalReservation reservation = new WalReservation();
    final WalRecord record = new WalRecord();
    long sequence;
  }

  @State(Scope.Thread)
  public static class VectorState {
    final PrimitiveVectorBatch batch = new PrimitiveVectorBatch(1_024);
    long threshold;

    @Setup(Level.Trial)
    public void setup() {
      for (int row = 0; row < batch.capacity(); row++) {
        batch.setRow(row, row + 1L, row * 17L & 16_383L);
      }
    }
  }

  @State(Scope.Thread)
  public static class VersionState {
    final FixedVersionStore store = new FixedVersionStore(4_096);
    long snapshot;

    @Setup(Level.Trial)
    public void setup() {
      for (int record = 0; record < 4_096; record++) {
        long begin = record & 255L;
        long end = (record & 3) == 0 ? 0L : begin + 64L;
        StatusCode status = store.append(record, begin, end, record * 3L, 0L);
        if (!status.isOk()) {
          throw new IllegalStateException("version benchmark setup failed: " + status);
        }
      }
    }
  }

  @State(Scope.Thread)
  public static class PageIoState {
    @Param({"8192", "16384", "32768"})
    int pageSize;

    PageIoPrototype io;
    long page;

    @Setup(Level.Trial)
    public void setup() {
      var result = new PageIoOpenResult();
      StatusCode status = PageIoPrototype.openTemp(pageSize, result);
      if (!status.isOk()) {
        throw new IllegalStateException("page I/O benchmark setup failed: " + status);
      }
      io = result.value();
      io.prepare(0x51A7L);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      io.close();
    }
  }

  @State(Scope.Thread)
  public static class ProtectionState {
    final PageProtectionModel model = new PageProtectionModel(
      4_096,
      16 * 1_024,
      128,
      64
    );
    final PageProtectionResult result = new PageProtectionResult();
    long seed;
  }

  @Benchmark
  public long walReserveEncodePublishConsume(WalState state) {
    long sequence = state.sequence++;
    StatusCode status = state.ring.tryReserve(state.reservation);
    if (status.isOk()) {
      status = state.ring.encode(state.reservation, sequence, sequence * 31L);
    }
    if (status.isOk()) {
      status = state.ring.publish(state.reservation);
    }
    if (status.isOk()) {
      status = state.ring.poll(state.record);
    }
    return status.stableCode() + state.record.value();
  }

  @Benchmark
  public long primitiveVectorFilter(VectorState state) {
    state.threshold = state.threshold + 31L & 16_383L;
    state.batch.scanBalanceAtLeast(state.threshold);
    return state.batch.sumSelectedAccountIds();
  }

  @Benchmark
  public long fixedVersionVisibilityScan(VersionState state) {
    state.snapshot = state.snapshot + 1L & 255L;
    state.store.scanVisible(state.snapshot);
    return state.store.sumVisibleValues();
  }

  @Benchmark
  public int positionalPageWriteRead(PageIoState state, Blackhole blackhole) {
    long page = state.page++ & 7L;
    StatusCode status = state.io.writePage(page);
    if (status.isOk()) {
      status = state.io.readPage(page);
    }
    blackhole.consume(state.io.readLong(0));
    return status.stableCode();
  }

  @Benchmark
  public int positionalPageWriteForce(PageIoState state) {
    StatusCode status = state.io.writePage(state.page++ & 7L);
    if (status.isOk()) {
      status = state.io.force();
    }
    return status.stableCode();
  }

  @Benchmark
  public long firstPageImageStorm(ProtectionState state) {
    state.model.firstPageImage(4_096, state.seed++, state.result);
    return state.result.totalBytes() + state.result.forceCalls();
  }

  @Benchmark
  public long doubleWriteStorm(ProtectionState state) {
    state.model.doubleWrite(4_096, state.seed++, state.result);
    return state.result.totalBytes() + state.result.forceCalls();
  }
}
