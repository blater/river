package io.riverdb.platform.file;

/** What an adapter can prove about a directory mutation when it returns. */
public enum DirectoryDurability {
  NOT_APPLIED,
  VISIBLE_NOT_DURABLE,
  DURABLE,
  UNKNOWN
}
