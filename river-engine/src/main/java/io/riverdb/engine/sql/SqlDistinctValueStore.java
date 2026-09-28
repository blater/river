package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;

/** Exact typed set with bounded inline values and paged external-order spill. */
final class SqlDistinctValueStore {
  private static final int INLINE_VALUES = 16;
  private final SqlBlockRowStore rows;
  private final SqlSessionShapeBudget budget;
  private final SqlBlockRow[] inline = new SqlBlockRow[INLINE_VALUES];
  private final SqlBlockSchema schema = new SqlBlockSchema();
  private final SqlBlockRow candidate = new SqlBlockRow();
  private final SqlBlockRow probe = new SqlBlockRow();
  private final SqlBlockRow last = new SqlBlockRow();
  private final SqlBlockRow copied = new SqlBlockRow();
  private final SqlDistinctValueKey key = new SqlDistinctValueKey();
  private final long[] finishCount = new long[1];
  private long distinctCount;
  private int inlineCount;
  private int inlineRead;
  private boolean spilled;
  private boolean finished;
  private boolean finalPresent;

  SqlDistinctValueStore(SqlSessionShapeBudget budget) {
    this.budget = budget;
    rows = new SqlBlockRowStore(budget);
  }

  StatusCode begin(int descriptor) {
    StatusCode status = rows.close();
    if (!status.isOk()) return status;
    key.begin(descriptor);
    schema.set(1);
    schema.setColumn(0, "distinct", descriptor, true);
    status = schema.status();
    if (status.isOk()) status = prepareRows();
    resetState();
    return status;
  }

  StatusCode reset() {
    StatusCode status = rows.close();
    if (status.isOk()) resetState();
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
    return status.isOk() ? addCandidate() : status;
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
    return status.isOk() ? addCandidate() : status;
  }

  private StatusCode addCandidate() {
    if (spilled) return rows.append(candidate);
    for (int index = 0; index < inlineCount; index++) {
      if (key.same(candidate, inline[index])) return StatusCode.OK;
    }
    if (inlineCount == inline.length) {
      StatusCode status = rows.begin(schema, 0, false);
      if (!status.isOk()) return status;
      spilled = true;
      for (int index = 0; index < inlineCount; index++) {
        status = rows.append(inline[index]);
        if (!status.isOk()) return status;
      }
      return rows.append(candidate);
    }
    if (inline[inlineCount] == null) inline[inlineCount] = new SqlBlockRow(budget);
    StatusCode status = inline[inlineCount].copyFrom(candidate);
    if (status.isOk()) inlineCount++;
    return status;
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
      if (spilled) {
        StatusCode status = rows.finish();
        if (status.isOk()) status = countDistinct();
        if (!status.isOk()) return status;
      } else distinctCount = inlineCount;
      finished = true;
    }
    result[0] = distinctCount;
    return StatusCode.OK;
  }

  StatusCode rewindFinal() {
    if (!finished) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (spilled) rows.rewind();
    else inlineRead = 0;
    finalPresent = false;
    return StatusCode.OK;
  }

  StatusCode readFinal(SqlBlockRow destination) {
    if (!finished || destination == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (!spilled) return inlineRead >= inlineCount ? StatusCode.CONFLICT
        : destination.copyFrom(inline[inlineRead++]);
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
    StatusCode status = resetRows();
    return status.isOk() && key.isText() ? prepareTextRows() : status;
  }

  private StatusCode resetRows() {
    StatusCode status = candidate.reset(1);
    if (status.isOk()) status = probe.reset(1);
    if (status.isOk()) status = last.reset(1);
    if (status.isOk()) status = copied.reset(1);
    return status;
  }

  private StatusCode prepareTextRows() {
    StatusCode status = candidate.prepareText(0);
    if (status.isOk()) status = probe.prepareText(0);
    if (status.isOk()) status = last.prepareText(0);
    if (status.isOk()) status = copied.prepareText(0);
    return status;
  }

  private void resetState() {
    for (int index = 0; index < inlineCount; index++) inline[index].reset(0);
    distinctCount = 0;
    inlineCount = 0;
    inlineRead = 0;
    spilled = false;
    finished = false;
    finalPresent = false;
  }
}
