package io.riverdb.bench.harness;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

final class WorkloadGeneratorTest {
  @Test
  void riverBankMatchesPinnedSmallFixture() throws IOException {
    GenerationResult first = new RiverBankGenerator().generate(0x52_49_56_45_52L, 3);
    GenerationResult second = new RiverBankGenerator().generate(0x52_49_56_45_52L, 3);

    assertEquals(GenerationResult.Status.GENERATED, first.status());
    assertArrayEquals(fixture("riverbank-v1-seed-5249564552-count-3.tsv"), first.artifact().tsv());
    assertArrayEquals(first.artifact().tsv(), second.artifact().tsv());
    assertEquals(first.artifact().sha256(), second.artifact().sha256());
  }

  @Test
  void riverPapersMatchesPinnedSmallFixture() throws IOException {
    GenerationResult result = new RiverPapersGenerator().generate(0x50_41_50_45_52L, 3);

    assertEquals(GenerationResult.Status.GENERATED, result.status());
    assertArrayEquals(fixture("riverpapers-v1-seed-5041504552-count-3.tsv"),
        result.artifact().tsv());
  }

  @Test
  void seedsChangeWorkloadAndArtifactDefensivelyCopiesBytes() {
    GenerationResult first = new RiverBankGenerator().generate(1, 4);
    GenerationResult second = new RiverBankGenerator().generate(2, 4);
    byte[] callerCopy = first.artifact().tsv();
    callerCopy[0] = 'x';

    assertNotEquals(first.artifact().sha256(), second.artifact().sha256());
    assertNotEquals((byte) 'x', first.artifact().tsv()[0]);
  }

  @Test
  void generatorsRejectUnboundedCountsWithoutAllocatingArtifact() {
    GenerationResult bankZero = new RiverBankGenerator().generate(1, 0);
    GenerationResult bankLarge = new RiverBankGenerator().generate(
        1, RiverBankGenerator.MAX_RECORDS + 1);
    GenerationResult papersZero = new RiverPapersGenerator().generate(1, 0);
    GenerationResult papersLarge = new RiverPapersGenerator().generate(
        1, RiverPapersGenerator.MAX_RECORDS + 1);

    assertEquals(GenerationResult.Status.INVALID_RECORD_COUNT, bankZero.status());
    assertEquals(GenerationResult.Status.INVALID_RECORD_COUNT, bankLarge.status());
    assertEquals(GenerationResult.Status.INVALID_RECORD_COUNT, papersZero.status());
    assertEquals(GenerationResult.Status.INVALID_RECORD_COUNT, papersLarge.status());
    assertEquals(null, bankZero.artifact());
    assertEquals(null, papersZero.artifact());
  }

  private static byte[] fixture(String name) throws IOException {
    try (InputStream stream = WorkloadGeneratorTest.class.getResourceAsStream(name)) {
      assertNotNull(stream);
      return stream.readAllBytes();
    }
  }
}
