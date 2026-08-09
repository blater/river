package io.riverdb.bench.harness;

/** Explicit RiverBank cardinalities; labels describe intent rather than hardware sizing. */
public record RiverBankScale(
    String name,
    int branchCount,
    int accountCount,
    long transactionCount,
    int hotAccountCount) {
  public static RiverBankScale developerSmoke() {
    return new RiverBankScale("developer_smoke", 4, 128, 512, 8);
  }
}
