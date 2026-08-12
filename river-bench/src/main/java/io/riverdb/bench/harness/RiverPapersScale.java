package io.riverdb.bench.harness;

/** Explicit RiverPapers cardinalities and bounded text widths. */
public record RiverPapersScale(
    String name,
    long documentCount,
    int authorCount,
    int institutionCount,
    int minimumAbstractTokens,
    int maximumAbstractTokens) {
  public static RiverPapersScale developerSmoke() {
    return new RiverPapersScale("developer_smoke", 64, 48, 8, 24, 96);
  }
}
