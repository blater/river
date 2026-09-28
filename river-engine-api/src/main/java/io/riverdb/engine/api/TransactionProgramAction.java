package io.riverdb.engine.api;

/** Cardinality and result policy for one transaction-program step. */
public final class TransactionProgramAction {
  public static final int COMMAND = 1;
  public static final int EXACT_ONE = 2;
  public static final int ZERO_OR_ONE = 3;
  public static final int ROW_SET = 4;
  /** Select the zero-based ordinal from an ordered query; the final step expression is the ordinal. */
  public static final int ROW_AT = 5;

  private TransactionProgramAction() { }

  public static boolean isValid(int action) {
    return action >= COMMAND && action <= ROW_AT;
  }
}
