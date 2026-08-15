package io.riverdb.sql;

/** Borrowed typed parameters consumed and copied during one parser invocation. */
public interface SqlParameterSource {
  int count();

  boolean isNull(int index);

  int typeDescriptorAt(int index);

  long valueAt(int index);

  int copyTextAt(int index, char[] target, int offset);
}
