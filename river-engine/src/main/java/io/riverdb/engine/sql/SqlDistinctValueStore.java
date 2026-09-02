package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Exact typed set over the shared paged row-store and external-order engine. */
final class SqlDistinctValueStore {
  private final SqlBlockRowStore rows;
  private final SqlBlockSchema schema = new SqlBlockSchema();
  private final SqlBlockRow candidate = new SqlBlockRow();
  private final SqlBlockRow probe = new SqlBlockRow();
  private final SqlBlockRow last = new SqlBlockRow();
  private final SqlBlockRow copied = new SqlBlockRow();
  private final SqlDistinctValueKey key = new SqlDistinctValueKey();
  private final long[] finishCount = new long[1];
  private long distinctCount;
  private boolean finished;
  private boolean finalPresent;

  SqlDistinctValueStore(SqlSessionShapeBudget budget) {
    rows = new SqlBlockRowStore(budget);
  }

  StatusCode begin(int descriptor) {
    key.begin(descriptor);
    schema.set(1);
    schema.setColumn(0, "distinct", descriptor, true);
    StatusCode status = schema.status();
    if (status.isOk()) status = prepareRows();
    if (status.isOk()) status = rows.begin(schema, 0, false);
    resetState();
    return status;
  }

  StatusCode reset() {
    StatusCode status = rows.close();
    if (status.isOk()) status = rows.begin(schema, 0, false);
    resetState();
    return status;
  }

  StatusCode close() {
    StatusCode status = rows.close();
    resetState();
    return status;
  }

  StatusCode add(SqlProjectedRow source, int lane) {
    if (finished) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (source.isNull(lane)) return StatusCode.OK;
    StatusCode status = candidate.reset(1);
    if (status.isOk()) {
      candidate.setDecimal128(0, source.highValue(lane), source.value(lane));
    }
    if (status.isOk() && key.isText()) {
      status = candidate.setText(0, source.text(lane), 0, source.textLength(lane));
    }
    return status.isOk() ? rows.append(candidate) : status;
  }

  StatusCode add(SqlBlockRow source, int lane) {
    if (finished) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (source.nullValue(lane)) return StatusCode.OK;
    StatusCode status = candidate.reset(1);
    if (status.isOk()) {
      candidate.setDecimal128(0, source.highValue(lane), source.value(lane));
    }
    if (status.isOk() && key.isText()) {
      status = candidate.setText(0, source.text(lane), 0, source.textLength(lane));
    }
    return status.isOk() ? rows.append(candidate) : status;
  }

  StatusCode copyFrom(SqlDistinctValueStore source) {
    if (source == null || source == this) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = reset();
    if (status.isOk()) status = source.finish(finishCount);
    if (status.isOk()) status = source.rewindFinal();
    while (status.isOk()) {
      status = source.readFinal(copied);
      if (status == StatusCode.CONFLICT) return StatusCode.OK;
      if (status.isOk()) status = add(copied, 0);
    }
    return status;
  }

  StatusCode finish(long[] result) {
    if (result == null || result.length == 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!finished) {
      StatusCode status = rows.finish();
      if (status.isOk()) status = countDistinct();
      if (!status.isOk()) return status;
      finished = true;
    }
    result[0] = distinctCount;
    return StatusCode.OK;
  }

  StatusCode rewindFinal() {
    if (!finished) return StatusCode.INVALID_EXTERNAL_INPUT;
    rows.rewind();
    finalPresent = false;
    return StatusCode.OK;
  }

  StatusCode readFinal(SqlBlockRow destination) {
    if (!finished || destination == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    while (true) {
      StatusCode status = rows.next(probe);
      if (!status.isOk()) return status;
      if (finalPresent && key.same(probe, last)) continue;
      status = last.copyFrom(probe);
      if (status.isOk()) status = destination.copyFrom(probe);
      if (status.isOk()) finalPresent = true;
      return status;
    }
  }

  private StatusCode countDistinct() {
    rows.rewind();
    finalPresent = false;
    distinctCount = 0;
    while (true) {
      StatusCode status = rows.next(probe);
      if (status == StatusCode.CONFLICT) return StatusCode.OK;
      if (!status.isOk()) return status;
      if (finalPresent && key.same(probe, last)) continue;
      status = last.copyFrom(probe);
      if (!status.isOk()) return status;
      if (distinctCount == Long.MAX_VALUE) return StatusCode.RESOURCE_EXHAUSTED;
      distinctCount++;
      finalPresent = true;
    }
  }

  private StatusCode prepareRows() {
    StatusCode status = candidate.reset(1);
    if (status.isOk()) status = probe.reset(1);
    if (status.isOk()) status = last.reset(1);
    if (status.isOk()) status = copied.reset(1);
    if (status.isOk() && key.isText()) status = candidate.prepareText(0);
    if (status.isOk() && key.isText()) status = probe.prepareText(0);
    if (status.isOk() && key.isText()) status = last.prepareText(0);
    if (status.isOk() && key.isText()) status = copied.prepareText(0);
    return status;
  }

  private void resetState() {
    distinctCount = 0;
    finished = false;
    finalPresent = false;
  }
}
