package io.riverdb.bench.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class StreamingWorkloadGeneratorTest {
  @Test
  void riverBankIsDeterministicAcrossCallerBufferSizes() throws IOException {
    RiverBankScale scale = new RiverBankScale("test", 4, 32, 256, 4);
    StreamingWorkloadPlan first = new RiverBankStreamingGenerator().plan(0x5249564552L, scale);
    StreamingWorkloadPlan second = new RiverBankStreamingGenerator().plan(0x5249564552L, scale);

    assertEquals(StreamingWorkloadPlan.Status.PLANNED, first.status());
    for (int index = 0; index < first.artifacts().size(); index++) {
      GeneratedBytes small = generate(first.artifacts().get(index), 64);
      GeneratedBytes large = generate(second.artifacts().get(index), 4_096);
      assertArrayEquals(small.bytes(), large.bytes());
      assertEquals(small.sha256(), large.sha256());
      assertEquals(first.artifacts().get(index).recordCount(), small.result().rowCount());
      assertEquals(small.bytes().length, small.result().byteCount());
      assertTrue(small.maximumWriteBytes() <= 64);
      assertTrue(large.maximumWriteBytes() <= 4_096);
    }
  }

  @Test
  void riverBankRowsMaintainKeysTimestampsTypesAndHotColdReferences() throws IOException {
    RiverBankScale scale = new RiverBankScale("references", 4, 64, 2_000, 4);
    List<StreamingWorkloadArtifact> artifacts =
        new RiverBankStreamingGenerator().plan(73, scale).artifacts();
    String accounts = text(generate(artifacts.get(0), 257).bytes());
    String transactions = text(generate(artifacts.get(1), 257).bytes());
    Set<Long> accountIds = firstColumn(accounts);
    Set<String> idempotencyKeys = new HashSet<>();
    int hotReferences = 0;
    int presentReferences = 0;

    assertEquals(scale.accountCount(), accountIds.size());
    for (String row : dataRows(transactions)) {
      String[] fields = row.split("\t", -1);
      long timestamp = Long.parseLong(fields[1]);
      assertTrue(timestamp >= 1_577_836_800_000L && timestamp < 1_735_689_600_000L);
      assertTrue(Set.of("transfer", "deposit", "withdrawal", "card_authorize", "card_reverse")
          .contains(fields[2]));
      for (int field : new int[] {3, 4}) {
        if (!fields[field].isEmpty()) {
          long account = Long.parseLong(fields[field]);
          assertTrue(accountIds.contains(account));
          presentReferences++;
          if (account <= scale.hotAccountCount()) {
            hotReferences++;
          }
        }
      }
      long amount = Long.parseLong(fields[5]);
      assertTrue(amount >= 1 && amount <= 250_000);
      assertTrue(idempotencyKeys.add(fields[6]));
    }
    assertTrue(hotReferences * 100L / presentReferences >= 70);
    assertTrue(hotReferences * 100L / presentReferences <= 90);
  }

  @Test
  void riverPapersIsDeterministicAndRelationallyConsistent() throws IOException {
    RiverPapersScale scale = new RiverPapersScale("test", 80, 37, 7, 12, 48);
    StreamingWorkloadPlan plan = new RiverPapersStreamingGenerator().plan(-91, scale);
    GeneratedBytes authors = generate(plan.artifacts().get(0), 64);
    GeneratedBytes documents = generate(plan.artifacts().get(1), 64);
    GeneratedBytes relations = generate(plan.artifacts().get(2), 64);
    GeneratedBytes documentsLarge = generate(plan.artifacts().get(1), 65_536);

    assertArrayEquals(documents.bytes(), documentsLarge.bytes());
    assertEquals(scale.authorCount(), authors.result().rowCount());
    assertEquals(scale.documentCount(), documents.result().rowCount());
    assertEquals(scale.documentCount() * RiverPapersStreamingGenerator.AUTHORS_PER_DOCUMENT,
        relations.result().rowCount());
    Set<Long> authorIds = firstColumn(text(authors.bytes()));
    Set<Long> documentIds = firstColumn(text(documents.bytes()));
    Set<String> dois = new HashSet<>();
    boolean sawUtf8 = false;
    for (String row : dataRows(text(documents.bytes()))) {
      String[] fields = row.split("\t", -1);
      assertTrue(dois.add(fields[1]));
      assertTrue(Integer.parseInt(fields[4]) >= 18_262);
      int tokens = fields[8].split(" ").length;
      assertTrue(tokens >= scale.minimumAbstractTokens());
      assertTrue(tokens <= scale.maximumAbstractTokens());
      sawUtf8 |= fields[8].contains("β-cell")
          || fields[8].contains("résumé")
          || fields[8].contains("naïve");
    }
    assertTrue(sawUtf8);
    for (String row : dataRows(text(relations.bytes()))) {
      String[] fields = row.split("\t", -1);
      assertTrue(documentIds.contains(Long.parseLong(fields[0])));
      assertTrue(authorIds.contains(Long.parseLong(fields[1])));
      assertTrue(Integer.parseInt(fields[2]) >= 1
          && Integer.parseInt(fields[2]) <= 3);
    }
  }

  @Test
  void seedsChangeBytesAndInvalidScratchDoesNotWrite() throws IOException {
    StreamingWorkloadArtifact first = new RiverBankStreamingGenerator()
        .plan(1, RiverBankScale.developerSmoke()).artifacts().get(0);
    StreamingWorkloadArtifact second = new RiverBankStreamingGenerator()
        .plan(2, RiverBankScale.developerSmoke()).artifacts().get(0);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    StreamingGenerationResult invalid = first.writeTo(output, new byte[63]);

    assertNotEquals(generate(first, 128).sha256(), generate(second, 128).sha256());
    assertEquals(StreamingGenerationStatus.INVALID_SCRATCH_BUFFER, invalid.status());
    assertEquals(0, output.size());
  }

  @Test
  void developerSmokeCardinalitiesBytesAndChecksumsArePinned() throws IOException {
    List<StreamingWorkloadArtifact> artifacts = new java.util.ArrayList<>();
    artifacts.addAll(new RiverBankStreamingGenerator()
        .plan(0x52_49_56_45_52L, RiverBankScale.developerSmoke()).artifacts());
    artifacts.addAll(new RiverPapersStreamingGenerator()
        .plan(0x50_41_50_45_52L, RiverPapersScale.developerSmoke()).artifacts());
    LinkedHashMap<String, PinnedArtifact> expected = new LinkedHashMap<>();
    expected.put("riverbank_accounts", new PinnedArtifact(
        128, 5_062, "91d1869c12ef8d59ee77fb78673c7f18823c56742467ccb1953aa268616814bf"));
    expected.put("riverbank_transactions", new PinnedArtifact(
        512, 30_679, "1e9c34738a056f232a10eca33732492f0801def4956eb5b266805c9cc5b63fc8"));
    expected.put("riverpapers_authors", new PinnedArtifact(
        48, 1_028, "6105504fe93e035cc7bfe39655c766b3dd563f742bb201fcf4ffa7edf3fddd1c"));
    expected.put("riverpapers_documents", new PinnedArtifact(
        64, 36_302, "b7b50b923318fef9bb4bd5250026f92e4d9b03727172734e05c0d130d440c911"));
    expected.put("riverpapers_document_authors", new PinnedArtifact(
        192, 1_510, "76a328784f75026b019cdc0cfcd7beb60c2887cef021edd05fc5d0a3d7e0763c"));

    assertEquals(expected.size(), artifacts.size());
    for (StreamingWorkloadArtifact artifact : artifacts) {
      GeneratedBytes actual = generate(artifact, 257);
      PinnedArtifact pinned = expected.get(artifact.name());
      assertEquals(pinned.rows(), actual.result().rowCount());
      assertEquals(pinned.bytes(), actual.result().byteCount());
      assertEquals(pinned.sha256(), actual.sha256());
    }
  }

  @Test
  void generatorsRejectZeroLimitsAndOverflowBeforeEmission() {
    RiverBankStreamingGenerator bank = new RiverBankStreamingGenerator();
    RiverPapersStreamingGenerator papers = new RiverPapersStreamingGenerator();

    assertEquals(StreamingWorkloadPlan.Status.INVALID_SCALE,
        bank.plan(1, new RiverBankScale("zero", 0, 0, 0, 0)).status());
    assertEquals(StreamingWorkloadPlan.Status.INVALID_SCALE,
        bank.plan(1, new RiverBankScale(
            "over", 1, RiverBankStreamingGenerator.MAX_ACCOUNTS + 1, 1, 1)).status());
    assertEquals(StreamingWorkloadPlan.Status.INVALID_SCALE,
        papers.plan(1, new RiverPapersScale("zero", 0, 0, 0, 0, 0)).status());
    assertEquals(StreamingWorkloadPlan.Status.INVALID_SCALE,
        papers.plan(1, new RiverPapersScale(
            "over", RiverPapersStreamingGenerator.MAX_DOCUMENTS + 1,
            3, 1, 1, 1)).status());
    assertEquals(StreamingWorkloadPlan.Status.COUNT_OVERFLOW,
        papers.plan(1, new RiverPapersScale(
            "overflow", Long.MAX_VALUE, 3, 1, 1, 1)).status());
    assertFalse(bank.plan(1, new RiverBankScale("bad-name", 1, 2, 1, 1))
        .status() == StreamingWorkloadPlan.Status.PLANNED);
  }

  private static GeneratedBytes generate(StreamingWorkloadArtifact artifact, int scratchBytes)
      throws IOException {
    MeasuringOutput output = new MeasuringOutput();
    StreamingGenerationResult result = artifact.writeTo(output, new byte[scratchBytes]);
    assertEquals(StreamingGenerationStatus.GENERATED, result.status());
    byte[] bytes = output.bytes();
    return new GeneratedBytes(
        result,
        bytes,
        WorkloadChecksums.sha256(bytes),
        output.maximumWriteBytes());
  }

  private static Set<Long> firstColumn(String tsv) {
    Set<Long> ids = new HashSet<>();
    for (String row : dataRows(tsv)) {
      ids.add(Long.parseLong(row.substring(0, row.indexOf('\t'))));
    }
    return ids;
  }

  private static String[] dataRows(String tsv) {
    String[] rows = tsv.split("\n");
    return java.util.Arrays.copyOfRange(rows, 1, rows.length);
  }

  private static String text(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private record GeneratedBytes(
      StreamingGenerationResult result,
      byte[] bytes,
      String sha256,
      int maximumWriteBytes) {
  }

  private record PinnedArtifact(long rows, long bytes, String sha256) {
  }

  private static final class MeasuringOutput extends OutputStream {
    private final ByteArrayOutputStream output = new ByteArrayOutputStream();
    private int maximumWriteBytes;

    @Override
    public void write(int value) {
      output.write(value);
      maximumWriteBytes = Math.max(maximumWriteBytes, 1);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      output.write(bytes, offset, length);
      maximumWriteBytes = Math.max(maximumWriteBytes, length);
    }

    byte[] bytes() {
      return output.toByteArray();
    }

    int maximumWriteBytes() {
      return maximumWriteBytes;
    }
  }
}
