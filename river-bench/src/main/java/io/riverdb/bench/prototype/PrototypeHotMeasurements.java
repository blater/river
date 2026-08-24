package io.riverdb.bench.prototype;

import io.riverdb.base.error.StatusCode;

/** Measures the bounded hot-path WAL and primitive-vector prototypes. */
final class PrototypeHotMeasurements {
  private PrototypeHotMeasurements() { }

  static PrototypeSmoke.Measurement wal(
      PrototypeSmoke.AllocationMeter allocation,
      int hotIterations) {
    PrototypeSmoke.warmWal();
    var ring = new PreallocatedWalRing(1_024);
    var reservation = new WalReservation();
    var record = new WalRecord();
    long[] samples = new long[hotIterations];
    long allocationBefore = allocation.currentThreadBytes();
    long started = System.nanoTime();
    StatusCode status = StatusCode.OK;
    for (int iteration = 0; iteration < hotIterations; iteration++) {
      long operationStarted = System.nanoTime();
      status = ring.tryReserve(reservation);
      if (status.isOk()) status = ring.encode(reservation, iteration, iteration * 17L);
      if (status.isOk()) status = ring.publish(reservation);
      if (status.isOk()) status = ring.poll(record);
      samples[iteration] = System.nanoTime() - operationStarted;
      if (!status.isOk()) break;
    }
    var pressureRing = new PreallocatedWalRing(8);
    var claims = new WalReservation[9];
    for (int index = 0; index < claims.length; index++) claims[index] = new WalReservation();
    for (int index = 0; index < 8; index++) pressureRing.tryReserve(claims[index]);
    pressureRing.tryReserve(claims[8]);
    PrototypeSmoke.Measurement measurement = PrototypeSmoke.summarize(
        "wal_ring", status, samples, System.nanoTime() - started,
        allocation.delta(allocationBefore));
    measurement.operations = ring.counters().consumed();
    measurement.bytes = ring.counters().encodedBytes();
    measurement.copiedBytes = ring.counters().copiedBytes();
    measurement.maximumOccupancy = pressureRing.counters().maximumOccupancy();
    measurement.backpressureEvents = pressureRing.counters().backpressureEvents();
    return measurement;
  }

  static PrototypeSmoke.Measurement vector(
      PrototypeSmoke.AllocationMeter allocation,
      int hotIterations) {
    var batch = new PrimitiveVectorBatch(4_096);
    for (int row = 0; row < batch.capacity(); row++) {
      batch.setRow(row, row + 1L, row * 37L & 65_535L);
    }
    for (int iteration = 0; iteration < 1_000; iteration++) {
      batch.scanBalanceAtLeast(iteration & 65_535L);
      batch.sumSelectedAccountIds();
    }
    long[] samples = new long[hotIterations];
    long allocationBefore = allocation.currentThreadBytes();
    long started = System.nanoTime();
    long rows = 0L;
    long sink = 0L;
    for (int iteration = 0; iteration < hotIterations; iteration++) {
      long operationStarted = System.nanoTime();
      batch.scanBalanceAtLeast(iteration & 65_535L);
      sink ^= batch.sumSelectedAccountIds();
      samples[iteration] = System.nanoTime() - operationStarted;
      rows += batch.rowCount();
    }
    PrototypeSmoke.Measurement measurement = PrototypeSmoke.summarize(
        "primitive_vector_scan", StatusCode.OK, samples, System.nanoTime() - started,
        allocation.delta(allocationBefore));
    measurement.operations = rows;
    measurement.checkValue = sink;
    return measurement;
  }
}
