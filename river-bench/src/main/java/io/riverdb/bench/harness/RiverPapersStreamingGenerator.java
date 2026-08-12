package io.riverdb.bench.harness;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/** River-owned variable-width relational corpus; it contains no external article text. */
public final class RiverPapersStreamingGenerator {
  public static final int VERSION = 2;
  public static final long MAX_DOCUMENTS = 100_000_000L;
  public static final int MAX_AUTHORS = 10_000_000;
  public static final int MAX_INSTITUTIONS = 1_000_000;
  public static final int MAX_ABSTRACT_TOKENS = 4_096;
  public static final int AUTHORS_PER_DOCUMENT = 3;

  static final String AUTHORS_HEADER = "author_id\tdisplay_name\tinstitution_id\n";
  static final String DOCUMENTS_HEADER =
      "document_id\tdoi\ttitle\tinstitution_id\tpublished_epoch_day\tversion"
          + "\tcategory\tpublication_doi\tabstract_utf8\n";
  static final String DOCUMENT_AUTHORS_HEADER =
      "document_id\tauthor_id\tauthor_ordinal\n";

  private static final long EPOCH_DAY_2020 = 18_262L;
  private static final byte[][] WORDS = bytes(
      "adaptive", "analysis", "cell", "cohort", "enzyme", "genome", "model",
      "network", "protein", "signal", "tissue", "variant", "workflow", "résumé",
      "β-cell", "naïve");
  private static final byte[][] CATEGORY_WORDS = bytes(
      "cellular", "genomic", "neural", "microbial", "ecological", "biophysical");
  private static final String[] CATEGORIES = {
      "cell_biology", "genomics", "neuroscience", "microbiology", "ecology", "biophysics"
  };

  public StreamingWorkloadPlan plan(long seed, RiverPapersScale scale) {
    final long relationCount;
    try {
      relationCount = scale == null
          ? 0
          : Math.multiplyExact(scale.documentCount(), AUTHORS_PER_DOCUMENT);
    } catch (ArithmeticException exception) {
      return StreamingWorkloadPlan.countOverflow();
    }
    if (!valid(scale)) {
      return StreamingWorkloadPlan.invalidScale();
    }
    String common = "schema=riverpapers_v2;scale=" + scale.name()
        + ";documents=" + scale.documentCount()
        + ";authors=" + scale.authorCount()
        + ";institutions=" + scale.institutionCount()
        + ";authors_per_document=" + AUTHORS_PER_DOCUMENT
        + ";abstract_tokens=" + scale.minimumAbstractTokens() + ".."
        + scale.maximumAbstractTokens()
        + ";category_distribution=60_percent_hot_then_uniform"
        + ";token_distribution=70_percent_common_20_percent_category_10_percent_utf8"
        + ";external_dataset=none;full_text_index_claim=none";
    StreamingWorkloadArtifact authors = new StreamingWorkloadArtifact(
        "riverpapers_authors",
        VERSION,
        seed,
        scale.authorCount(),
        "riverpapers.authors.v2",
        common + ";table=authors;primary_key=author_id",
        (output, scratch) -> writeAuthors(output, scratch, seed, scale));
    StreamingWorkloadArtifact documents = new StreamingWorkloadArtifact(
        "riverpapers_documents",
        VERSION,
        seed,
        scale.documentCount(),
        "riverpapers.documents.v2",
        common + ";table=documents;primary_key=document_id;unique=doi"
            + ";indexes=category_published_day,publication_doi",
        (output, scratch) -> writeDocuments(output, scratch, seed, scale));
    StreamingWorkloadArtifact documentAuthors = new StreamingWorkloadArtifact(
        "riverpapers_document_authors",
        VERSION,
        seed,
        relationCount,
        "riverpapers.document_authors.v2",
        common + ";table=document_authors;primary_key=document_id_author_ordinal"
            + ";foreign_keys=document_id,author_id",
        (output, scratch) -> writeDocumentAuthors(output, scratch, seed, scale));
    return StreamingWorkloadPlan.planned(List.of(authors, documents, documentAuthors));
  }

  private static boolean valid(RiverPapersScale scale) {
    return scale != null
        && scale.name() != null
        && scale.name().matches("[a-z][a-z0-9_]{1,31}")
        && scale.documentCount() >= 1
        && scale.documentCount() <= MAX_DOCUMENTS
        && scale.authorCount() >= AUTHORS_PER_DOCUMENT
        && scale.authorCount() <= MAX_AUTHORS
        && scale.institutionCount() >= 1
        && scale.institutionCount() <= MAX_INSTITUTIONS
        && scale.minimumAbstractTokens() >= 1
        && scale.minimumAbstractTokens() <= scale.maximumAbstractTokens()
        && scale.maximumAbstractTokens() <= MAX_ABSTRACT_TOKENS;
  }

  private static StreamingGenerationResult writeAuthors(
      OutputStream output,
      byte[] scratch,
      long seed,
      RiverPapersScale scale) throws IOException {
    BoundedTsvOutput tsv = output(output, scratch);
    if (tsv == null) {
      return invalidScratch();
    }
    long rows = 0;
    try {
      tsv.appendAscii(AUTHORS_HEADER);
      for (int author = 1; author <= scale.authorCount(); author++) {
        tsv.appendLong(author);
        tsv.append('\t');
        tsv.appendAscii("River Author ");
        tsv.appendLong(author);
        tsv.append('\t');
        tsv.appendLong(1 + DeterministicValues.bounded(
            seed, author - 1L, 20, scale.institutionCount()));
        tsv.append('\n');
        rows++;
      }
      tsv.finish();
    } catch (ArithmeticException exception) {
      return overflow(rows);
    }
    return completed(rows, scale.authorCount(), tsv.byteCount());
  }

  private static StreamingGenerationResult writeDocuments(
      OutputStream output,
      byte[] scratch,
      long seed,
      RiverPapersScale scale) throws IOException {
    BoundedTsvOutput tsv = output(output, scratch);
    if (tsv == null) {
      return invalidScratch();
    }
    long rows = 0;
    try {
      tsv.appendAscii(DOCUMENTS_HEADER);
      int tokenRange = scale.maximumAbstractTokens() - scale.minimumAbstractTokens() + 1;
      for (long sequence = 0; sequence < scale.documentCount(); sequence++) {
        int category = category(seed, sequence);
        tsv.appendLong(sequence + 1);
        tsv.append('\t');
        tsv.appendAscii("10.9900/riverpapers-v2-");
        tsv.appendLong(seed);
        tsv.append('-');
        tsv.appendLong(sequence + 1);
        tsv.append('\t');
        tsv.appendAscii("River study ");
        tsv.appendUtf8(CATEGORY_WORDS[category]);
        tsv.appendAscii(" sequence ");
        tsv.appendLong(sequence + 1);
        tsv.append('\t');
        tsv.appendLong(1 + DeterministicValues.bounded(
            seed, sequence, 30, scale.institutionCount()));
        tsv.append('\t');
        tsv.appendLong(EPOCH_DAY_2020 + DeterministicValues.bounded(
            seed, sequence, 31, 1_827));
        tsv.append('\t');
        tsv.appendLong(1 + DeterministicValues.bounded(seed, sequence, 32, 4));
        tsv.append('\t');
        tsv.appendAscii(CATEGORIES[category]);
        tsv.append('\t');
        if (DeterministicValues.bounded(seed, sequence, 33, 5) == 0) {
          tsv.appendAscii("10.9901/river-publication-");
          tsv.appendLong(sequence + 1);
        }
        tsv.append('\t');
        int tokens = scale.minimumAbstractTokens() + (int) DeterministicValues.bounded(
            seed, sequence, 34, tokenRange);
        appendAbstract(tsv, seed, sequence, category, tokens);
        tsv.append('\n');
        rows++;
      }
      tsv.finish();
    } catch (ArithmeticException exception) {
      return overflow(rows);
    }
    return completed(rows, scale.documentCount(), tsv.byteCount());
  }

  private static StreamingGenerationResult writeDocumentAuthors(
      OutputStream output,
      byte[] scratch,
      long seed,
      RiverPapersScale scale) throws IOException {
    BoundedTsvOutput tsv = output(output, scratch);
    if (tsv == null) {
      return invalidScratch();
    }
    long expected = scale.documentCount() * AUTHORS_PER_DOCUMENT;
    long rows = 0;
    try {
      tsv.appendAscii(DOCUMENT_AUTHORS_HEADER);
      for (long sequence = 0; sequence < scale.documentCount(); sequence++) {
        long first = DeterministicValues.bounded(
            seed, sequence, 40, scale.authorCount());
        for (int ordinal = 1; ordinal <= AUTHORS_PER_DOCUMENT; ordinal++) {
          tsv.appendLong(sequence + 1);
          tsv.append('\t');
          tsv.appendLong(1 + (first + ordinal - 1) % scale.authorCount());
          tsv.append('\t');
          tsv.appendLong(ordinal);
          tsv.append('\n');
          rows++;
        }
      }
      tsv.finish();
    } catch (ArithmeticException exception) {
      return overflow(rows);
    }
    return completed(rows, expected, tsv.byteCount());
  }

  private static void appendAbstract(
      BoundedTsvOutput output,
      long seed,
      long sequence,
      int category,
      int tokenCount) throws IOException {
    for (int token = 0; token < tokenCount; token++) {
      if (token > 0) {
        output.append(' ');
      }
      long choice = DeterministicValues.bounded(seed, sequence, 100 + token, 100);
      if (choice < 70) {
        output.appendUtf8(WORDS[(int) DeterministicValues.bounded(
            seed, sequence, 4_200 + token, 13)]);
      } else if (choice < 90) {
        output.appendUtf8(CATEGORY_WORDS[category]);
      } else {
        output.appendUtf8(WORDS[13 + (int) DeterministicValues.bounded(
            seed, sequence, 8_300 + token, 3)]);
      }
    }
  }

  private static int category(long seed, long sequence) {
    long choice = DeterministicValues.bounded(seed, sequence, 29, 100);
    return choice < 60 ? 0 : 1 + (int) DeterministicValues.bounded(
        seed, sequence, 28, CATEGORIES.length - 1);
  }

  private static BoundedTsvOutput output(OutputStream output, byte[] scratch) {
    return BoundedTsvOutput.validScratch(scratch)
        ? new BoundedTsvOutput(output, scratch)
        : null;
  }

  private static StreamingGenerationResult invalidScratch() {
    return new StreamingGenerationResult(
        StreamingGenerationStatus.INVALID_SCRATCH_BUFFER, 0, 0);
  }

  private static StreamingGenerationResult overflow(long rows) {
    return new StreamingGenerationResult(
        StreamingGenerationStatus.BYTE_COUNT_OVERFLOW, rows, Long.MAX_VALUE);
  }

  private static StreamingGenerationResult completed(
      long actualRows,
      long expectedRows,
      long bytes) {
    StreamingGenerationStatus status = actualRows == expectedRows
        ? StreamingGenerationStatus.GENERATED
        : StreamingGenerationStatus.ROW_COUNT_MISMATCH;
    return new StreamingGenerationResult(status, actualRows, bytes);
  }

  private static byte[][] bytes(String... values) {
    byte[][] result = new byte[values.length][];
    for (int index = 0; index < values.length; index++) {
      result[index] = BoundedTsvOutput.utf8(values[index]);
    }
    return result;
  }
}
