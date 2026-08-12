package io.riverdb.bench.harness;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.SplittableRandom;

/** Partial in-memory tiny workload-shape generator; no abstracts are copied. */
public final class RiverPapersGenerator {
  public static final int VERSION = 1;
  public static final int MAX_RECORDS = 10_000;

  private static final String[] CATEGORIES = {
      "cell-biology", "genomics", "neuroscience", "microbiology", "ecology"
  };
  private static final String[] INSTITUTIONS = {
      "Aster Institute", "Beacon Laboratory", "Cedar University", "Delta Centre"
  };
  private static final String[] WORDS = {
      "adaptive", "cell", "cohort", "enzyme", "genome", "model", "network",
      "protein", "signal", "tissue", "variant", "workflow"
  };

  public GenerationResult generate(long seed, int recordCount) {
    if (recordCount < 1 || recordCount > MAX_RECORDS) {
      return GenerationResult.invalidRecordCount();
    }
    SplittableRandom random = new SplittableRandom(seed);
    StringBuilder output = new StringBuilder(128 + recordCount * 240);
    output.append("sequence\tdoi\ttitle\tinstitution\tdate\tversion\tcategory")
        .append("\tpublication_doi\tabstract\tauthors\n");
    LocalDate epoch = LocalDate.of(2020, 1, 1);
    for (int sequence = 0; sequence < recordCount; sequence++) {
      int wordCount = random.nextInt(12, 49);
      output.append(sequence).append('\t')
          .append("10.9000/river-v1-").append(Long.toUnsignedString(seed, 16))
          .append('-').append(sequence).append('\t')
          .append("Study of ").append(WORDS[random.nextInt(WORDS.length)])
          .append(" and ").append(WORDS[random.nextInt(WORDS.length)]).append('\t')
          .append(INSTITUTIONS[random.nextInt(INSTITUTIONS.length)]).append('\t')
          .append(epoch.plusDays(random.nextInt(2_192))).append('\t')
          .append(random.nextInt(1, 5)).append('\t')
          .append(CATEGORIES[random.nextInt(CATEGORIES.length)]).append('\t');
      if (random.nextInt(5) == 0) {
        output.append("10.9100/published-").append(sequence);
      }
      output.append('\t');
      appendWords(output, random, wordCount);
      output.append('\t')
          .append("Author ").append(random.nextInt(1, 501)).append(';')
          .append("Author ").append(random.nextInt(501, 1_001)).append('\n');
    }
    byte[] bytes = output.toString().getBytes(StandardCharsets.UTF_8);
    WorkloadArtifact artifact = new WorkloadArtifact(
        "riverpapers_tiny",
        VERSION,
        seed,
        recordCount,
        "schema=partial_tiny_v1;abstract_words=12..48;"
            + "publication_doi_null_rate=0.8;distribution=uniform",
        bytes,
        WorkloadChecksums.sha256(bytes));
    return GenerationResult.generated(artifact);
  }

  private static void appendWords(
      StringBuilder output,
      SplittableRandom random,
      int wordCount) {
    for (int word = 0; word < wordCount; word++) {
      if (word > 0) {
        output.append(' ');
      }
      output.append(WORDS[random.nextInt(WORDS.length)]);
    }
  }
}
