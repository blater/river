package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.text.Utf8Text;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.sql.SqlComparison;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Owns bounded cursors, row copies, and value sets used by nested queries. */
final class SqlNestedQueryExecution {
  static final int MAXIMUM_MEMBERSHIP_VALUES = 1_024;
  private static final int NULL_PROJECTION = BoundSqlStatement.NULL_PROJECTION;
  private static final int NESTED_SCALAR = 1;
  private static final int NESTED_EXISTENCE = 2;
  private static final int NESTED_MEMBERSHIP = 3;

  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final BoundSqlQuery.Block command;
  private final BoundSqlQuery query;
  private final SqlExpressionEvaluator expressions;

  private final int[] scalarPredicateColumns =
      new int[BoundSqlQuery.MAXIMUM_PREDICATES];
  private final int[] scalarPredicateValueColumns =
      new int[BoundSqlQuery.MAXIMUM_PREDICATES];
  private final boolean[] scalarPredicateValueOuter =
      new boolean[BoundSqlQuery.MAXIMUM_PREDICATES];
  private final TableDefinition[] recursiveTables =
      new TableDefinition[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final RelationalScanCursor[] recursiveCursors =
      new RelationalScanCursor[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final RelationalScanResult[] recursiveRows =
      new RelationalScanResult[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final ByteBuffer[] recursiveRowBuffers =
      new ByteBuffer[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final HeapRowResult[] recursiveRowCopies =
      new HeapRowResult[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final long[] recursiveKeys =
      new long[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final int[] recursiveProjections =
      new int[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final int[] recursivePredicateColumns = new int[
      BoundSqlQuery.MAXIMUM_BLOCKS * BoundSqlQuery.MAXIMUM_PREDICATES];
  private final int[] recursivePredicateValueColumns = new int[
      BoundSqlQuery.MAXIMUM_BLOCKS * BoundSqlQuery.MAXIMUM_PREDICATES];
  private final int[] recursivePredicateValueScopes = new int[
      BoundSqlQuery.MAXIMUM_BLOCKS * BoundSqlQuery.MAXIMUM_PREDICATES];
  private final boolean[] recursiveScalarNulls =
      new boolean[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final long[] recursiveScalarValues =
      new long[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final int[] recursiveResultTypes =
      new int[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final boolean[] dynamicScalarNulls =
      new boolean[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final long[] dynamicScalarValues =
      new long[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final int[] dynamicScalarTypes =
      new int[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final boolean[] recursiveExistenceResults =
      new boolean[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final int[] recursiveMembershipCounts =
      new int[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final boolean[] recursiveMembershipNulls =
      new boolean[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final long[] membershipValues = new long[MAXIMUM_MEMBERSHIP_VALUES];
  private final long[] membershipScratchValues =
      new long[MAXIMUM_MEMBERSHIP_VALUES];
  // Each membership bank owns copied UTF-8 bytes until that bank is reused by
  // the next nested evaluation. No handle below refers to a relational scan row.
  private final ByteBuffer membershipText = ByteBuffer.allocateDirect(
      MAXIMUM_MEMBERSHIP_VALUES * Utf8Text.MAXIMUM_BYTES);
  private final ByteBuffer membershipScratchText = ByteBuffer.allocateDirect(
      MAXIMUM_MEMBERSHIP_VALUES * Utf8Text.MAXIMUM_BYTES);
  private final RelationalScanCursor scalarCursor = new RelationalScanCursor();
  private final RelationalScanResult scalarRow = new RelationalScanResult();
  private final TableDefinition parentResultTable = new TableDefinition();
  // The outer copy remains valid through both predicate phases for one outer row
  // and is overwritten only by the next evaluateBeforePredicates call.
  private final HeapRowResult correlatedOuterRow = new HeapRowResult();
  private final ByteBuffer correlatedOuterBuffer = ByteBuffer.allocateDirect(
      TableSchema.MAXIMUM_ROW_BYTES);
  private long[] recursiveMembershipValues;
  private ByteBuffer recursiveMembershipText;
  private int[] recursiveMembershipTextUsed;
  private boolean subqueryPredicateFalse;
  private boolean membershipHasNull;
  private boolean nestedCorrelated;
  private boolean correlatedScalar;
  private boolean correlatedExistence;
  private boolean correlatedMembership;
  private boolean correlatedNestedChain;
  private boolean recursiveNestedChain;
  private boolean recursiveRootCorrelated;
  private boolean existenceResult;
  private boolean scalarResultNull;
  private boolean outerCopied;
  private long scalarResultValue;
  private int membershipCandidateBank;
  private int membershipCandidateDepth = -1;
  private int membershipCandidateOffset;
  private int nestedProjection;
  private int nestedProjectionType;
  private int membershipValueCount;
  private int membershipTextUsed;
  private int membershipScratchTextUsed;
  private int membershipType;
  private int membershipScratchType;

  SqlNestedQueryExecution(
      RelationalSession relationalSession,
      BoundSqlStatement boundStatement,
      SqlExpressionEvaluator evaluator) {
    session = relationalSession;
    bound = boundStatement;
    query = bound.executableQuery;
    command = query.root();
    expressions = evaluator;
  }

  StatusCode resetForStatement() {
    StatusCode status = close();
    if (!status.isOk()) {
      return status;
    }
    subqueryPredicateFalse = false;
    membershipValueCount = 0;
    membershipHasNull = false;
    membershipCandidateBank = 0;
    membershipCandidateDepth = -1;
    membershipCandidateOffset = 0;
    membershipTextUsed = 0;
    membershipScratchTextUsed = 0;
    membershipType = 0;
    membershipScratchType = 0;
    nestedCorrelated = false;
    correlatedScalar = false;
    correlatedExistence = false;
    correlatedMembership = false;
    correlatedNestedChain = false;
    recursiveNestedChain = false;
    recursiveRootCorrelated = false;
    outerCopied = false;
    for (int block = 0; block < dynamicScalarNulls.length; block++) {
      dynamicScalarNulls[block] = true;
      dynamicScalarValues[block] = 0;
      dynamicScalarTypes[block] = 0;
      recursiveResultTypes[block] = 0;
    }
    return StatusCode.OK;
  }

  StatusCode close() {
    StatusCode status = closeCursor(scalarCursor, scalarRow);
    for (int depth = 0;
        recursiveCursors[0] != null && depth < recursiveCursors.length;
        depth++) {
      StatusCode close = closeCursor(
          recursiveCursors[depth], recursiveRows[depth]);
      if (status.isOk() && !close.isOk()) {
        status = close;
      }
    }
    return status;
  }

  private StatusCode closeCursor(
      RelationalScanCursor cursor, RelationalScanResult row) {
    if (!cursor.isActive()) {
      return StatusCode.OK;
    }
    StatusCode status = session.closeScan(cursor);
    if (status.isOk()) {
      cursor.reset();
      row.reset();
    }
    return status;
  }

  StatusCode prepare(boolean explainOnly) {
    if (query.blockCount() > 2
        && (query.hasScalarPredicate()
            || query.hasExistencePredicate()
            || query.hasMembershipPredicate())) {
      return explainOnly ? StatusCode.OK : prepareNestedChain();
    }
    if (query.hasScalarPredicate()) {
      return explainOnly ? StatusCode.OK : evaluateScalarPredicate();
    }
    if (query.hasExistencePredicate()) {
      return explainOnly ? StatusCode.OK : evaluateExistencePredicate();
    }
    if (query.hasMembershipPredicate()) {
      return explainOnly ? StatusCode.OK : evaluateMembershipPredicate();
    }
    return StatusCode.OK;
  }

  StatusCode evaluateBeforePredicates(
      long primaryKey, HeapRowResult source) {
    outerCopied = false;
    if (correlatedScalar || correlatedExistence) {
      subqueryPredicateFalse = false;
    }
    StatusCode status = StatusCode.OK;
    if (recursiveNestedChain && recursiveRootCorrelated) {
      subqueryPredicateFalse = false;
      status = copyOuter(source);
      if (status.isOk()) {
        status = evaluateRecursiveChain(primaryKey, correlatedOuterRow);
      }
    }
    if (status.isOk() && !subqueryPredicateFalse && correlatedNestedChain) {
      subqueryPredicateFalse = false;
      membershipValueCount = 0;
      membershipHasNull = false;
      status = copyOuter(source);
      if (status.isOk()) {
        status = evaluateNestedChain(primaryKey, correlatedOuterRow);
      }
    }
    if (status.isOk() && !subqueryPredicateFalse && correlatedScalar) {
      status = copyOuter(source);
      if (status.isOk()) {
        status = evaluateCorrelatedScalar(primaryKey, correlatedOuterRow);
      }
    }
    if (status.isOk() && !subqueryPredicateFalse && correlatedMembership) {
      membershipValueCount = 0;
      membershipHasNull = false;
      status = copyOuter(source);
      if (status.isOk()) {
        status = evaluateCorrelatedMembership(primaryKey, correlatedOuterRow);
      }
    }
    return status;
  }

  StatusCode evaluateAfterPredicates(
      long primaryKey, HeapRowResult source) {
    if (!correlatedExistence) {
      return StatusCode.OK;
    }
    StatusCode status = copyOuter(source);
    return status.isOk()
        ? evaluateCorrelatedExistence(primaryKey, correlatedOuterRow) : status;
  }

  HeapRowResult evaluatedRow(HeapRowResult original) {
    return outerCopied ? correlatedOuterRow : original;
  }

  boolean rejectsOuterRow() {
    return subqueryPredicateFalse;
  }

  boolean matchesMembership(
      long value, HeapRowResult source, int column) {
    boolean equal = false;
    for (int candidate = 0; candidate < membershipValueCount; candidate++) {
      if (isTextType(membershipResultType(
              membershipCandidateBank, membershipCandidateDepth))
          ? membershipTextEquals(
              source,
              column,
              membershipCandidateBank,
              membershipCandidateDepth,
              membershipCandidateOffset + candidate)
          : value == membershipValue(
              membershipCandidateBank,
              membershipCandidateDepth,
              membershipCandidateOffset + candidate)) {
        equal = true;
        break;
      }
    }
    return equal != query.membershipNegated()
        && (equal || !membershipHasNull);
  }

  boolean matchesScalar(long value) {
    return !dynamicScalarNulls[0]
        && expressions.matchesComparison(
            value, command.comparison(query.scalarPredicate()), dynamicScalarValues[0]);
  }

  boolean correlatedScalar() {
    return correlatedScalar;
  }

  boolean correlatedExistence() {
    return correlatedExistence;
  }

  boolean correlatedNestedChain() {
    return correlatedNestedChain;
  }

  boolean recursiveNestedChain() {
    return recursiveNestedChain;
  }

  boolean recursiveRootCorrelated() {
    return recursiveRootCorrelated;
  }

  private StatusCode copyOuter(HeapRowResult source) {
    StatusCode status = copyCorrelatedOuterRow(source);
    if (status.isOk()) {
      outerCopied = true;
    }
    return status;
  }

  private long readColumn(
      long primaryKey, HeapRowResult source, int column) {
    return expressions.readColumn(primaryKey, source, column);
  }

  private boolean isNull(
      HeapRowResult source,
      TableDefinition definition,
      int column) {
    return expressions.isNull(source, definition, column);
  }

  private boolean matchesComparison(
      long actual, BoundSqlQuery.Block source, int predicate) {
    SqlComparison comparison = source.comparison(predicate);
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return actual >= source.predicateLowerInclusive(predicate)
          && actual < source.predicateUpperExclusive(predicate);
    }
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      boolean equal = false;
      for (int index = 0; index < source.literalMembershipCount(predicate); index++) {
        if (actual == source.literalMembershipValue(predicate, index)) {
          equal = true;
          break;
        }
      }
      return comparison == SqlComparison.IN
          ? equal : !equal && !source.literalMembershipHasNull(predicate);
    }
    return expressions.matchesComparison(
        actual, comparison, source.predicateValue(predicate));
  }

  private boolean matchesLiteralComparison(
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition,
      int column,
      BoundSqlQuery.Block predicateSource,
      int predicate) {
    if (!definition.isVarchar(column)) {
      return matchesComparison(
          readColumn(primaryKey, source, column), predicateSource, predicate);
    }
    SqlComparison comparison = predicateSource.comparison(predicate);
    if (comparison == SqlComparison.IN || comparison == SqlComparison.NOT_IN) {
      boolean equal = false;
      for (int index = 0;
          index < predicateSource.literalMembershipCount(predicate);
          index++) {
        if (compareText(
            source, column, predicateSource,
            predicateSource.literalMembershipValue(predicate, index)) == 0) {
          equal = true;
          break;
        }
      }
      return comparison == SqlComparison.IN
          ? equal : !equal && !predicateSource.literalMembershipHasNull(predicate);
    }
    if (comparison == SqlComparison.HALF_OPEN_RANGE) {
      return compareText(
              source, column, predicateSource,
              predicateSource.predicateLowerInclusive(predicate)) >= 0
          && compareText(
              source, column, predicateSource,
              predicateSource.predicateUpperExclusive(predicate)) < 0;
    }
    int compared = compareText(
        source, column, predicateSource, predicateSource.predicateValue(predicate));
    return switch (comparison) {
      case EQUAL -> compared == 0;
      case NOT_EQUAL -> compared != 0;
      case LESS_THAN -> compared < 0;
      case LESS_OR_EQUAL -> compared <= 0;
      case GREATER_THAN -> compared > 0;
      case GREATER_OR_EQUAL -> compared >= 0;
      case HALF_OPEN_RANGE, IN, NOT_IN -> false;
    };
  }

  private int compareText(
      HeapRowResult actual,
      int column,
      BoundSqlQuery.Block expected,
      long expectedHandle) {
    long actualHandle = actual.getLong((column - 1) * Long.BYTES);
    int actualOffset = (int) (actualHandle >>> 32);
    int actualLength = (int) actualHandle;
    int expectedLength = expected.textByteLength(expectedHandle);
    if (!validTextHandle(actual, actualOffset, actualLength)
        || expectedLength < 0) {
      return Integer.MIN_VALUE;
    }
    int common = Math.min(actualLength, expectedLength);
    for (int index = 0; index < common; index++) {
      int comparison = Integer.compare(
          Byte.toUnsignedInt(actual.getByte(actualOffset + index)),
          Byte.toUnsignedInt(expected.textByteAt(expectedHandle, index)));
      if (comparison != 0) {
        return comparison;
      }
    }
    return Integer.compare(actualLength, expectedLength);
  }

  private boolean equalColumns(
      long leftKey,
      HeapRowResult left,
      TableDefinition leftDefinition,
      int leftColumn,
      long rightKey,
      HeapRowResult right,
      TableDefinition rightDefinition,
      int rightColumn) {
    if (!leftDefinition.isVarchar(leftColumn)) {
      return readColumn(leftKey, left, leftColumn)
          == readColumn(rightKey, right, rightColumn);
    }
    long leftHandle = readColumn(leftKey, left, leftColumn);
    long rightHandle = readColumn(rightKey, right, rightColumn);
    int leftOffset = (int) (leftHandle >>> 32);
    int leftLength = (int) leftHandle;
    int rightOffset = (int) (rightHandle >>> 32);
    int rightLength = (int) rightHandle;
    if (!validTextHandle(left, leftOffset, leftLength)
        || !validTextHandle(right, rightOffset, rightLength)
        || leftLength != rightLength) {
      return false;
    }
    for (int index = 0; index < leftLength; index++) {
      if (left.getByte(leftOffset + index) != right.getByte(rightOffset + index)) {
        return false;
      }
    }
    return true;
  }

  private static boolean validTextHandle(
      HeapRowResult source, int offset, int length) {
    return offset >= 0 && length >= 0 && offset <= source.length() - length;
  }

  private static boolean isTextType(int descriptor) {
    return SqlTypeDescriptor.typeId(descriptor)
        == SqlTypeDescriptor.TYPE_ID_VARCHAR;
  }

  private static StatusCode validateRow(
      HeapRowResult source, TableDefinition definition) {
    return source.length() >= definition.fixedRowBytes()
            && source.length() <= definition.maximumRowBytes()
        ? StatusCode.OK : StatusCode.CORRUPTION;
  }

  private static boolean matchesTableQualifier(
      BoundSqlQuery.Block qualified, CharSequence name) {
    return sameName(qualified.tableName(), name)
        || qualified.tableAlias().length() > 0
            && sameName(qualified.tableAlias(), name);
  }

  private static boolean sameName(CharSequence left, CharSequence right) {
    if (left.length() != right.length()) {
      return false;
    }
    for (int index = 0; index < left.length(); index++) {
      if (left.charAt(index) != right.charAt(index)) {
        return false;
      }
    }
    return true;
  }

  RelationalScanCursor scalarCursor() {
    return scalarCursor;
  }

  RelationalScanResult scalarRow() {
    return scalarRow;
  }

  HeapRowResult correlatedOuterRow() {
    return correlatedOuterRow;
  }

  int scalarPredicateColumn(int predicate) {
    return scalarPredicateColumns[predicate];
  }

  void setScalarPredicateColumn(int predicate, int column) {
    scalarPredicateColumns[predicate] = column;
  }

  int scalarPredicateValueColumn(int predicate) {
    return scalarPredicateValueColumns[predicate];
  }

  boolean scalarPredicateValueOuter(int predicate) {
    return scalarPredicateValueOuter[predicate];
  }

  void setScalarPredicateValue(
      int predicate, int column, boolean outer) {
    scalarPredicateValueColumns[predicate] = column;
    scalarPredicateValueOuter[predicate] = outer;
  }

  TableDefinition recursiveTable(int depth) {
    return recursiveTables[depth];
  }

  RelationalScanCursor recursiveCursor(int depth) {
    return recursiveCursors[depth];
  }

  RelationalScanResult recursiveRow(int depth) {
    return recursiveRows[depth];
  }

  ByteBuffer recursiveRowBuffer(int depth) {
    return recursiveRowBuffers[depth];
  }

  HeapRowResult recursiveRowCopy(int depth) {
    return recursiveRowCopies[depth];
  }

  long recursiveKey(int depth) {
    return recursiveKeys[depth];
  }

  void setRecursiveKey(int depth, long key) {
    recursiveKeys[depth] = key;
  }

  int recursiveProjection(int depth) {
    return recursiveProjections[depth];
  }

  void setRecursiveProjection(int depth, int projection) {
    recursiveProjections[depth] = projection;
  }

  int recursivePredicateColumn(int slot) {
    return recursivePredicateColumns[slot];
  }

  int recursivePredicateValueColumn(int slot) {
    return recursivePredicateValueColumns[slot];
  }

  int recursivePredicateValueScope(int slot) {
    return recursivePredicateValueScopes[slot];
  }

  void setRecursivePredicate(
      int slot, int column, int valueColumn, int valueScope) {
    recursivePredicateColumns[slot] = column;
    recursivePredicateValueColumns[slot] = valueColumn;
    recursivePredicateValueScopes[slot] = valueScope;
  }

  boolean recursiveScalarNull(int depth) {
    return recursiveScalarNulls[depth];
  }

  long recursiveScalarValue(int depth) {
    return recursiveScalarValues[depth];
  }

  void resetRecursiveResult(int depth) {
    recursiveScalarNulls[depth] = true;
    recursiveScalarValues[depth] = 0;
    recursiveExistenceResults[depth] = false;
    recursiveMembershipCounts[depth] = 0;
    recursiveMembershipNulls[depth] = false;
    if (recursiveMembershipTextUsed != null) {
      recursiveMembershipTextUsed[depth] =
          depth * MAXIMUM_MEMBERSHIP_VALUES * Utf8Text.MAXIMUM_BYTES;
    }
  }

  void setRecursiveScalar(int depth, long value) {
    recursiveScalarNulls[depth] = false;
    recursiveScalarValues[depth] = value;
  }

  boolean recursiveExistence(int depth) {
    return recursiveExistenceResults[depth];
  }

  void setRecursiveExistence(int depth) {
    recursiveExistenceResults[depth] = true;
  }

  int recursiveMembershipCount(int depth) {
    return recursiveMembershipCounts[depth];
  }

  void setRecursiveMembershipCount(int depth, int count) {
    recursiveMembershipCounts[depth] = count;
  }

  boolean recursiveMembershipNull(int depth) {
    return recursiveMembershipNulls[depth];
  }

  void setRecursiveMembershipNull(int depth) {
    recursiveMembershipNulls[depth] = true;
  }

  long membershipValue(boolean scratch, int index) {
    return scratch ? membershipScratchValues[index] : membershipValues[index];
  }

  long membershipValue(int bank, int depth, int index) {
    if (bank == 2) {
      return recursiveMembershipValue(depth, index);
    }
    return membershipValue(bank == 1, index);
  }

  int membershipCapacity() {
    return MAXIMUM_MEMBERSHIP_VALUES;
  }

  void setMembershipValue(boolean scratch, int index, long value) {
    if (scratch) {
      membershipScratchValues[index] = value;
    } else {
      membershipValues[index] = value;
    }
  }

  private void resetMembershipOutput(int bank) {
    if (bank == 1) {
      membershipScratchTextUsed = 0;
      membershipScratchType = 0;
    } else {
      membershipTextUsed = 0;
      membershipType = 0;
    }
  }

  private void setMembershipOutputType(int bank, int type) {
    if (bank == 1) {
      membershipScratchType = type;
    } else {
      membershipType = type;
    }
  }

  private int membershipResultType(int bank, int depth) {
    return bank == 2 ? recursiveResultTypes[depth]
        : bank == 1 ? membershipScratchType : membershipType;
  }

  private StatusCode appendMembershipValue(
      int bank,
      int depth,
      int index,
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition,
      int projection) {
    if (!definition.isVarchar(projection)) {
      if (bank == 2) {
        setRecursiveMembershipValue(
            depth, index, readColumn(primaryKey, source, projection));
      } else {
        setMembershipValue(
            bank == 1, index, readColumn(primaryKey, source, projection));
      }
      return StatusCode.OK;
    }
    long sourceHandle = readColumn(primaryKey, source, projection);
    int sourceOffset = (int) (sourceHandle >>> 32);
    int sourceLength = (int) sourceHandle;
    ByteBuffer target = membershipTextBuffer(bank);
    int targetOffset = membershipTextUsed(bank, depth);
    if (!validTextHandle(source, sourceOffset, sourceLength)
        || targetOffset > target.capacity() - sourceLength) {
      return StatusCode.CORRUPTION;
    }
    for (int byteIndex = 0; byteIndex < sourceLength; byteIndex++) {
      target.put(targetOffset + byteIndex, source.getByte(sourceOffset + byteIndex));
    }
    long targetHandle = (long) targetOffset << 32
        | Integer.toUnsignedLong(sourceLength);
    if (bank == 2) {
      setRecursiveMembershipValue(depth, index, targetHandle);
      recursiveMembershipTextUsed[depth] = targetOffset + sourceLength;
    } else {
      setMembershipValue(bank == 1, index, targetHandle);
      if (bank == 1) {
        membershipScratchTextUsed = targetOffset + sourceLength;
      } else {
        membershipTextUsed = targetOffset + sourceLength;
      }
    }
    return StatusCode.OK;
  }

  private ByteBuffer membershipTextBuffer(int bank) {
    return bank == 2 ? recursiveMembershipText
        : bank == 1 ? membershipScratchText : membershipText;
  }

  private int membershipTextUsed(int bank, int depth) {
    return bank == 2 ? recursiveMembershipTextUsed[depth]
        : bank == 1 ? membershipScratchTextUsed : membershipTextUsed;
  }

  private boolean membershipTextEquals(
      HeapRowResult source,
      int column,
      int bank,
      int depth,
      int index) {
    long sourceHandle = readColumn(0, source, column);
    int sourceOffset = (int) (sourceHandle >>> 32);
    int sourceLength = (int) sourceHandle;
    long candidate = membershipValue(bank, depth, index);
    int candidateOffset = (int) (candidate >>> 32);
    int candidateLength = (int) candidate;
    ByteBuffer candidateBytes = membershipTextBuffer(bank);
    int used = membershipTextUsed(bank, depth);
    if (!validTextHandle(source, sourceOffset, sourceLength)
        || candidateOffset < 0
        || candidateLength < 0
        || candidateOffset > used - candidateLength) {
      return false;
    }
    if (sourceLength != candidateLength) {
      return false;
    }
    for (int byteIndex = 0; byteIndex < sourceLength; byteIndex++) {
      if (source.getByte(sourceOffset + byteIndex)
          != candidateBytes.get(candidateOffset + byteIndex)) {
        return false;
      }
    }
    return true;
  }

  long recursiveMembershipValue(int depth, int index) {
    return recursiveMembershipValues[depth * MAXIMUM_MEMBERSHIP_VALUES + index];
  }

  void setRecursiveMembershipValue(int depth, int index, long value) {
    recursiveMembershipValues[depth * MAXIMUM_MEMBERSHIP_VALUES + index] = value;
  }

  void ensureRecursiveState() {
    if (recursiveMembershipValues == null) {
      recursiveMembershipValues = new long[
          BoundSqlQuery.MAXIMUM_BLOCKS * MAXIMUM_MEMBERSHIP_VALUES];
      recursiveMembershipText = ByteBuffer.allocateDirect(
          BoundSqlQuery.MAXIMUM_BLOCKS
              * MAXIMUM_MEMBERSHIP_VALUES * Utf8Text.MAXIMUM_BYTES);
      recursiveMembershipTextUsed = new int[BoundSqlQuery.MAXIMUM_BLOCKS];
    }
    for (int block = 0; block < recursiveTables.length; block++) {
      if (recursiveTables[block] == null) {
        recursiveTables[block] = new TableDefinition();
        recursiveCursors[block] = new RelationalScanCursor();
        recursiveRows[block] = new RelationalScanResult();
        recursiveRowBuffers[block] = ByteBuffer.allocateDirect(
            TableSchema.MAXIMUM_ROW_BYTES);
        recursiveRowCopies[block] = new HeapRowResult();
      }
    }
  }

  private StatusCode prepareNestedChain() {
    StatusCode status = StatusCode.OK;
    if (hasIntermediateReference()) {
      ensureRecursiveState();
      status = bindRecursiveChain();
    }
    if (!status.isOk()) {
      return status;
    }
    if (recursiveNestedChain) {
      return recursiveRootCorrelated
          ? StatusCode.OK : evaluateRecursiveChain(0, null);
    }
    boolean correlated = false;
    for (int block = query.blockCount() - 1;
        status.isOk() && block > 0;
        block--) {
      BoundSqlQuery.Block nested = query.block(block);
      if (nested == null || nested.isOrdered()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      status = bindNestedCommand(nested);
      correlated |= nestedCorrelated;
    }
    if (status.isOk() && correlated) {
      correlatedNestedChain = true;
      return StatusCode.OK;
    }
    return status.isOk() ? evaluateNestedChain(0, null) : status;
  }

  private boolean hasIntermediateReference() {
    for (int depth = 2; depth < query.blockCount(); depth++) {
      BoundSqlQuery.Block nested = query.block(depth);
      for (int index = 0; index < nested.predicateCount(); index++) {
        if (!nested.isColumnPredicate(index)) {
          continue;
        }
        CharSequence qualifier = nested.predicateValueTableName(index);
        if (matchesTableQualifier(nested, qualifier)) {
          continue;
        }
        for (int scope = depth - 1; scope > 0; scope--) {
          if (matchesTableQualifier(query.block(scope), qualifier)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private StatusCode bindRecursiveChain() {
    recursiveNestedChain = false;
    recursiveRootCorrelated = false;
    for (int depth = 1; depth < query.blockCount(); depth++) {
      BoundSqlQuery.Block nested = query.block(depth);
      if (nested == null || nested.isOrdered()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      TableDefinition definition = recursiveTable(depth);
      StatusCode status = session.resolveTable(nested.tableName(), definition);
      if (!status.isOk()) {
        return status;
      }
      if (nested.columnCount() != 1
          || nested.isSelectAll()
          || nested.columnTableName(0).length() > 0
              && !matchesTableQualifier(nested, nested.columnTableName(0))) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int projection = nested.isNullProjection(0)
          ? NULL_PROJECTION : definition.findColumn(nested.firstColumnName());
      if (projection < 0 && projection != NULL_PROJECTION) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      setRecursiveProjection(depth, projection);
      int parent = depth - 1;
      recursiveResultTypes[depth] = projection == NULL_PROJECTION
          ? 0 : definition.typeDescriptor(projection);
      TableDefinition parentDefinition = parent == 0
          ? bound.table : recursiveTable(parent);
      StatusCode edgeStatus = validateNestedResultEdge(
          parent, recursiveResultTypes[depth], parentDefinition);
      if (!edgeStatus.isOk()) {
        return edgeStatus;
      }
      int base = depth * BoundSqlQuery.MAXIMUM_PREDICATES;
      for (int index = 0; index < nested.predicateCount(); index++) {
        if (nested.predicateTableName(index).length() > 0
            && !matchesTableQualifier(
                nested, nested.predicateTableName(index))) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        int column = definition.findColumn(nested.predicateColumnName(index));
        if (column < 0
            || nested.isRangePredicate(index)
                && nested.predicateUpperExclusive(index)
                    <= nested.predicateLowerInclusive(index)) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        int slot = base + index;
        setRecursivePredicate(slot, column, -1, -1);
        if (nested.isColumnPredicate(index)) {
          int scope = recursiveScope(
              depth, nested.predicateValueTableName(index));
          if (scope < 0) {
            return StatusCode.INVALID_EXTERNAL_INPUT;
          }
          TableDefinition valueDefinition = scope == 0
              ? bound.table : recursiveTable(scope);
          int valueColumn = valueDefinition.findColumn(
              nested.predicateValueColumnName(index));
          if (valueColumn < 0) {
            return StatusCode.INVALID_EXTERNAL_INPUT;
          }
          if (!SqlTypeDescriptor.canCompare(
              definition.typeDescriptor(column),
              valueDefinition.typeDescriptor(valueColumn))) {
            return StatusCode.DATATYPE_MISMATCH;
          }
          setRecursivePredicate(
              slot, column, valueColumn, scope);
          recursiveRootCorrelated |= scope == 0;
          recursiveNestedChain |= scope > 0 && scope < depth;
        } else if (!nested.isNullPredicate(index)
            && index != query.membershipPredicate(depth)
            && !SqlTypeDescriptor.canCompare(
                definition.typeDescriptor(column),
                nested.predicateTypeDescriptor(index))) {
          return StatusCode.DATATYPE_MISMATCH;
        }
      }
    }
    return StatusCode.OK;
  }

  private StatusCode validateNestedResultEdge(
      int parent, int childType, TableDefinition parentDefinition) {
    boolean scalar = query.hasScalarPredicate(parent);
    boolean membership = query.hasMembershipPredicate(parent);
    if (!scalar && !membership) {
      return StatusCode.OK;
    }
    BoundSqlQuery.Block parentCommand = parent == 0 ? command : query.block(parent);
    int predicate = scalar
        ? query.scalarPredicate(parent) : query.membershipPredicate(parent);
    int parentColumn = predicate < 0 ? -1 : parentDefinition.findColumn(
        parentCommand.predicateColumnName(predicate));
    if (parentColumn < 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (childType != 0
        && !SqlTypeDescriptor.canCompare(
            parentDefinition.typeDescriptor(parentColumn), childType)) {
      return StatusCode.DATATYPE_MISMATCH;
    }
    // Text scalar results need an owned typed scalar carrier; membership owns
    // UTF-8 bytes already, while scalar storage is currently primitive-only.
    return scalar && isTextType(childType)
        ? StatusCode.DATATYPE_MISMATCH : StatusCode.OK;
  }

  private int recursiveScope(int depth, CharSequence qualifier) {
    if (qualifier.length() == 0) {
      return -1;
    }
    BoundSqlQuery.Block local = query.block(depth);
    if (matchesTableQualifier(local, qualifier)) {
      return depth;
    }
    for (int scope = depth - 1; scope > 0; scope--) {
      if (matchesTableQualifier(query.block(scope), qualifier)) {
        return scope;
      }
    }
    return matchesTableQualifier(command, qualifier) ? 0 : -1;
  }

  private StatusCode evaluateRecursiveChain(
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    int resultKind = query.hasScalarPredicate()
        ? NESTED_SCALAR
        : query.hasExistencePredicate()
            ? NESTED_EXISTENCE
            : query.hasMembershipPredicate() ? NESTED_MEMBERSHIP : 0;
    if (resultKind == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = evaluateRecursiveBlock(
        1, resultKind, outerPrimaryKey, outerSource);
    if (!status.isOk()) {
      return status;
    }
    if (resultKind == NESTED_SCALAR) {
      subqueryPredicateFalse = recursiveScalarNull(1);
      if (!subqueryPredicateFalse) {
        dynamicScalarNulls[0] = false;
        dynamicScalarValues[0] = recursiveScalarValue(1);
        dynamicScalarTypes[0] = recursiveResultTypes[1];
      }
    } else if (resultKind == NESTED_EXISTENCE) {
      subqueryPredicateFalse = query.existenceNegated()
          ? recursiveExistence(1)
          : !recursiveExistence(1);
    } else {
      subqueryPredicateFalse = false;
      membershipCandidateBank = 2;
      membershipCandidateDepth = 1;
      membershipCandidateOffset = 0;
      membershipValueCount = recursiveMembershipCount(1);
      membershipHasNull = recursiveMembershipNull(1);
    }
    return status;
  }

  private StatusCode evaluateRecursiveBlock(
      int depth,
      int resultKind,
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    resetRecursiveResult(depth);
    BoundSqlQuery.Block nested = query.block(depth);
    if (nested.rowLimit() == 0) {
      return StatusCode.OK;
    }
    TableDefinition definition = recursiveTable(depth);
    RelationalScanCursor cursor = recursiveCursor(depth);
    RelationalScanResult rowResult = recursiveRow(depth);
    StatusCode status = session.beginScan(definition, cursor);
    boolean cursorActive = status.isOk();
    long matchedRows = 0;
    while (status.isOk()) {
      status = session.nextScan(cursor, rowResult);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      HeapRowResult source = rowResult.row();
      long primaryKey = rowResult.key();
      if (status.isOk()) {
        status = validateRow(source, definition);
      }
      if (status.isOk()
          && !matchesRecursivePredicates(
              depth, primaryKey, source, outerPrimaryKey, outerSource)) {
        continue;
      }
      if (status.isOk() && depth + 1 < query.blockCount()) {
        status = copyRecursiveRow(depth, primaryKey, source);
        if (status.isOk()) {
          source = recursiveRowCopy(depth);
          int childKind = recursiveResultKind(depth);
          status = childKind == 0
              ? StatusCode.INVALID_EXTERNAL_INPUT
              : evaluateRecursiveBlock(
                  depth + 1, childKind, outerPrimaryKey, outerSource);
        }
        if (status.isOk()
            && !matchesRecursiveChild(depth, primaryKey, source)) {
          continue;
        }
      }
      if (!status.isOk()) {
        break;
      }
      matchedRows++;
      int projection = recursiveProjection(depth);
      if (resultKind == NESTED_EXISTENCE) {
        setRecursiveExistence(depth);
        break;
      }
      if (resultKind == NESTED_SCALAR) {
        if (matchedRows > 1) {
          status = StatusCode.CARDINALITY_VIOLATION;
        } else if (projection != NULL_PROJECTION
            && !isNull(source, definition, projection)) {
          setRecursiveScalar(
              depth, readColumn(primaryKey, source, projection));
        }
      } else if (projection == NULL_PROJECTION
          || isNull(source, definition, projection)) {
        setRecursiveMembershipNull(depth);
      } else {
        int count = recursiveMembershipCount(depth);
        if (count >= MAXIMUM_MEMBERSHIP_VALUES) {
          status = StatusCode.RESOURCE_EXHAUSTED;
        } else {
          status = appendMembershipValue(
              2, depth, count, primaryKey, source, definition, projection);
          if (status.isOk()) {
            setRecursiveMembershipCount(depth, count + 1);
          }
        }
      }
      if (status.isOk() && matchedRows >= nested.rowLimit()) {
        break;
      }
    }
    if (cursorActive) {
      StatusCode close = session.closeScan(cursor);
      if (close.isOk()) {
        cursor.reset();
        rowResult.reset();
      }
      if (status.isOk()) {
        status = close;
      }
    }
    return status;
  }

  private int recursiveResultKind(int block) {
    return query.hasScalarPredicate(block)
        ? NESTED_SCALAR
        : query.hasExistencePredicate(block)
            ? NESTED_EXISTENCE
            : query.hasMembershipPredicate(block) ? NESTED_MEMBERSHIP : 0;
  }

  private StatusCode copyRecursiveRow(
      int depth,
      long primaryKey,
      HeapRowResult source) {
    ByteBuffer target = recursiveRowBuffer(depth);
    if (source.length() > target.capacity()) {
      return StatusCode.CORRUPTION;
    }
    target.clear();
    target.limit(source.length());
    StatusCode status = source.copyTo(target);
    if (status.isOk()) {
      target.position(0);
      recursiveRowCopy(depth).set(
          target, 0, 0, source.length());
      setRecursiveKey(depth, primaryKey);
    }
    return status;
  }

  private boolean matchesRecursivePredicates(
      int depth,
      long primaryKey,
      HeapRowResult source,
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    BoundSqlQuery.Block nested = query.block(depth);
    TableDefinition definition = recursiveTable(depth);
    int skipped = query.hasScalarPredicate(depth)
        ? query.scalarPredicate(depth)
        : query.hasMembershipPredicate(depth)
            ? query.membershipPredicate(depth) : -1;
    int base = depth * BoundSqlQuery.MAXIMUM_PREDICATES;
    for (int index = 0; index < nested.predicateCount(); index++) {
      if (index == skipped) {
        continue;
      }
      int slot = base + index;
      int column = recursivePredicateColumn(slot);
      long value = readColumn(primaryKey, source, column);
      boolean nullValue = isNull(source, definition, column);
      if (nested.isNullPredicate(index)) {
        if (nullValue == nested.isNullPredicateNegated(index)) {
          return false;
        }
        continue;
      }
      if (nullValue) {
        return false;
      }
      if (nested.isColumnPredicate(index)) {
        int scope = recursivePredicateValueScope(slot);
        int valueColumn = recursivePredicateValueColumn(slot);
        HeapRowResult valueSource = scope == 0
            ? outerSource : scope == depth
                ? source : recursiveRowCopy(scope);
        TableDefinition valueDefinition = scope == 0
            ? bound.table : recursiveTable(scope);
        long valueKey = scope == 0
            ? outerPrimaryKey : scope == depth
                ? primaryKey : recursiveKey(scope);
        if (valueSource == null
            || isNull(valueSource, valueDefinition, valueColumn)
            || !equalColumns(
                primaryKey,
                source,
                definition,
                column,
                valueKey,
                valueSource,
                valueDefinition,
                valueColumn)) {
          return false;
        }
        continue;
      }
      if (!matchesLiteralComparison(
          primaryKey, source, definition, column, nested, index)) {
        return false;
      }
    }
    return true;
  }

  private boolean matchesRecursiveChild(
      int depth,
      long primaryKey,
      HeapRowResult source) {
    int child = depth + 1;
    if (query.hasExistencePredicate(depth)) {
      boolean exists = recursiveExistence(child);
      return query.existenceNegated(depth) ? !exists : exists;
    }
    int predicate = query.hasScalarPredicate(depth)
        ? query.scalarPredicate(depth) : query.membershipPredicate(depth);
    int column = recursivePredicateColumn(
        depth * BoundSqlQuery.MAXIMUM_PREDICATES + predicate);
    TableDefinition definition = recursiveTable(depth);
    if (isNull(source, definition, column)) {
      return false;
    }
    long value = readColumn(primaryKey, source, column);
    if (query.hasScalarPredicate(depth)) {
      return !recursiveScalarNull(child)
          && SqlTypeDescriptor.canCompare(
              definition.typeDescriptor(column), recursiveResultTypes[child])
          && expressions.matchesComparison(
              value,
              query.block(depth).comparison(predicate),
              recursiveScalarValue(child));
    }
    int count = recursiveMembershipCount(child);
    boolean equal = false;
    for (int index = 0; index < count; index++) {
      if (isTextType(recursiveResultTypes[child])
          ? membershipTextEquals(source, column, 2, child, index)
          : value == recursiveMembershipValue(child, index)) {
        equal = true;
        break;
      }
    }
    return equal != query.membershipNegated(depth)
        && (equal || !recursiveMembershipNull(child));
  }

  private StatusCode evaluateNestedChain(
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    StatusCode status = StatusCode.OK;
    boolean commandEnabled = true;
    int candidates = 0;
    int candidateCount = 0;
    boolean candidateHasNull = false;
    for (int block = query.blockCount() - 1;
        status.isOk() && block > 0;
        block--) {
      BoundSqlQuery.Block nested = query.block(block);
      if (nested == null || nested.isOrdered()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      status = bindNestedCommand(nested);
      if (status.isOk() && nestedCorrelated && outerSource == null) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int parent = block - 1;
      int resultKind = query.hasScalarPredicate(parent)
          ? NESTED_SCALAR
          : query.hasExistencePredicate(parent)
              ? NESTED_EXISTENCE
              : query.hasMembershipPredicate(parent)
                  ? NESTED_MEMBERSHIP : 0;
      if (status.isOk() && resultKind == 0) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
      }
      int output = candidates == 0 ? 1 : 0;
      membershipValueCount = 0;
      membershipHasNull = false;
      resetMembershipOutput(output);
      if (status.isOk()) {
        status = evaluateNestedRows(
            nested,
            commandEnabled,
            resultKind,
            output,
            outerPrimaryKey,
            outerSource,
            query.membershipPredicate(block),
            query.membershipNegated(block),
            candidates,
            candidateCount,
            candidateHasNull);
      }
      if (!status.isOk()) {
        break;
      }
      if (resultKind == NESTED_SCALAR) {
        commandEnabled = !scalarResultNull;
        if (commandEnabled) {
          dynamicScalarNulls[parent] = false;
          dynamicScalarValues[parent] = scalarResultValue;
          dynamicScalarTypes[parent] = nestedProjectionType;
        }
      } else if (resultKind == NESTED_EXISTENCE) {
        commandEnabled = query.existenceNegated(parent)
            ? !existenceResult : existenceResult;
      } else {
        commandEnabled = true;
        setMembershipOutputType(output, nestedProjectionType);
        candidates = output;
        candidateCount = membershipValueCount;
        candidateHasNull = membershipHasNull;
      }
    }
    if (status.isOk()) {
      subqueryPredicateFalse = !commandEnabled;
      if (query.hasMembershipPredicate()) {
        membershipCandidateBank = candidates;
        membershipCandidateDepth = -1;
        membershipValueCount = candidateCount;
        membershipHasNull = candidateHasNull;
      }
    }
    return status;
  }

  private StatusCode evaluateNestedRows(
      BoundSqlQuery.Block nested,
      boolean commandEnabled,
      int resultKind,
      int outputBank,
      long outerPrimaryKey,
      HeapRowResult outerSource,
      int nestedMembershipPredicate,
      boolean nestedMembershipNegated,
      int inputBank,
      int inputCount,
      boolean inputHasNull) {
    scalarResultNull = true;
    scalarResultValue = 0;
    existenceResult = false;
    if (resultKind == NESTED_MEMBERSHIP) {
      setMembershipOutputType(outputBank, nestedProjectionType);
    }
    if (!commandEnabled || nested.rowLimit() == 0) {
      return StatusCode.OK;
    }
    StatusCode status = session.beginScan(bound.scalarTable, scalarCursor);
    boolean cursorActive = status.isOk();
    long matchedRows = 0;
    while (status.isOk()) {
      status = session.nextScan(scalarCursor, scalarRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) {
        status = validateRow(scalarRow.row(), bound.scalarTable);
      }
      if (status.isOk()
          && !matchesScalarPredicates(
              nested,
              scalarRow.key(),
              scalarRow.row(),
              outerPrimaryKey,
              outerSource,
              nestedMembershipPredicate,
              nestedMembershipNegated,
              inputBank,
              inputCount,
              inputHasNull)) {
        continue;
      }
      if (!status.isOk()) {
        break;
      }
      matchedRows++;
      if (resultKind == NESTED_EXISTENCE) {
        existenceResult = true;
        break;
      }
      if (resultKind == NESTED_SCALAR) {
        if (matchedRows > 1) {
          status = StatusCode.CARDINALITY_VIOLATION;
        } else if (nestedProjection != NULL_PROJECTION
            && !isNull(scalarRow.row(), bound.scalarTable, nestedProjection)) {
          scalarResultNull = false;
          scalarResultValue = readColumn(
              scalarRow.key(), scalarRow.row(), nestedProjection);
        }
      } else if (nestedProjection == NULL_PROJECTION
          || isNull(scalarRow.row(), bound.scalarTable, nestedProjection)) {
        membershipHasNull = true;
      } else if (membershipValueCount
          >= membershipCapacity()) {
        status = StatusCode.RESOURCE_EXHAUSTED;
      } else {
        status = appendMembershipValue(
            outputBank,
            -1,
            membershipValueCount,
            scalarRow.key(),
            scalarRow.row(),
            bound.scalarTable,
            nestedProjection);
        if (status.isOk()) {
          membershipValueCount++;
        }
      }
      if (status.isOk() && matchedRows >= nested.rowLimit()) {
        break;
      }
    }
    if (cursorActive) {
      StatusCode close = session.closeScan(scalarCursor);
      if (close.isOk()) {
        scalarCursor.reset();
        scalarRow.reset();
      }
      if (status.isOk()) {
        status = close;
      }
    }
    return status;
  }

  private StatusCode evaluateScalarPredicate() {
    StatusCode status = StatusCode.OK;
    for (int block = query.blockCount() - 1;
        status.isOk() && block > 0 && !subqueryPredicateFalse;
        block--) {
      BoundSqlQuery.Block scalar = query.block(block);
      if (scalar == null || scalar.isOrdered()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      status = bindNestedCommand(scalar);
      if (status.isOk() && nestedCorrelated) {
        if (query.blockCount() != 2) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        correlatedScalar = true;
        return StatusCode.OK;
      }
      if (status.isOk()) {
        status = evaluateScalarRows(
            scalar, 0, null, block - 1);
      }
    }
    return status;
  }

  private StatusCode evaluateCorrelatedScalar(
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    BoundSqlQuery.Block scalar = query.scalarCommand();
    return scalar == null
        ? StatusCode.INVALID_EXTERNAL_INPUT
        : evaluateScalarRows(
            scalar, outerPrimaryKey, outerSource, 0);
  }

  private StatusCode evaluateScalarRows(
      BoundSqlQuery.Block scalar,
      long outerPrimaryKey,
      HeapRowResult outerSource,
      int destinationBlock) {
    StatusCode status = StatusCode.OK;
    if (scalar.rowLimit() == 0) {
      subqueryPredicateFalse = true;
      return StatusCode.OK;
    }
    status = session.beginScan(bound.scalarTable, scalarCursor);
    boolean cursorActive = status.isOk();
    int rows = 0;
    long value = 0;
    while (status.isOk()) {
      status = session.nextScan(scalarCursor, scalarRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) {
        status = validateRow(scalarRow.row(), bound.scalarTable);
      }
      if (status.isOk()
          && !matchesScalarPredicates(
              scalar,
              scalarRow.key(),
              scalarRow.row(),
              outerPrimaryKey,
              outerSource,
              -1,
              false,
              1,
              0,
              false)) {
        continue;
      }
      if (status.isOk()) {
        rows++;
        if (rows > 1) {
          status = StatusCode.CARDINALITY_VIOLATION;
        } else {
          if (nestedProjection == NULL_PROJECTION
              || isNull(scalarRow.row(), bound.scalarTable, nestedProjection)) {
            subqueryPredicateFalse = true;
          } else {
            value = readColumn(scalarRow.key(), scalarRow.row(), nestedProjection);
          }
          if (scalar.rowLimit() == 1) {
            break;
          }
        }
      }
    }
    if (cursorActive) {
      StatusCode close = session.closeScan(scalarCursor);
      if (close.isOk()) {
        scalarCursor.reset();
        scalarRow.reset();
      }
      if (status.isOk()) {
        status = close;
      }
    }
    if (status.isOk() && rows == 0) {
      subqueryPredicateFalse = true;
    } else if (status.isOk() && !subqueryPredicateFalse) {
      dynamicScalarNulls[destinationBlock] = false;
      dynamicScalarValues[destinationBlock] = value;
      dynamicScalarTypes[destinationBlock] = nestedProjectionType;
    }
    return status;
  }

  private StatusCode evaluateExistencePredicate() {
    StatusCode status = StatusCode.OK;
    boolean nestedPredicateTrue = true;
    for (int block = query.blockCount() - 1;
        status.isOk() && block > 0;
        block--) {
      BoundSqlQuery.Block nested = query.block(block);
      if (nested == null || nested.isOrdered()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      status = bindNestedCommand(nested);
      if (status.isOk() && nestedCorrelated) {
        if (query.blockCount() != 2) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        correlatedExistence = true;
        return StatusCode.OK;
      }
      if (status.isOk()) {
        if (nestedPredicateTrue) {
          status = evaluateExistenceRows(nested, 0, null);
        } else {
          existenceResult = false;
        }
      }
      if (status.isOk()) {
        nestedPredicateTrue = query.existenceNegated(block - 1)
            ? !existenceResult : existenceResult;
      }
    }
    if (status.isOk()) {
      subqueryPredicateFalse = !nestedPredicateTrue;
    }
    return status;
  }

  private StatusCode evaluateCorrelatedExistence(
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    BoundSqlQuery.Block nested = query.existenceCommand();
    if (nested == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = evaluateExistenceRows(
        nested, outerPrimaryKey, outerSource);
    if (status.isOk()) {
      subqueryPredicateFalse = query.existenceNegated()
          ? existenceResult : !existenceResult;
    }
    return status;
  }

  private StatusCode evaluateExistenceRows(
      BoundSqlQuery.Block nested,
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    StatusCode status = StatusCode.OK;
    existenceResult = false;
    if (status.isOk() && nested.rowLimit() > 0) {
      status = session.beginScan(bound.scalarTable, scalarCursor);
    }
    boolean cursorActive = status.isOk() && nested.rowLimit() > 0;
    while (status.isOk() && cursorActive) {
      status = session.nextScan(scalarCursor, scalarRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) {
        status = validateRow(scalarRow.row(), bound.scalarTable);
      }
      if (status.isOk()
          && matchesScalarPredicates(
              nested,
              scalarRow.key(),
              scalarRow.row(),
              outerPrimaryKey,
              outerSource,
              -1,
              false,
              1,
              0,
              false)) {
        existenceResult = true;
        break;
      }
    }
    if (cursorActive) {
      StatusCode close = session.closeScan(scalarCursor);
      if (close.isOk()) {
        scalarCursor.reset();
        scalarRow.reset();
      }
      if (status.isOk()) {
        status = close;
      }
    }
    return status;
  }

  private StatusCode evaluateMembershipPredicate() {
    StatusCode status = StatusCode.OK;
    int input = 0;
    int inputCount = 0;
    boolean inputHasNull = false;
    for (int block = query.blockCount() - 1;
        status.isOk() && block > 0;
        block--) {
      BoundSqlQuery.Block nested = query.block(block);
      if (nested == null || nested.isOrdered()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      status = bindNestedCommand(nested);
      if (status.isOk() && nestedCorrelated) {
        if (query.blockCount() != 2) {
          return StatusCode.INVALID_EXTERNAL_INPUT;
        }
        correlatedMembership = true;
        membershipCandidateBank = 0;
        membershipCandidateDepth = -1;
        return StatusCode.OK;
      }
      int output = input == 0 ? 1 : 0;
      membershipValueCount = 0;
      membershipHasNull = false;
      resetMembershipOutput(output);
      if (status.isOk()) {
        status = evaluateMembershipRows(
            nested,
            0,
            null,
            output,
            query.membershipPredicate(block),
            query.membershipNegated(block),
            input,
            inputCount,
            inputHasNull);
      }
      input = output;
      inputCount = membershipValueCount;
      inputHasNull = membershipHasNull;
    }
    membershipCandidateBank = input;
    membershipCandidateDepth = -1;
    return status;
  }

  private StatusCode evaluateCorrelatedMembership(
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    BoundSqlQuery.Block nested = query.membershipCommand();
    if (nested == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    membershipCandidateBank = 0;
    membershipCandidateDepth = -1;
    resetMembershipOutput(0);
    return evaluateMembershipRows(
        nested,
        outerPrimaryKey,
        outerSource,
        0,
        -1,
        false,
        1,
        0,
        false);
  }

  private StatusCode evaluateMembershipRows(
      BoundSqlQuery.Block nested,
      long outerPrimaryKey,
      HeapRowResult outerSource,
      int outputBank,
      int nestedMembershipPredicate,
      boolean nestedMembershipNegated,
      int inputBank,
      int inputCount,
      boolean inputHasNull) {
    setMembershipOutputType(outputBank, nestedProjectionType);
    if (nested.rowLimit() == 0) {
      return StatusCode.OK;
    }
    StatusCode status = session.beginScan(bound.scalarTable, scalarCursor);
    boolean cursorActive = status.isOk();
    long matchedRows = 0;
    while (status.isOk()) {
      status = session.nextScan(scalarCursor, scalarRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) {
        status = validateRow(scalarRow.row(), bound.scalarTable);
      }
      if (status.isOk()
          && !matchesScalarPredicates(
              nested,
              scalarRow.key(),
              scalarRow.row(),
              outerPrimaryKey,
              outerSource,
              nestedMembershipPredicate,
              nestedMembershipNegated,
              inputBank,
              inputCount,
              inputHasNull)) {
        continue;
      }
      if (status.isOk()) {
        matchedRows++;
        if (nestedProjection == NULL_PROJECTION
            || isNull(scalarRow.row(), bound.scalarTable, nestedProjection)) {
          membershipHasNull = true;
        } else if (membershipValueCount
            >= membershipCapacity()) {
          status = StatusCode.RESOURCE_EXHAUSTED;
        } else {
          status = appendMembershipValue(
              outputBank,
              -1,
              membershipValueCount,
              scalarRow.key(),
              scalarRow.row(),
              bound.scalarTable,
              nestedProjection);
          if (status.isOk()) {
            membershipValueCount++;
          }
        }
        if (status.isOk() && matchedRows >= nested.rowLimit()) {
          break;
        }
      }
    }
    if (cursorActive) {
      StatusCode close = session.closeScan(scalarCursor);
      if (close.isOk()) {
        scalarCursor.reset();
        scalarRow.reset();
      }
      if (status.isOk()) {
        status = close;
      }
    }
    return status;
  }

  private StatusCode bindNestedCommand(BoundSqlQuery.Block nested) {
    nestedCorrelated = false;
    if (nested.columnCount() != 1 || nested.isSelectAll()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = session.resolveTable(nested.tableName(), bound.scalarTable);
    if (status.isOk()
        && nested.columnTableName(0).length() > 0
        && !matchesTableQualifier(nested, nested.columnTableName(0))) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    nestedProjection = status.isOk() && nested.isNullProjection(0)
        ? NULL_PROJECTION
        : status.isOk() ? bound.scalarTable.findColumn(nested.firstColumnName()) : -1;
    nestedProjectionType = nestedProjection == NULL_PROJECTION
        ? 0 : nestedProjection < 0 ? 0
            : bound.scalarTable.typeDescriptor(nestedProjection);
    if (status.isOk()
        && nestedProjection < 0
        && nestedProjection != NULL_PROJECTION) {
      status = StatusCode.INVALID_EXTERNAL_INPUT;
    }
    for (int index = 0; status.isOk() && index < nested.predicateCount(); index++) {
      if (nested.predicateTableName(index).length() > 0
          && !matchesTableQualifier(nested, nested.predicateTableName(index))) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
        break;
      }
      int column = bound.scalarTable.findColumn(nested.predicateColumnName(index));
      if (column < 0
          || nested.isRangePredicate(index)
              && nested.predicateUpperExclusive(index)
                  <= nested.predicateLowerInclusive(index)) {
        status = StatusCode.INVALID_EXTERNAL_INPUT;
        break;
      }
      setScalarPredicateColumn(index, column);
      setScalarPredicateValue(index, -1, false);
      if (status.isOk() && nested.isColumnPredicate(index)) {
        CharSequence valueTable = nested.predicateValueTableName(index);
        int valueColumn;
        if (valueTable.length() == 0) {
          status = StatusCode.INVALID_EXTERNAL_INPUT;
          break;
        } else if (matchesTableQualifier(nested, valueTable)) {
          valueColumn = bound.scalarTable.findColumn(
              nested.predicateValueColumnName(index));
        } else if (matchesTableQualifier(command, valueTable)) {
          valueColumn = bound.table.findColumn(nested.predicateValueColumnName(index));
          setScalarPredicateValue(index, -1, true);
          nestedCorrelated = true;
        } else {
          status = StatusCode.INVALID_EXTERNAL_INPUT;
          break;
        }
        if (valueColumn < 0) {
          status = StatusCode.INVALID_EXTERNAL_INPUT;
          break;
        }
        TableDefinition valueDefinition = scalarPredicateValueOuter(index)
            ? bound.table : bound.scalarTable;
        if (!SqlTypeDescriptor.canCompare(
            bound.scalarTable.typeDescriptor(column),
            valueDefinition.typeDescriptor(valueColumn))) {
          status = StatusCode.DATATYPE_MISMATCH;
          break;
        }
        setScalarPredicateValue(
            index,
            valueColumn,
            scalarPredicateValueOuter(index));
      } else if (status.isOk()
          && !nested.isNullPredicate(index)
          && index != query.membershipPredicate(nestedBlock(nested))
          && !SqlTypeDescriptor.canCompare(
              bound.scalarTable.typeDescriptor(column),
              nested.predicateTypeDescriptor(index))) {
        status = StatusCode.DATATYPE_MISMATCH;
        break;
      }
    }
    int block = nestedBlock(nested);
    if (status.isOk() && block > 0) {
      int parent = block - 1;
      TableDefinition parentDefinition = bound.table;
      if (parent > 0) {
        status = session.resolveTable(
            query.block(parent).tableName(), parentResultTable);
        parentDefinition = parentResultTable;
      }
      if (status.isOk()) {
        status = validateNestedResultEdge(
            parent, nestedProjectionType, parentDefinition);
      }
    }
    return status;
  }

  private int nestedBlock(BoundSqlQuery.Block nested) {
    for (int block = 1; block < query.blockCount(); block++) {
      if (query.block(block) == nested) {
        return block;
      }
    }
    return -1;
  }

  private boolean matchesScalarPredicates(
      BoundSqlQuery.Block scalar,
      long primaryKey,
      HeapRowResult source,
      long outerPrimaryKey,
      HeapRowResult outerSource,
      int nestedMembershipPredicate,
      boolean nestedMembershipNegated,
      int nestedMembershipBank,
      int nestedMembershipValueCount,
      boolean nestedMembershipHasNull) {
    for (int index = 0; index < scalar.predicateCount(); index++) {
      int predicateColumn = scalarPredicateColumn(index);
      long value = readColumn(primaryKey, source, predicateColumn);
      boolean nullValue = isNull(
          source, bound.scalarTable, predicateColumn);
      if (scalar.isNullPredicate(index)) {
        if (nullValue == scalar.isNullPredicateNegated(index)) {
          return false;
        }
        continue;
      }
      if (nullValue) {
        return false;
      }
      int block = nestedBlock(scalar);
      if (block >= 0
          && query.hasScalarPredicate(block)
          && query.scalarPredicate(block) == index) {
        if (dynamicScalarNulls[block]
            || !SqlTypeDescriptor.canCompare(
                bound.scalarTable.typeDescriptor(predicateColumn),
                dynamicScalarTypes[block])
            || !expressions.matchesComparison(
                value, scalar.comparison(index), dynamicScalarValues[block])) {
          return false;
        }
        continue;
      }
      if (index == nestedMembershipPredicate) {
        boolean equal = false;
        for (int candidate = 0;
            candidate < nestedMembershipValueCount;
            candidate++) {
          if (isTextType(membershipResultType(nestedMembershipBank, -1))
              ? membershipTextEquals(
                  source,
                  predicateColumn,
                  nestedMembershipBank,
                  -1,
                  candidate)
              : value == membershipValue(
                  nestedMembershipBank, -1, candidate)) {
            equal = true;
            break;
          }
        }
        if (equal == nestedMembershipNegated
            || !equal && nestedMembershipHasNull) {
          return false;
        }
        continue;
      }
      if (scalar.isColumnPredicate(index)) {
        boolean outer = scalarPredicateValueOuter(index);
        HeapRowResult valueSource = outer ? outerSource : source;
        TableDefinition valueTable = outer ? bound.table : bound.scalarTable;
        int valueColumn = scalarPredicateValueColumn(index);
        if (valueSource == null
            || isNull(valueSource, valueTable, valueColumn)
            || !equalColumns(
                primaryKey,
                source,
                bound.scalarTable,
                predicateColumn,
                outer ? outerPrimaryKey : primaryKey,
                valueSource,
                valueTable,
                valueColumn)) {
          return false;
        }
        continue;
      }
      if (!matchesLiteralComparison(
          primaryKey,
          source,
          bound.scalarTable,
          predicateColumn,
          scalar,
          index)) {
        return false;
      }
    }
    return true;
  }

  StatusCode copyCorrelatedOuterRow(HeapRowResult source) {
    if (source.length() > correlatedOuterBuffer.capacity()) {
      return StatusCode.CORRUPTION;
    }
    correlatedOuterBuffer.clear();
    correlatedOuterBuffer.limit(source.length());
    StatusCode status = source.copyTo(correlatedOuterBuffer);
    if (status.isOk()) {
      correlatedOuterBuffer.flip();
      correlatedOuterRow.set(correlatedOuterBuffer, 0, 0, source.length());
    }
    return status;
  }
}
