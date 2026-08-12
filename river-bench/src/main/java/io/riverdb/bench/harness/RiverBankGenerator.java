package io.riverdb.bench.harness;

import java.nio.charset.StandardCharsets;
import java.util.SplittableRandom;

/** Partial in-memory tiny workload-shape generator; it contains no external data. */
public final class RiverBankGenerator {
  public static final int VERSION = 1;
  public static final int MAX_RECORDS = 10_000;

  private static final String[] OPERATIONS = {
      "transfer", "deposit", "withdrawal", "card_authorize", "card_reverse"
  };

  public GenerationResult generate(long seed, int recordCount) {
    if (recordCount < 1 || recordCount > MAX_RECORDS) {
      return GenerationResult.invalidRecordCount();
    }
    SplittableRandom random = new SplittableRandom(seed);
    StringBuilder output = new StringBuilder(128 + recordCount * 80);
    output.append("sequence\toperation\tbranch_id\tfrom_account_id\tto_account_id")
        .append("\tamount_minor\tidempotency_key\n");
    for (int sequence = 0; sequence < recordCount; sequence++) {
      int branch = random.nextInt(1, 33);
      int from = random.nextInt(1, 8_193);
      int to = random.nextInt(1, 8_192);
      if (to >= from) {
        to++;
      }
      long amount = random.nextLong(1, 250_001);
      String operation = OPERATIONS[random.nextInt(OPERATIONS.length)];
      output.append(sequence).append('\t')
          .append(operation).append('\t')
          .append(branch).append('\t')
          .append(from).append('\t')
          .append(to).append('\t')
          .append(amount).append('\t')
          .append("rb-v1-").append(Long.toUnsignedString(seed, 16)).append('-')
          .append(sequence).append('\n');
    }
    byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
    WorkloadArtifact artifact = new WorkloadArtifact(
        "riverbank_tiny",
        VERSION,
        seed,
        recordCount,
        "schema=partial_tiny_v1;branches=32;accounts=8192;"
            + "amount_minor=1..250000;distribution=uniform",
        bytes,
        WorkloadChecksums.sha256(bytes));
    return GenerationResult.generated(artifact);
  }
}
