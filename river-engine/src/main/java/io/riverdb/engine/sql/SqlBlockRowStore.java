package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.error.StatusDetail;
import io.riverdb.engine.runtime.materialized.SqlMaterializedPagedByteStream;
import io.riverdb.engine.runtime.materialized.SqlMaterializedScratchFileKind;

/** Statement-owned paged row store with stable external ordering. */
final class SqlBlockRowStore {
  private final SqlBlockRowCodec codec;
  private final SqlBlockRowSortKeyCodec sortKey;
  private final SqlBlockRowExternalOrder order;
  private final SqlBlockRowPagedIndexRecord indexRecord = new SqlBlockRowPagedIndexRecord();
  private final SqlBlockRowOrdinalStream.Result storedResult =
      new SqlBlockRowOrdinalStream.Result();
  private final SqlMaterializedPagedByteStream.AppendResult append =
      new SqlMaterializedPagedByteStream.AppendResult();
  private final SqlMaterializedPagedByteStream.Result opened =
      new SqlMaterializedPagedByteStream.Result();
  private final StatusDetail detail = new StatusDetail(160);
  private final SqlMaterializedStatement materialized;
  private SqlMaterializedPagedByteStream rows;
  private SqlMaterializedPagedByteStream index;
  private SqlMaterializedPagedByteStream keys;
  private SqlBlockSchema schema;
  private long rowCount;
  private long next;
  private long readLimit = Long.MAX_VALUE;
  private StatusCode terminal = StatusCode.OK;

  SqlBlockRowStore() { this(null); }

  SqlBlockRowStore(SqlSessionShapeBudget shapeBudget) {
    materialized = shapeBudget == null ? null : shapeBudget.materialized();
    codec = new SqlBlockRowCodec(shapeBudget);
    sortKey = new SqlBlockRowSortKeyCodec(shapeBudget);
    order = new SqlBlockRowExternalOrder(shapeBudget);
  }

  StatusCode begin(SqlBlockSchema rowSchema, int keyColumn, boolean descendingOrder) {
    StatusCode validation = validateSchema(rowSchema);
    if (!validation.isOk()) return validation;
    if (keyColumn < -1 || keyColumn >= rowSchema.count()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = resetForBegin();
    if (!status.isOk()) return status;
    if (!sortKey.beginSingle(rowSchema, keyColumn, descendingOrder)) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    schema = rowSchema;
    return openStreams();
  }

  StatusCode begin(
      SqlBlockSchema rowSchema, int[] keyColumns, boolean[] descendingOrder, int keyCount) {
    StatusCode validation = validateSchema(rowSchema);
    if (!validation.isOk()) return validation;
    StatusCode status = resetForBegin();
    if (!status.isOk()) return status;
    if (!sortKey.beginTuple(rowSchema, keyColumns, descendingOrder, keyCount)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    schema = rowSchema;
    return openStreams();
  }

  StatusCode append(SqlBlockRow source) {
    if (!usable() || source == null || source.count() != schema.count()) {
      return terminal.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : terminal;
    }
    if (rowCount >= Long.MAX_VALUE / SqlBlockRowPagedIndexRecord.BYTES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = codec.encode(source, schema, rowCount);
    if (status.isOk() && sortKey.sorted()) status = sortKey.encode(source);
    if (!status.isOk()) return status;
    status = rows.append(codec.buffer(), append, detail);
    if (!status.isOk()) return fail(status);
    long rowOffset = append.offset();
    int rowLength = append.bytes();
    long keyOffset = 0;
    int keyLength = 0;
    int flags = 0;
    if (sortKey.sorted()) {
      status = keys.append(sortKey.bytes(), append, detail);
      if (!status.isOk()) return fail(status);
      keyOffset = append.offset();
      keyLength = append.bytes();
      flags = SqlBlockRowPagedIndexRecord.FLAG_KEY_PRESENT;
    }
    status = indexRecord.encode(
        rowOffset, rowLength, source.key(), rowCount, keyOffset, keyLength, flags);
    if (status.isOk()) status = index.append(indexRecord.bytes(), append, detail);
    if (!status.isOk()) return fail(status);
    rowCount++;
    return StatusCode.OK;
  }

  StatusCode finish() {
    if (!usable()) return terminal.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : terminal;
    StatusCode status = sortKey.sorted()
        ? order.build(materialized, index, keys, sortKey, rowCount)
        : StatusCode.OK;
    if (!status.isOk()) return fail(status);
    next = 0;
    return StatusCode.OK;
  }

  StatusCode limit(long maximumRows) {
    if (schema == null || maximumRows < 0) return StatusCode.INVALID_EXTERNAL_INPUT;
    readLimit = maximumRows;
    if (next > rowCount()) next = rowCount();
    return StatusCode.OK;
  }

  StatusCode next(SqlBlockRow destination) {
    if (schema == null || destination == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    if (next >= rowCount()) return StatusCode.CONFLICT;
    StatusCode status = readAt(next, destination);
    if (status.isOk()) next++;
    return status;
  }

  StatusCode readAt(long position, SqlBlockRow destination) {
    if (!usable() || destination == null || position < 0 || position >= rowCount()) {
      return terminal.isOk() ? StatusCode.INVALID_EXTERNAL_INPUT : terminal;
    }
    StatusCode status = storedPosition(position, storedResult);
    if (!status.isOk()) return status;
    long ordinal = storedResult.value();
    status = readIndex(ordinal);
    if (status.isOk()) status = codec.prepareRead(indexRecord.rowLength());
    if (status.isOk()) status = rows.read(indexRecord.rowOffset(), codec.buffer(), detail);
    if (status.isOk()) {
      codec.buffer().flip();
      status = codec.decode(destination, schema, ordinal);
    }
    if (status.isOk()) destination.setKey(indexRecord.rowKey());
    return status;
  }

  StatusCode readAt(int position, SqlBlockRow destination) {
    return readAt((long) position, destination);
  }

  void rewind() { next = 0; }

  long rowCount() { return Math.min(rowCount, readLimit); }

  boolean spilled() { return rows != null; }
  boolean hasResources() { return schema != null || rows != null || index != null || keys != null; }

  StatusCode clearForReuse() {
    if (schema == null) return StatusCode.INVALID_EXTERNAL_INPUT;
    StatusCode status = closeStreams(false);
    if (!status.isOk()) return status;
    codec.reset();
    sortKey.reset();
    rowCount = 0;
    next = 0;
    readLimit = Long.MAX_VALUE;
    terminal = StatusCode.OK;
    return openStreams();
  }

  StatusCode close() {
    StatusCode status = closeStreams(true);
    codec.close();
    sortKey.close();
    schema = null;
    rowCount = 0;
    next = 0;
    readLimit = Long.MAX_VALUE;
    terminal = StatusCode.OK;
    return status;
  }

  private StatusCode openStreams() {
    if (materialized == null) {
      schema = null;
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = open(SqlMaterializedScratchFileKind.ROWS, 0);
    if (status.isOk()) rows = opened.stream();
    if (status.isOk()) status = open(
        SqlMaterializedScratchFileKind.INDEX, SqlBlockRowPagedIndexRecord.BYTES);
    if (status.isOk()) index = opened.stream();
    if (status.isOk() && sortKey.sorted()) {
      status = open(SqlMaterializedScratchFileKind.KEYS, 0);
      if (status.isOk()) keys = opened.stream();
    }
    if (!status.isOk()) {
      terminal = status;
      closeStreams(false);
      schema = null;
      return status;
    }
    rowCount = 0;
    next = 0;
    readLimit = Long.MAX_VALUE;
    terminal = StatusCode.OK;
    return StatusCode.OK;
  }

  private StatusCode open(SqlMaterializedScratchFileKind kind, int fixedBytes) {
    return materialized.openStream(kind, fixedBytes, 0, opened, detail);
  }

  private StatusCode readIndex(long ordinal) {
    if (ordinal < 0 || ordinal > Long.MAX_VALUE / SqlBlockRowPagedIndexRecord.BYTES) {
      return StatusCode.CORRUPTION;
    }
    indexRecord.prepareRead();
    StatusCode status = index.read(
        ordinal * SqlBlockRowPagedIndexRecord.BYTES, indexRecord.bytes(), detail);
    if (status.isOk()) status = indexRecord.validate(ordinal);
    if (status.isOk()) status = indexRecord.validateRowBounds(rows.logicalLength());
    if (status.isOk() && sortKey.sorted()) {
      status = indexRecord.validateKeyBounds(keys.logicalLength());
    }
    return status;
  }

  StatusCode storedPosition(
      long position, SqlBlockRowOrdinalStream.Result target) {
    if (!sortKey.sorted()) {
      target.value = position;
      return StatusCode.OK;
    }
    return order.stored(position, rowCount, target);
  }

  private StatusCode closeStreams(boolean finalClose) {
    StatusCode status = finalClose ? order.close() : order.clearForReuse();
    status = close(rows, status);
    status = close(index, status);
    status = close(keys, status);
    if (status.isOk()) {
      rows = null;
      index = null;
      keys = null;
    }
    return status;
  }

  private StatusCode resetForBegin() {
    StatusCode status = closeStreams(false);
    if (!status.isOk()) return status;
    codec.reset();
    sortKey.reset();
    schema = null;
    rowCount = 0;
    next = 0;
    readLimit = Long.MAX_VALUE;
    terminal = StatusCode.OK;
    return StatusCode.OK;
  }

  private StatusCode close(
      SqlMaterializedPagedByteStream stream, StatusCode prior) {
    if (stream == null) return prior;
    StatusCode status = stream.close(detail);
    return prior.isOk() ? status : prior;
  }

  private StatusCode fail(StatusCode status) {
    terminal = status;
    return status;
  }

  private boolean usable() {
    return schema != null && rows != null && index != null && terminal.isOk();
  }

  private static StatusCode validateSchema(SqlBlockSchema schema) {
    return schema == null ? StatusCode.INVALID_EXTERNAL_INPUT : schema.status();
  }
}
