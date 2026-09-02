package io.riverdb.engine.api;

import io.riverdb.base.error.StatusCode;

/** Forward-DAG dominators used to prove prior results exist on every path. */
final class TransactionProgramDominators {
  private final int steps;
  private final int[] counts;
  private final int[] offsets;
  private final int[] predecessors;
  private final int[] parents;
  private final int[] depths;
  private final int[] ancestors;

  TransactionProgramDominators(int steps, int edgeCount) {
    this.steps = steps;
    counts = new int[steps];
    offsets = new int[steps + 1];
    predecessors = new int[edgeCount];
    parents = new int[steps];
    depths = new int[steps];
    ancestors = new int[TransactionProgramValidationSizing.multiply(
        TransactionProgramValidationSizing.levels(steps), steps)];
  }

  static long retainedBytes(int steps, int edgeCount) {
    int levels = TransactionProgramValidationSizing.levels(steps);
    int ancestorCount = TransactionProgramValidationSizing.multiply(levels, steps);
    if (steps < 0 || edgeCount < 0 || ancestorCount < 0) return -1;
    long bytes = 0;
    bytes = add(bytes, TransactionProgramStorage.arrayBytes(steps, Integer.BYTES));
    bytes = add(bytes, TransactionProgramStorage.arrayBytes(steps + 1, Integer.BYTES));
    bytes = add(bytes, TransactionProgramStorage.arrayBytes(edgeCount, Integer.BYTES));
    bytes = add(bytes, TransactionProgramStorage.arrayBytes(steps, Integer.BYTES));
    bytes = add(bytes, TransactionProgramStorage.arrayBytes(steps, Integer.BYTES));
    bytes = add(bytes, TransactionProgramStorage.arrayBytes(ancestorCount, Integer.BYTES));
    return bytes;
  }

  void build(TransactionProgram program) {
    countEdges(program);
    prefixCounts();
    fillEdges(program);
    buildParents();
  }

  StatusCode validateReferences(TransactionProgram program) {
    for (int target = 0; target < steps; target++) {
      StatusCode status = validateExpressions(program, target);
      if (!status.isOk()) return status;
    }
    return StatusCode.OK;
  }

  private void countEdges(TransactionProgram program) {
    for (int source = 0; source < steps; source++) {
      count(source + 1, steps);
      count(program.falseTarget(source), steps);
      count(program.emptyTarget(source), steps);
    }
  }

  private void count(int target, int totalSteps) {
    if (target >= 0 && target < totalSteps && target < steps) counts[target]++;
  }

  private void prefixCounts() {
    int total = 0;
    for (int step = 0; step < steps; step++) {
      offsets[step] = total;
      total += counts[step];
      counts[step] = offsets[step];
    }
    offsets[steps] = total;
  }

  private void fillEdges(TransactionProgram program) {
    for (int source = 0; source < steps; source++) {
      fill(source + 1, steps, source);
      fill(program.falseTarget(source), steps, source);
      fill(program.emptyTarget(source), steps, source);
    }
  }

  private void fill(int target, int totalSteps, int source) {
    if (target >= 0 && target < totalSteps && target < steps) {
      predecessors[counts[target]++] = source;
    }
  }

  private void buildParents() {
    for (int step = 0; step < steps; step++) {
      int parent = -1;
      if (step > 0) {
        int first = offsets[step];
        int last = offsets[step + 1];
        if (first < last) parent = predecessors[first];
        for (int index = first + 1; index < last; index++) {
          parent = lowestCommonAncestor(parent, predecessors[index]);
        }
      }
      parents[step] = parent;
      depths[step] = parent < 0 ? 0 : depths[parent] + 1;
      setAncestors(step);
    }
  }

  private void setAncestors(int step) {
    ancestors[step] = parents[step];
    int levels = ancestors.length / steps;
    for (int level = 1; level < levels; level++) {
      int parent = ancestors[(level - 1) * steps + step];
      ancestors[level * steps + step] = parent < 0
          ? -1 : ancestors[(level - 1) * steps + parent];
    }
  }

  private StatusCode validateExpressions(TransactionProgram program, int target) {
    int first = program.firstParameter(target);
    int end = first + program.parameterCount(target);
    for (int parameter = first; parameter < end; parameter++) {
      StatusCode status = validateExpression(program, program.parameterExpression(parameter), target);
      if (!status.isOk()) return status;
    }
    int guard = program.guardExpression(target);
    return guard < 0 ? StatusCode.OK : validateExpression(program, guard, target);
  }

  private StatusCode validateExpression(TransactionProgram program, int expression, int target) {
    int first = program.expressionFirstNode(expression);
    int end = first + program.expressionNodeCount(expression);
    for (int node = first; node < end; node++) {
      if (program.nodeOperator(node) != TransactionScalarOperator.RESULT) continue;
      int source = program.nodeFirst(node);
      int action = program.action(source);
      if (action != TransactionProgramAction.EXACT_ONE
          && action != TransactionProgramAction.ZERO_OR_ONE) {
        return StatusCode.INVALID_EXTERNAL_INPUT;
      }
      if (!dominates(source, target)) return StatusCode.INVALID_EXTERNAL_INPUT;
      if (action == TransactionProgramAction.ZERO_OR_ONE) {
        int empty = program.emptyTarget(source);
        if (empty < 0) empty = source + 1;
        if (target >= empty) return StatusCode.INVALID_EXTERNAL_INPUT;
      }
    }
    return StatusCode.OK;
  }

  private boolean dominates(int source, int target) {
    if (source < 0 || source >= steps || target < 0 || target >= steps) return false;
    int distance = depths[target] - depths[source];
    return distance >= 0 && lift(target, distance) == source;
  }

  private int lift(int node, int distance) {
    int level = 0;
    while (distance != 0 && node >= 0) {
      if ((distance & 1) != 0) node = ancestors[level * steps + node];
      distance >>>= 1;
      level++;
    }
    return node;
  }

  private int lowestCommonAncestor(int left, int right) {
    if (left < 0) return right;
    if (right < 0) return left;
    if (depths[left] < depths[right]) {
      int swap = left;
      left = right;
      right = swap;
    }
    left = lift(left, depths[left] - depths[right]);
    if (left == right) return left;
    int levels = ancestors.length / steps;
    for (int level = levels - 1; level >= 0; level--) {
      int leftAncestor = ancestors[level * steps + left];
      int rightAncestor = ancestors[level * steps + right];
      if (leftAncestor != rightAncestor) {
        left = leftAncestor;
        right = rightAncestor;
      }
    }
    return ancestors[left];
  }

  private static long add(long left, long right) {
    return right < 0 || left > Long.MAX_VALUE - right ? -1 : left + right;
  }
}
