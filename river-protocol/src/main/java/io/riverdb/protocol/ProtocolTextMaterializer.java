package io.riverdb.protocol;

/** Materializes validated SQL text, with a test provider for allocation-failure injection. */
interface ProtocolTextMaterializer {
  String materialize(char[] source, int offset, int length);
}
