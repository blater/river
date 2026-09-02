package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;
import java.util.Arrays;

/** Primitive retained storage shared by the program editor and frozen view. */
final class TransactionProgramStorage {
  static final long HEADER_BYTES = 224L;
  private static final long ARRAY_HEADER_BYTES = 16L;
  private final RetainedMemoryLease memory;
  long[] handles = new long[0];
  long[] minimumAffectedRows = new long[0];
  long[] maximumAffectedRows = new long[0];
  int[] actions = new int[0];
  int[] firstParameters = new int[0];
  int[] parameterCounts = new int[0];
  int[] guards = new int[0];
  int[] falseTargets = new int[0];
  int[] emptyTargets = new int[0];
  int[] firstCaptures = new int[0];
  int[] captureCounts = new int[0];
  int[] parameterExpressions = new int[0];
  int[] captureColumns = new int[0];
  int[] expressionFirstNodes = new int[0];
  int[] expressionNodeCounts = new int[0];
  int[] expressionDescriptors = new int[0];
  int[] nodeOperators = new int[0];
  int[] nodeFirst = new int[0];
  int[] nodeSecond = new int[0];
  int[] nodeDescriptors = new int[0];
  int[] referenceNext = new int[0];
  int[] referenceHeads = new int[0];
  int[] typeStack = new int[0];
  int stepCount;
  int parameterCount;
  int captureCount;
  int expressionCount;
  int nodeCount;
  int maximumArgumentSlot;
  int maximumStackDepth;
  int currentStep = -1;
  int currentExpression = -1;
  int currentStackDepth;
  boolean currentGuard;
  boolean frozen;
  long retainedBytes;
  long validationBytes;

  TransactionProgramStorage(RetainedMemoryLease memory) {
    this.memory = memory;
  }

  void reset() {
    stepCount = 0;
    parameterCount = 0;
    captureCount = 0;
    expressionCount = 0;
    nodeCount = 0;
    maximumArgumentSlot = 0;
    maximumStackDepth = 0;
    currentStep = -1;
    currentExpression = -1;
    currentStackDepth = 0;
    currentGuard = false;
    frozen = false;
  }

  StatusCode release() {
    StatusCode status = memory.resize(0);
    if (!status.isOk()) return status;
    reset();
    handles = new long[0];
    minimumAffectedRows = new long[0];
    maximumAffectedRows = new long[0];
    actions = new int[0];
    firstParameters = new int[0];
    parameterCounts = new int[0];
    guards = new int[0];
    falseTargets = new int[0];
    emptyTargets = new int[0];
    firstCaptures = new int[0];
    captureCounts = new int[0];
    parameterExpressions = new int[0];
    captureColumns = new int[0];
    expressionFirstNodes = new int[0];
    expressionNodeCounts = new int[0];
    expressionDescriptors = new int[0];
    nodeOperators = new int[0];
    nodeFirst = new int[0];
    nodeSecond = new int[0];
    nodeDescriptors = new int[0];
    referenceNext = new int[0];
    referenceHeads = new int[0];
    typeStack = new int[0];
    retainedBytes = 0;
    validationBytes = 0;
    return StatusCode.OK;
  }

  StatusCode finishFreeze() {
    long frozenBytes = retainedBytes - (long) typeStack.length * Integer.BYTES;
    StatusCode status = memory.resize(frozenBytes);
    if (!status.isOk()) return status;
    typeStack = new int[0];
    retainedBytes = frozenBytes;
    frozen = true;
    return StatusCode.OK;
  }

  StatusCode reserveValidation(long bytes) {
    if (bytes < 0 || bytes > Long.MAX_VALUE - retainedBytes) {
      return StatusCode.RESOURCE_EXHAUSTED;
    }
    StatusCode status = memory.resize(retainedBytes + bytes);
    if (status.isOk()) validationBytes = bytes;
    return status;
  }

  StatusCode releaseValidation() {
    if (validationBytes == 0) return StatusCode.OK;
    StatusCode status = memory.resize(retainedBytes);
    if (status.isOk()) validationBytes = 0;
    return status;
  }

  StatusCode ensureSteps(int needed) {
    if (needed <= handles.length) return StatusCode.OK;
    int size = growth(handles.length, needed);
    if (size < 0) return StatusCode.RESOURCE_EXHAUSTED;
    long charge = retainedWithoutSteps() + stepBytes(size);
    StatusCode status = memory.resize(charge);
    if (!status.isOk()) return status;
    try {
      long[] nextHandles = Arrays.copyOf(handles, size);
      long[] nextMinimumAffectedRows = Arrays.copyOf(minimumAffectedRows, size);
      long[] nextMaximumAffectedRows = Arrays.copyOf(maximumAffectedRows, size);
      int[] nextActions = Arrays.copyOf(actions, size);
      int[] nextFirstParameters = Arrays.copyOf(firstParameters, size);
      int[] nextParameterCounts = Arrays.copyOf(parameterCounts, size);
      int[] nextGuards = Arrays.copyOf(guards, size);
      int[] nextFalseTargets = Arrays.copyOf(falseTargets, size);
      int[] nextEmptyTargets = Arrays.copyOf(emptyTargets, size);
      int[] nextFirstCaptures = Arrays.copyOf(firstCaptures, size);
      int[] nextCaptureCounts = Arrays.copyOf(captureCounts, size);
      int[] nextReferenceHeads = Arrays.copyOf(referenceHeads, size);
      handles = nextHandles;
      minimumAffectedRows = nextMinimumAffectedRows;
      maximumAffectedRows = nextMaximumAffectedRows;
      actions = nextActions;
      firstParameters = nextFirstParameters;
      parameterCounts = nextParameterCounts;
      guards = nextGuards;
      falseTargets = nextFalseTargets;
      emptyTargets = nextEmptyTargets;
      firstCaptures = nextFirstCaptures;
      captureCounts = nextCaptureCounts;
      referenceHeads = nextReferenceHeads;
      retainedBytes = charge;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      memory.resize(retainedBytes);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode ensureParameters(int needed) {
    if (needed <= parameterExpressions.length) return StatusCode.OK;
    return growOne(parameterExpressions, growth(parameterExpressions.length, needed), true);
  }

  StatusCode ensureCaptures(int needed) {
    if (needed <= captureColumns.length) return StatusCode.OK;
    return growOne(captureColumns, growth(captureColumns.length, needed), false);
  }

  StatusCode ensureExpressions(int needed) {
    if (needed <= expressionFirstNodes.length) return StatusCode.OK;
    int size = growth(expressionFirstNodes.length, needed);
    if (size < 0) return StatusCode.RESOURCE_EXHAUSTED;
    long charge = retainedBytes + (long) (size - expressionFirstNodes.length) * 3 * Integer.BYTES;
    StatusCode status = memory.resize(charge);
    if (!status.isOk()) return status;
    try {
      int[] nextFirstNodes = Arrays.copyOf(expressionFirstNodes, size);
      int[] nextNodeCounts = Arrays.copyOf(expressionNodeCounts, size);
      int[] nextDescriptors = Arrays.copyOf(expressionDescriptors, size);
      expressionFirstNodes = nextFirstNodes;
      expressionNodeCounts = nextNodeCounts;
      expressionDescriptors = nextDescriptors;
      retainedBytes = charge;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      memory.resize(retainedBytes);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode ensureNodes(int needed) {
    if (needed <= nodeOperators.length) return StatusCode.OK;
    int size = growth(nodeOperators.length, needed);
    if (size < 0) return StatusCode.RESOURCE_EXHAUSTED;
    long charge = retainedBytes + (long) (size - nodeOperators.length) * 5 * Integer.BYTES;
    StatusCode status = memory.resize(charge);
    if (!status.isOk()) return status;
    try {
      int[] nextOperators = Arrays.copyOf(nodeOperators, size);
      int[] nextFirst = Arrays.copyOf(nodeFirst, size);
      int[] nextSecond = Arrays.copyOf(nodeSecond, size);
      int[] nextDescriptors = Arrays.copyOf(nodeDescriptors, size);
      int[] nextReferences = Arrays.copyOf(referenceNext, size);
      nodeOperators = nextOperators;
      nodeFirst = nextFirst;
      nodeSecond = nextSecond;
      nodeDescriptors = nextDescriptors;
      referenceNext = nextReferences;
      retainedBytes = charge;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      memory.resize(retainedBytes);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  StatusCode ensureTypeStack(int needed) {
    if (needed <= typeStack.length) return StatusCode.OK;
    int size = growth(typeStack.length, needed);
    if (size < 0) return StatusCode.RESOURCE_EXHAUSTED;
    long charge = retainedBytes + (long) (size - typeStack.length) * Integer.BYTES;
    StatusCode status = memory.resize(charge);
    if (!status.isOk()) return status;
    try {
      typeStack = Arrays.copyOf(typeStack, size);
      retainedBytes = charge;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      memory.resize(retainedBytes);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private StatusCode growOne(int[] current, int size, boolean parameters) {
    if (size < 0) return StatusCode.RESOURCE_EXHAUSTED;
    long charge = retainedBytes + (long) (size - current.length) * Integer.BYTES;
    StatusCode status = memory.resize(charge);
    if (!status.isOk()) return status;
    try {
      int[] next = Arrays.copyOf(current, size);
      if (parameters) parameterExpressions = next;
      else captureColumns = next;
      retainedBytes = charge;
      return StatusCode.OK;
    } catch (OutOfMemoryError failure) {
      memory.resize(retainedBytes);
      return StatusCode.RESOURCE_EXHAUSTED;
    }
  }

  private long retainedWithoutSteps() {
    return retainedBytes - stepBytes(handles.length);
  }

  static long stepBytes(int size) {
    return size == 0 ? 0
        : HEADER_BYTES + (long) size * (3 * Long.BYTES + 9 * Integer.BYTES);
  }

  static long arrayBytes(int length, int elementBytes) {
    return length == 0 ? 0 : ARRAY_HEADER_BYTES + (long) length * elementBytes;
  }

  static int growth(int current, int needed) {
    if (needed < 0) return -1;
    if (needed <= current) return current;
    int next = current == 0 ? 8 : current << 1;
    if (next <= current) return needed;
    return Math.max(next, needed);
  }
}
