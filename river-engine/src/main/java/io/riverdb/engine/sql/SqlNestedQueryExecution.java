package io.riverdb.engine.sql;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.type.SqlTypeDescriptor;
import io.riverdb.engine.relational.RelationalScanCursor;
import io.riverdb.engine.relational.RelationalScanResult;
import io.riverdb.engine.relational.RelationalSession;
import io.riverdb.engine.relational.TableDefinition;
import io.riverdb.engine.relational.TableSchema;
import io.riverdb.storage.heap.HeapRowResult;
import java.nio.ByteBuffer;

/** Owns bounded cursors, row copies, and value sets used by nested queries. */
final class SqlNestedQueryExecution {
  static final int MAXIMUM_MEMBERSHIP_VALUES = SqlMembershipValues.MAXIMUM_VALUES;
  private static final int NULL_PROJECTION = BoundSqlStatement.NULL_PROJECTION;
  private static final int NESTED_SCALAR = 1;
  private static final int NESTED_EXISTENCE = 2;
  private static final int NESTED_MEMBERSHIP = 3;

  private final RelationalSession session;
  private final BoundSqlStatement bound;
  private final BoundSqlQuery.Block command;
  private final BoundSqlQuery query;
  private final SqlExpressionEvaluator expressions;
  private final SqlNestedPredicateEvaluator predicates;

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
  private final boolean[] recursiveRowAccepted =
      new boolean[BoundSqlQuery.MAXIMUM_BLOCKS];
  private final NestedChainState nestedChainState = new NestedChainState();
  private final SqlMembershipValues memberships = new SqlMembershipValues();
  private final RelationalScanCursor scalarCursor = new RelationalScanCursor();
  private final RelationalScanResult scalarRow = new RelationalScanResult();
  // The outer copy remains valid through both predicate phases for one outer row
  // and is overwritten only by the next evaluateBeforePredicates call.
  private final HeapRowResult correlatedOuterRow = new HeapRowResult();
  private final ByteBuffer correlatedOuterBuffer = ByteBuffer.allocateDirect(
      TableSchema.MAXIMUM_ROW_BYTES);
  private boolean subqueryPredicateFalse;
  private boolean membershipHasNull;
  private boolean nestedCorrelated;
  private boolean nestedCandidateAccepted;
  private boolean correlatedScalar;
  private boolean correlatedExistence;
  private boolean correlatedMembership;
  private boolean correlatedNestedChain;
  private boolean recursiveNestedChain;
  private boolean recursiveRootCorrelated;
  private boolean preparingCandidate;
  private boolean existenceResult;
  private boolean scalarResultNull;
  private boolean outerCopied;
  private long scalarResultValue;
  private int membershipCandidateBank;
  private int membershipCandidateDepth = -1;
  private int membershipCandidateOffset;
  private int nestedProjection;
  private int nestedProjectionType;
  private TableDefinition nestedTable;
  private BoundSqlQuery.Block nestedCommand;
  private int membershipValueCount;

  SqlNestedQueryExecution(
      RelationalSession relationalSession,
      BoundSqlStatement boundStatement,
      SqlExpressionEvaluator evaluator) {
    session = relationalSession;
    bound = boundStatement;
    query = bound.executableQuery;
    command = query.root();
    expressions = evaluator;
    predicates = new SqlNestedPredicateEvaluator(expressions);
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
    memberships.resetStatement();
    predicates.reset();
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
    preparingCandidate = true;
    correlatedScalar = query.topology().scalar();
    correlatedExistence = query.topology().existence();
    correlatedMembership = query.topology().membership();
    correlatedNestedChain = query.topology().nestedChain();
    recursiveNestedChain = query.topology().recursiveChain();
    recursiveRootCorrelated = query.topology().rootCorrelated();
    try {
      if (query.blockCount() > 2
          && (query.hasScalarPredicate()
              || query.hasExistencePredicate()
              || query.hasMembershipPredicate())) {
        return prepareNestedChain(explainOnly);
      }
      if (query.hasScalarPredicate()) {
        return explainOnly
            ? selectBoundBlock(query.scalarCommand()) : evaluateScalarPredicate();
      }
      if (query.hasExistencePredicate()) {
        return explainOnly
            ? selectBoundBlock(query.existenceCommand()) : evaluateExistencePredicate();
      }
      if (query.hasMembershipPredicate()) {
        return explainOnly
            ? selectBoundBlock(query.membershipCommand()) : evaluateMembershipPredicate();
      }
      return StatusCode.OK;
    } finally {
      preparingCandidate = false;
    }
  }

  StatusCode evaluateBeforePredicates(
      long primaryKey, HeapRowResult source) {
    if (query.blockCount() > 1 && !query.isExecutable()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    outerCopied = false;
    if (correlatedScalar || correlatedExistence) {
      subqueryPredicateFalse = false;
    }
    StatusCode status = evaluateCorrelatedRecursive(primaryKey, source);
    if (canContinue(status) && correlatedNestedChain) {
      resetMembershipResult();
      status = withOuterCopy(source, primaryKey, NESTED_CHAIN_EVALUATION);
    }
    if (canContinue(status) && correlatedScalar) {
      status = withOuterCopy(source, primaryKey, SCALAR_EVALUATION);
    }
    if (canContinue(status) && correlatedMembership) {
      resetMembershipResult();
      status = withOuterCopy(source, primaryKey, MEMBERSHIP_EVALUATION);
    }
    return status;
  }

  private static final int SCALAR_EVALUATION = 1;
  private static final int MEMBERSHIP_EVALUATION = 2;
  private static final int NESTED_CHAIN_EVALUATION = 3;

  private boolean canContinue(StatusCode status) {
    return status.isOk() && !subqueryPredicateFalse;
  }

  private void resetMembershipResult() {
    subqueryPredicateFalse = false;
    membershipValueCount = 0;
    membershipHasNull = false;
  }

  private StatusCode evaluateCorrelatedRecursive(
      long primaryKey, HeapRowResult source) {
    if (!recursiveNestedChain || !recursiveRootCorrelated) {
      return StatusCode.OK;
    }
    subqueryPredicateFalse = false;
    StatusCode status = copyOuter(source);
    return status.isOk()
        ? evaluateRecursiveChain(primaryKey, correlatedOuterRow) : status;
  }

  private StatusCode withOuterCopy(
      HeapRowResult source, long primaryKey, int evaluation) {
    StatusCode status = copyOuter(source);
    if (!status.isOk()) {
      return status;
    }
    return switch (evaluation) {
      case SCALAR_EVALUATION ->
          evaluateCorrelatedScalar(primaryKey, correlatedOuterRow);
      case MEMBERSHIP_EVALUATION ->
          evaluateCorrelatedMembership(primaryKey, correlatedOuterRow);
      case NESTED_CHAIN_EVALUATION ->
          evaluateNestedChain(primaryKey, correlatedOuterRow);
      default -> StatusCode.INVARIANT_BROKEN;
    };
  }

  StatusCode evaluateAfterPredicates(
      long primaryKey, HeapRowResult source) {
    if (query.blockCount() > 1 && !query.isExecutable()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
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
      long value,
      int valueDescriptor,
      HeapRowResult source,
      int column) {
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
          : expressions.compareExact(
              value,
              valueDescriptor,
              membershipValue(
                  membershipCandidateBank,
                  membershipCandidateDepth,
                  membershipCandidateOffset + candidate),
              membershipResultType(
                  membershipCandidateBank, membershipCandidateDepth)) == 0) {
        equal = true;
        break;
      }
    }
    return equal != query.membershipNegated()
        && (equal || !membershipHasNull);
  }

  boolean matchesScalar(long value, int valueDescriptor) {
    return !dynamicScalarNulls[0]
        && expressions.matchesComparison(
            value,
            valueDescriptor,
            command.comparison(query.scalarPredicate()),
            dynamicScalarValues[0],
            dynamicScalarTypes[0]);
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

  private boolean matchesLiteralComparison(
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition,
      int column,
      BoundSqlQuery.Block predicateSource,
      int predicate) {
    return predicates.matchesLiteral(
        primaryKey, source, definition, column, predicateSource.predicates(), predicate);
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
    return predicates.equalColumns(
        leftKey,
        left,
        leftDefinition,
        leftColumn,
        rightKey,
        right,
        rightDefinition,
        rightColumn);
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
    return nestedCommand.predicates().resolvedColumn(predicate);
  }

  int scalarPredicateValueColumn(int predicate) {
    return nestedCommand.predicates().resolvedValueColumn(predicate);
  }

  boolean scalarPredicateValueOuter(int predicate) {
    int scope = nestedCommand.predicates().resolvedValueScope(predicate);
    return scope >= 0 && scope < nestedCommand.blockIndex();
  }

  TableDefinition recursiveTable(int depth) {
    BoundSqlQuery.Block block = query.block(depth);
    return block == null ? null : block.table();
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
    return query.block(depth).projection();
  }

  int recursivePredicateColumn(int slot) {
    int depth = slot / BoundSqlQuery.MAXIMUM_PREDICATES;
    return query.block(depth).predicates().resolvedColumn(
        slot % BoundSqlQuery.MAXIMUM_PREDICATES);
  }

  int recursivePredicateValueColumn(int slot) {
    int depth = slot / BoundSqlQuery.MAXIMUM_PREDICATES;
    return query.block(depth).predicates().resolvedValueColumn(
        slot % BoundSqlQuery.MAXIMUM_PREDICATES);
  }

  int recursivePredicateValueScope(int slot) {
    int depth = slot / BoundSqlQuery.MAXIMUM_PREDICATES;
    return query.block(depth).predicates().resolvedValueScope(
        slot % BoundSqlQuery.MAXIMUM_PREDICATES);
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
    memberships.resetRecursive(depth);
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
    return memberships.value(scratch, index);
  }

  long membershipValue(int bank, int depth, int index) {
    return memberships.value(bank, depth, index);
  }

  int membershipCapacity() {
    return MAXIMUM_MEMBERSHIP_VALUES;
  }

  void setMembershipValue(boolean scratch, int index, long value) {
    memberships.setValue(scratch, index, value);
  }

  private void resetMembershipOutput(int bank) {
    memberships.resetOutput(bank);
  }

  private void setMembershipOutputType(int bank, int type) {
    memberships.setType(bank, type);
  }

  private int membershipResultType(int bank, int depth) {
    return memberships.type(bank, depth, recursiveResultTypes);
  }

  private StatusCode appendMembershipValue(
      int bank,
      int depth,
      int index,
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition,
      int projection) {
    return memberships.append(
        bank,
        depth,
        index,
        readColumn(primaryKey, source, projection),
        source,
        definition.isVarchar(projection));
  }

  private boolean membershipTextEquals(
      HeapRowResult source,
      int column,
      int bank,
      int depth,
      int index) {
    return memberships.textEquals(
        source, readColumn(0, source, column), bank, depth, index);
  }

  long recursiveMembershipValue(int depth, int index) {
    return memberships.recursiveValue(depth, index);
  }

  void setRecursiveMembershipValue(int depth, int index, long value) {
    memberships.setRecursiveValue(depth, index, value);
  }

  void ensureRecursiveState() {
    memberships.ensureRecursiveState(BoundSqlQuery.MAXIMUM_BLOCKS);
    for (int block = 0; block < BoundSqlQuery.MAXIMUM_BLOCKS; block++) {
      if (recursiveCursors[block] == null) {
        recursiveCursors[block] = new RelationalScanCursor();
        recursiveRows[block] = new RelationalScanResult();
        recursiveRowBuffers[block] = ByteBuffer.allocateDirect(
            TableSchema.MAXIMUM_ROW_BYTES);
        recursiveRowCopies[block] = new HeapRowResult();
      }
    }
  }

  private StatusCode prepareNestedChain(boolean explainOnly) {
    StatusCode status = prepareRecursiveChain(explainOnly);
    if (!status.isOk()) {
      return status;
    }
    if (recursiveNestedChain) {
      return explainOnly || recursiveRootCorrelated
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
      status = selectBoundBlock(nested);
      correlated |= nestedCorrelated;
    }
    if (explainOnly) {
      return status;
    }
    if (status.isOk() && correlated) {
      correlatedNestedChain = true;
      return StatusCode.OK;
    }
    return status.isOk() ? evaluateNestedChain(0, null) : status;
  }

  private StatusCode prepareRecursiveChain(boolean explainOnly) {
    if (!hasIntermediateReference()) {
      return StatusCode.OK;
    }
    if (!explainOnly) {
      ensureRecursiveState();
    }
    return bindRecursiveChain();
  }

  private boolean hasIntermediateReference() {
    return query.topology().recursiveChain();
  }

  private StatusCode bindRecursiveChain() {
    recursiveNestedChain = query.topology().recursiveChain();
    recursiveRootCorrelated = query.topology().rootCorrelated();
    for (int depth = 1; depth < query.blockCount(); depth++) {
      BoundSqlQuery.Block nested = query.block(depth);
      if (!availableBlock(nested)) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      recursiveResultTypes[depth] = nested.projectionType();
    }
    return StatusCode.OK;
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
    publishRecursiveResult(resultKind);
    return status;
  }

  private void publishRecursiveResult(int resultKind) {
    if (resultKind == NESTED_SCALAR) {
      publishRecursiveScalar();
    } else if (resultKind == NESTED_EXISTENCE) {
      subqueryPredicateFalse = query.existenceNegated()
          ? recursiveExistence(1) : !recursiveExistence(1);
    } else {
      publishRecursiveMembership();
    }
  }

  private void publishRecursiveScalar() {
    subqueryPredicateFalse = recursiveScalarNull(1);
    if (!subqueryPredicateFalse) {
      dynamicScalarNulls[0] = false;
      dynamicScalarValues[0] = recursiveScalarValue(1);
      dynamicScalarTypes[0] = recursiveResultTypes[1];
    }
  }

  private void publishRecursiveMembership() {
    subqueryPredicateFalse = false;
    membershipCandidateBank = 2;
    membershipCandidateDepth = 1;
    membershipCandidateOffset = 0;
    membershipValueCount = recursiveMembershipCount(1);
    membershipHasNull = recursiveMembershipNull(1);
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
      status = nextRecursiveCandidate(
          depth, outerPrimaryKey, outerSource, definition, cursor, rowResult);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) {
        break;
      }
      if (!recursiveRowAccepted[depth]) {
        continue;
      }
      matchedRows++;
      status = accumulateRecursiveResult(
          depth, resultKind, matchedRows, definition, rowResult);
      if (status.isOk()
          && (resultKind == NESTED_EXISTENCE
              || matchedRows >= nested.rowLimit())) {
        break;
      }
    }
    return closeRecursiveCursor(cursorActive, cursor, rowResult, status);
  }

  private StatusCode nextRecursiveCandidate(
      int depth,
      long outerPrimaryKey,
      HeapRowResult outerSource,
      TableDefinition definition,
      RelationalScanCursor cursor,
      RelationalScanResult rowResult) {
    StatusCode status = session.nextScan(cursor, rowResult);
    return status.isOk()
        ? evaluateRecursiveRow(
            depth, outerPrimaryKey, outerSource, definition, rowResult)
        : status;
  }

  private StatusCode closeRecursiveCursor(
      boolean cursorActive,
      RelationalScanCursor cursor,
      RelationalScanResult rowResult,
      StatusCode bodyStatus) {
    if (!cursorActive) {
      return bodyStatus;
    }
    StatusCode close = closeCursor(cursor, rowResult);
    return bodyStatus.isOk() ? close : bodyStatus;
  }

  private StatusCode evaluateRecursiveRow(
      int depth,
      long outerPrimaryKey,
      HeapRowResult outerSource,
      TableDefinition definition,
      RelationalScanResult rowResult) {
    recursiveRowAccepted[depth] = false;
    HeapRowResult source = rowResult.row();
    long primaryKey = rowResult.key();
    StatusCode status = validateRow(source, definition);
    if (!status.isOk()) return status;
    if (!matchesRecursivePredicates(
        depth, primaryKey, source, outerPrimaryKey, outerSource)) {
      StatusCode predicateStatus = predicates.status();
      return predicateStatus.isOk() ? StatusCode.OK : predicateStatus;
    }
    if (!predicates.status().isOk()) {
      return predicates.status();
    }
    if (depth + 1 < query.blockCount()) {
      status = evaluateRecursiveDescendant(
          depth, primaryKey, source, outerPrimaryKey, outerSource);
      if (!status.isOk() || !recursiveRowAccepted[depth]) {
        return status;
      }
    }
    recursiveRowAccepted[depth] = true;
    return StatusCode.OK;
  }

  private StatusCode evaluateRecursiveDescendant(
      int depth,
      long primaryKey,
      HeapRowResult source,
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    StatusCode status = copyRecursiveRow(depth, primaryKey, source);
    if (!status.isOk()) {
      return status;
    }
    int childKind = recursiveResultKind(depth);
    if (childKind == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    status = evaluateRecursiveBlock(
        depth + 1, childKind, outerPrimaryKey, outerSource);
    if (status.isOk()) {
      recursiveRowAccepted[depth] = matchesRecursiveChild(
          depth, primaryKey, recursiveRowCopy(depth));
    }
    return status;
  }

  private StatusCode accumulateRecursiveResult(
      int depth,
      int resultKind,
      long matchedRows,
      TableDefinition definition,
      RelationalScanResult rowResult) {
    if (resultKind == NESTED_EXISTENCE) {
      setRecursiveExistence(depth);
      return StatusCode.OK;
    }
    HeapRowResult source = depth + 1 < query.blockCount()
        ? recursiveRowCopy(depth) : rowResult.row();
    long primaryKey = rowResult.key();
    int projection = recursiveProjection(depth);
    if (resultKind == NESTED_SCALAR) {
      return accumulateRecursiveScalar(
          depth, matchedRows, primaryKey, source, definition, projection);
    }
    return accumulateRecursiveMembership(
        depth, primaryKey, source, definition, projection);
  }

  private StatusCode accumulateRecursiveScalar(
      int depth,
      long matchedRows,
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition,
      int projection) {
    if (matchedRows > 1) {
      return StatusCode.CARDINALITY_VIOLATION;
    }
    if (projection != NULL_PROJECTION
        && !isNull(source, definition, projection)) {
      setRecursiveScalar(
          depth, readColumn(primaryKey, source, projection));
    }
    return StatusCode.OK;
  }

  private StatusCode accumulateRecursiveMembership(
      int depth,
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition,
      int projection) {
    if (projection == NULL_PROJECTION
        || isNull(source, definition, projection)) {
      setRecursiveMembershipNull(depth);
      return StatusCode.OK;
    }
    int count = recursiveMembershipCount(depth);
    if (count >= MAXIMUM_MEMBERSHIP_VALUES) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = appendMembershipValue(
        2, depth, count, primaryKey, source, definition, projection);
    if (status.isOk()) {
      setRecursiveMembershipCount(depth, count + 1);
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
    predicates.reset();
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
      if (!matchesRecursivePredicate(
          depth,
          base + index,
          index,
          primaryKey,
          source,
          definition,
          nested,
          outerPrimaryKey,
          outerSource)) {
        return false;
      }
    }
    return true;
  }

  private boolean matchesRecursivePredicate(
      int depth,
      int slot,
      int predicate,
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition,
      BoundSqlQuery.Block nested,
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    int column = recursivePredicateColumn(slot);
    boolean nullValue = isNull(source, definition, column);
    if (nested.predicates().isTruth(predicate)) {
      long value = nullValue ? 0 : readColumn(primaryKey, source, column);
      return predicates.matchesTruth(
          nested.predicates(), predicate, nullValue, value);
    }
    if (nested.isNullPredicate(predicate)) {
      return nullValue != nested.isNullPredicateNegated(predicate);
    }
    if (nullValue) {
      return false;
    }
    if (nested.isColumnPredicate(predicate)) {
      return matchesRecursiveColumn(
          depth,
          slot,
          primaryKey,
          source,
          definition,
          column,
          outerPrimaryKey,
          outerSource);
    }
    return matchesLiteralComparison(
        primaryKey, source, definition, column, nested, predicate);
  }

  private boolean matchesRecursiveColumn(
      int depth,
      int slot,
      long primaryKey,
      HeapRowResult source,
      TableDefinition definition,
      int column,
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    int scope = recursivePredicateValueScope(slot);
    int valueColumn = recursivePredicateValueColumn(slot);
    HeapRowResult valueSource = scope == 0
        ? outerSource : scope == depth ? source : recursiveRowCopy(scope);
    if (valueSource == null) {
      return false;
    }
    TableDefinition valueDefinition = scope == 0
        ? bound.table : recursiveTable(scope);
    long valueKey = scope == 0
        ? outerPrimaryKey : scope == depth ? primaryKey : recursiveKey(scope);
    return !isNull(valueSource, valueDefinition, valueColumn)
        && equalColumns(
            primaryKey,
            source,
            definition,
            column,
            valueKey,
            valueSource,
            valueDefinition,
            valueColumn);
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
              definition.typeDescriptor(column),
              query.block(depth).comparison(predicate),
              recursiveScalarValue(child),
              recursiveResultTypes[child]);
    }
    int count = recursiveMembershipCount(child);
    boolean equal = false;
    for (int index = 0; index < count; index++) {
      if (isTextType(recursiveResultTypes[child])
          ? membershipTextEquals(source, column, 2, child, index)
          : expressions.compareExact(
              value,
              definition.typeDescriptor(column),
              recursiveMembershipValue(child, index),
              recursiveResultTypes[child]) == 0) {
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
    nestedChainState.reset();
    for (int block = query.blockCount() - 1; block > 0; block--) {
      BoundSqlQuery.Block nested = query.block(block);
      if (nested == null || nested.isOrdered()) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      StatusCode status = evaluateNestedBlock(
          block, nested, outerPrimaryKey, outerSource, nestedChainState);
      if (!status.isOk()) {
        return status;
      }
    }
    publishNestedChain(nestedChainState);
    return StatusCode.OK;
  }

  private StatusCode evaluateNestedBlock(
      int block,
      BoundSqlQuery.Block nested,
      long outerPrimaryKey,
      HeapRowResult outerSource,
      NestedChainState state) {
    StatusCode status = selectBoundBlock(nested);
    if (!status.isOk()) {
      return status;
    }
    if (nestedCorrelated && outerSource == null) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int parent = block - 1;
    int kind = nestedResultKind(parent);
    if (kind == 0) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    int output = state.candidates == 0 ? 1 : 0;
    membershipValueCount = 0;
    membershipHasNull = false;
    resetMembershipOutput(output);
    status = evaluateNestedRows(
        nested,
        state.commandEnabled,
        kind,
        output,
        outerPrimaryKey,
        outerSource,
        query.membershipPredicate(block),
        query.membershipNegated(block),
        state.candidates,
        state.candidateCount,
        state.candidateHasNull);
    if (status.isOk()) {
      acceptNestedResult(parent, kind, output, state);
    }
    return status;
  }

  private int nestedResultKind(int parent) {
    if (query.hasScalarPredicate(parent)) {
      return NESTED_SCALAR;
    }
    if (query.hasExistencePredicate(parent)) {
      return NESTED_EXISTENCE;
    }
    return query.hasMembershipPredicate(parent) ? NESTED_MEMBERSHIP : 0;
  }

  private void acceptNestedResult(
      int parent,
      int kind,
      int output,
      NestedChainState state) {
    if (kind == NESTED_SCALAR) {
      state.commandEnabled = !scalarResultNull;
      if (state.commandEnabled) {
        dynamicScalarNulls[parent] = false;
        dynamicScalarValues[parent] = scalarResultValue;
        dynamicScalarTypes[parent] = nestedProjectionType;
      }
      return;
    }
    if (kind == NESTED_EXISTENCE) {
      state.commandEnabled = query.existenceNegated(parent)
          ? !existenceResult : existenceResult;
      return;
    }
    state.commandEnabled = true;
    setMembershipOutputType(output, nestedProjectionType);
    state.candidates = output;
    state.candidateCount = membershipValueCount;
    state.candidateHasNull = membershipHasNull;
  }

  private void publishNestedChain(NestedChainState state) {
    subqueryPredicateFalse = !state.commandEnabled;
    if (query.hasMembershipPredicate()) {
      membershipCandidateBank = state.candidates;
      membershipCandidateDepth = -1;
      membershipValueCount = state.candidateCount;
      membershipHasNull = state.candidateHasNull;
    }
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
    StatusCode status = session.beginScan(nestedTable, scalarCursor);
    boolean cursorActive = status.isOk();
    long matchedRows = 0;
    while (status.isOk()) {
      status = nextNestedCandidate(
          nested,
          outerPrimaryKey,
          outerSource,
          nestedMembershipPredicate,
          nestedMembershipNegated,
          inputBank,
          inputCount,
          inputHasNull);
      if (status == StatusCode.CONFLICT) return closeScalarCursor(cursorActive, StatusCode.OK);
      if (!status.isOk()) break;
      if (!nestedCandidateAccepted) continue;
      matchedRows++;
      status = accumulateNestedResult(resultKind, outputBank, matchedRows);
      if (status.isOk()
          && (resultKind == NESTED_EXISTENCE
              || matchedRows >= nested.rowLimit())) {
        break;
      }
    }
    return closeScalarCursor(cursorActive, status);
  }

  private StatusCode nextNestedCandidate(
      BoundSqlQuery.Block nested,
      long outerPrimaryKey,
      HeapRowResult outerSource,
      int nestedMembershipPredicate,
      boolean nestedMembershipNegated,
      int inputBank,
      int inputCount,
      boolean inputHasNull) {
    StatusCode status = session.nextScan(scalarCursor, scalarRow);
    nestedCandidateAccepted = false;
    if (!status.isOk()) {
      return status;
    }
    status = validateRow(scalarRow.row(), nestedTable);
    if (!status.isOk()) {
      return status;
    }
    nestedCandidateAccepted = matchesScalarPredicates(
        nested,
        scalarRow.key(),
        scalarRow.row(),
        outerPrimaryKey,
        outerSource,
        nestedMembershipPredicate,
        nestedMembershipNegated,
        inputBank,
        inputCount,
        inputHasNull);
    return predicates.status();
  }

  private StatusCode closeScalarCursor(
      boolean cursorActive, StatusCode bodyStatus) {
    if (!cursorActive) {
      return bodyStatus;
    }
    StatusCode close = closeCursor(scalarCursor, scalarRow);
    return bodyStatus.isOk() ? close : bodyStatus;
  }

  private StatusCode accumulateNestedResult(
      int resultKind, int outputBank, long matchedRows) {
    if (resultKind == NESTED_EXISTENCE) {
      existenceResult = true;
      return StatusCode.OK;
    }
    if (resultKind == NESTED_SCALAR) {
      return accumulateNestedScalar(matchedRows);
    }
    return accumulateNestedMembership(outputBank);
  }

  private StatusCode accumulateNestedScalar(long matchedRows) {
    if (matchedRows > 1) {
      return StatusCode.CARDINALITY_VIOLATION;
    }
    if (nestedProjection != NULL_PROJECTION
        && !isNull(scalarRow.row(), nestedTable, nestedProjection)) {
      scalarResultNull = false;
      scalarResultValue = readColumn(
          scalarRow.key(), scalarRow.row(), nestedProjection);
    }
    return StatusCode.OK;
  }

  private StatusCode accumulateNestedMembership(int outputBank) {
    if (nestedProjection == NULL_PROJECTION
        || isNull(scalarRow.row(), nestedTable, nestedProjection)) {
      membershipHasNull = true;
      return StatusCode.OK;
    }
    if (membershipValueCount >= membershipCapacity()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = appendMembershipValue(
        outputBank,
        -1,
        membershipValueCount,
        scalarRow.key(),
        scalarRow.row(),
        nestedTable,
        nestedProjection);
    if (status.isOk()) {
      membershipValueCount++;
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
      status = selectBoundBlock(scalar);
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
    if (scalar.rowLimit() == 0) {
      subqueryPredicateFalse = true;
      return StatusCode.OK;
    }
    StatusCode status = session.beginScan(nestedTable, scalarCursor);
    boolean cursorActive = status.isOk();
    int rows = 0;
    long value = 0;
    while (status.isOk()) {
      status = nextNestedCandidate(
          scalar, outerPrimaryKey, outerSource, -1, false, 1, 0, false);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (!status.isOk()) break;
      if (!nestedCandidateAccepted) continue;
      rows++;
      if (rows > 1) status = StatusCode.CARDINALITY_VIOLATION;
      else value = captureScalarValue();
      if (status.isOk() && scalar.rowLimit() == 1) break;
    }
    status = closeScalarCursor(cursorActive, status);
    publishScalarResult(status, rows, value, destinationBlock);
    return status;
  }

  private void publishScalarResult(
      StatusCode status, int rows, long value, int destinationBlock) {
    if (!status.isOk()) {
      return;
    }
    if (rows == 0) {
      subqueryPredicateFalse = true;
    } else if (!subqueryPredicateFalse) {
      dynamicScalarNulls[destinationBlock] = false;
      dynamicScalarValues[destinationBlock] = value;
      dynamicScalarTypes[destinationBlock] = nestedProjectionType;
    }
  }

  private long captureScalarValue() {
    if (nestedProjection == NULL_PROJECTION
        || isNull(scalarRow.row(), nestedTable, nestedProjection)) {
      subqueryPredicateFalse = true;
      return 0;
    }
    return readColumn(scalarRow.key(), scalarRow.row(), nestedProjection);
  }

  private StatusCode evaluateExistencePredicate() {
    StatusCode status = StatusCode.OK;
    boolean nestedPredicateTrue = true;
    for (int block = query.blockCount() - 1;
        status.isOk() && block > 0;
        block--) {
      BoundSqlQuery.Block nested = query.block(block);
      status = prepareExistenceBlock(nested, nestedPredicateTrue);
      if (status.isOk() && nestedCorrelated) {
        correlatedExistence = true;
        return StatusCode.OK;
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

  private StatusCode prepareExistenceBlock(
      BoundSqlQuery.Block nested, boolean nestedPredicateTrue) {
    if (nested == null || nested.isOrdered()) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    StatusCode status = selectBoundBlock(nested);
    if (!status.isOk()) {
      return status;
    }
    if (nestedCorrelated) {
      return query.blockCount() == 2
          ? StatusCode.OK : StatusCode.INVALID_EXTERNAL_INPUT;
    }
    if (!nestedPredicateTrue) {
      existenceResult = false;
      return StatusCode.OK;
    }
    return evaluateExistenceRows(nested, 0, null);
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
    existenceResult = false;
    if (nested.rowLimit() == 0) {
      return StatusCode.OK;
    }
    StatusCode status = session.beginScan(nestedTable, scalarCursor);
    boolean cursorActive = status.isOk();
    while (status.isOk()) {
      status = session.nextScan(scalarCursor, scalarRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) {
        status = validateRow(scalarRow.row(), nestedTable);
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
      if (status.isOk() && !predicates.status().isOk()) status = predicates.status();
    }
    return closeScalarCursor(cursorActive, status);
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
      status = selectBoundBlock(nested);
      if (status.isOk() && nestedCorrelated) {
        return prepareCorrelatedMembership();
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

  private StatusCode prepareCorrelatedMembership() {
    if (query.blockCount() != 2) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    correlatedMembership = true;
    membershipCandidateBank = 0;
    membershipCandidateDepth = -1;
    return StatusCode.OK;
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
    StatusCode status = session.beginScan(nestedTable, scalarCursor);
    boolean cursorActive = status.isOk();
    long matchedRows = 0;
    while (status.isOk()) {
      status = session.nextScan(scalarCursor, scalarRow);
      if (status == StatusCode.CONFLICT) {
        status = StatusCode.OK;
        break;
      }
      if (status.isOk()) {
        status = validateRow(scalarRow.row(), nestedTable);
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
        if (!predicates.status().isOk()) status = predicates.status();
        continue;
      }
      if (!status.isOk()) {
        break;
      }
      matchedRows++;
      status = appendCurrentMembershipValue(outputBank);
      if (status.isOk() && matchedRows >= nested.rowLimit()) {
        break;
      }
    }
    return closeScalarCursor(cursorActive, status);
  }

  private StatusCode appendCurrentMembershipValue(int outputBank) {
    if (nestedProjection == NULL_PROJECTION
        || isNull(scalarRow.row(), nestedTable, nestedProjection)) {
      membershipHasNull = true;
      return StatusCode.OK;
    }
    if (membershipValueCount >= membershipCapacity()) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = appendMembershipValue(
        outputBank,
        -1,
        membershipValueCount,
        scalarRow.key(),
        scalarRow.row(),
        nestedTable,
        nestedProjection);
    if (status.isOk()) {
      membershipValueCount++;
    }
    return status;
  }

  private StatusCode selectBoundBlock(BoundSqlQuery.Block nested) {
    nestedCorrelated = false;
    if (!availableBlock(nested)) {
      return StatusCode.INVALID_EXTERNAL_INPUT;
    }
    nestedCommand = nested;
    nestedTable = nested.table();
    nestedProjection = nested.projection();
    nestedProjectionType = nested.projectionType();
    nestedCorrelated = nested.isCorrelated();
    return StatusCode.OK;
  }

  private boolean availableBlock(BoundSqlQuery.Block block) {
    return block != null
        && (preparingCandidate
            ? block.table() != null
            : query.isExecutable()
                && block.isBound(query.executableGeneration()));
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
    predicates.reset();
    for (int index = 0; index < scalar.predicateCount(); index++) {
      if (!matchesScalarPredicate(
          scalar,
          primaryKey,
          source,
          outerPrimaryKey,
          outerSource,
          index,
          nestedMembershipPredicate,
          nestedMembershipNegated,
          nestedMembershipBank,
          nestedMembershipValueCount,
          nestedMembershipHasNull)) {
        return false;
      }
    }
    return true;
  }

  private boolean matchesScalarPredicate(
      BoundSqlQuery.Block scalar,
      long primaryKey,
      HeapRowResult source,
      long outerPrimaryKey,
      HeapRowResult outerSource,
      int index,
      int nestedMembershipPredicate,
      boolean nestedMembershipNegated,
      int nestedMembershipBank,
      int nestedMembershipValueCount,
      boolean nestedMembershipHasNull) {
    int predicateColumn = scalarPredicateColumn(index);
    boolean nullValue = isNull(source, nestedTable, predicateColumn);
    if (scalar.predicates().isTruth(index)) {
      long value = nullValue ? 0 : readColumn(primaryKey, source, predicateColumn);
      return predicates.matchesTruth(
          scalar.predicates(), index, nullValue, value);
    }
    if (scalar.isNullPredicate(index)) {
      return nullValue != scalar.isNullPredicateNegated(index);
    }
    if (nullValue) {
      return false;
    }
    long value = readColumn(primaryKey, source, predicateColumn);
    int block = scalar.blockIndex();
    if (isDynamicScalarPredicate(block, index)) {
      return matchesDynamicScalar(scalar, index, predicateColumn, value, block);
    }
    if (index == nestedMembershipPredicate) {
      return matchesNestedMembership(
          source,
          predicateColumn,
          value,
          nestedMembershipNegated,
          nestedMembershipBank,
          nestedMembershipValueCount,
          nestedMembershipHasNull);
    }
    if (scalar.isColumnPredicate(index)) {
      return matchesScalarColumn(
          index,
          primaryKey,
          source,
          predicateColumn,
          outerPrimaryKey,
          outerSource);
    }
    return matchesLiteralComparison(
        primaryKey, source, nestedTable, predicateColumn, scalar, index);
  }

  private boolean isDynamicScalarPredicate(int block, int index) {
    return block >= 0
        && query.hasScalarPredicate(block)
        && query.scalarPredicate(block) == index;
  }

  private boolean matchesDynamicScalar(
      BoundSqlQuery.Block scalar,
      int index,
      int predicateColumn,
      long value,
      int block) {
    return !dynamicScalarNulls[block]
        && SqlTypeDescriptor.canCompare(
            nestedTable.typeDescriptor(predicateColumn),
            dynamicScalarTypes[block])
        && expressions.matchesComparison(
            value,
            nestedTable.typeDescriptor(predicateColumn),
            scalar.comparison(index),
            dynamicScalarValues[block],
            dynamicScalarTypes[block]);
  }

  private boolean matchesNestedMembership(
      HeapRowResult source,
      int predicateColumn,
      long value,
      boolean negated,
      int bank,
      int count,
      boolean hasNull) {
    boolean equal = false;
    for (int candidate = 0; candidate < count; candidate++) {
      if (isTextType(membershipResultType(bank, -1))
          ? membershipTextEquals(source, predicateColumn, bank, -1, candidate)
          : expressions.compareExact(
              value,
              nestedTable.typeDescriptor(predicateColumn),
              membershipValue(bank, -1, candidate),
              membershipResultType(bank, -1)) == 0) {
        equal = true;
        break;
      }
    }
    return equal != negated && (equal || !hasNull);
  }

  private boolean matchesScalarColumn(
      int index,
      long primaryKey,
      HeapRowResult source,
      int predicateColumn,
      long outerPrimaryKey,
      HeapRowResult outerSource) {
    boolean outer = scalarPredicateValueOuter(index);
    HeapRowResult valueSource = outer ? outerSource : source;
    TableDefinition valueTable = outer ? bound.table : nestedTable;
    int valueColumn = scalarPredicateValueColumn(index);
    return valueSource != null
        && !isNull(valueSource, valueTable, valueColumn)
        && equalColumns(
            primaryKey,
            source,
            nestedTable,
            predicateColumn,
            outer ? outerPrimaryKey : primaryKey,
            valueSource,
            valueTable,
            valueColumn);
  }

  private static final class NestedChainState {
    private boolean commandEnabled;
    private int candidates;
    private int candidateCount;
    private boolean candidateHasNull;

    private void reset() {
      commandEnabled = true;
      candidates = 0;
      candidateCount = 0;
      candidateHasNull = false;
    }
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
