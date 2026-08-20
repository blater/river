package io.riverdb.engine.sql;

/** Bound nested-query correlation shape shared by its execution owners. */
final class SqlNestedTopology {
  private boolean scalar;
  private boolean existence;
  private boolean membership;
  private boolean nestedChain;
  private boolean recursiveChain;
  private boolean rootCorrelated;

  void reset() {
    scalar = false;
    existence = false;
    membership = false;
    nestedChain = false;
    recursiveChain = false;
    rootCorrelated = false;
  }

  void set(
      boolean scalarValue,
      boolean existenceValue,
      boolean membershipValue,
      boolean nestedChainValue,
      boolean recursiveChainValue,
      boolean rootCorrelatedValue) {
    scalar = scalarValue;
    existence = existenceValue;
    membership = membershipValue;
    nestedChain = nestedChainValue;
    recursiveChain = recursiveChainValue;
    rootCorrelated = rootCorrelatedValue;
  }

  boolean scalar() { return scalar; }
  boolean existence() { return existence; }
  boolean membership() { return membership; }
  boolean nestedChain() { return nestedChain; }
  boolean recursiveChain() { return recursiveChain; }
  boolean rootCorrelated() { return rootCorrelated; }
}
