package io.riverdb.engine.testsupport.fault;

import io.riverdb.platform.file.ForceMode;
import java.util.Arrays;

final class FaultingDurableEntry {
  final byte[] volatileBytes;
  final byte[] durableBytes;
  String volatileName;
  String durableName;
  boolean volatileDirectory;
  boolean durableDirectory;
  int volatileSize;
  int durableSize;

  FaultingDurableEntry(int maxFileBytes) {
    volatileBytes = new byte[maxFileBytes];
    durableBytes = new byte[maxFileBytes];
  }

  void prepare(String name, boolean directory) {
    volatileName = name;
    durableName = null;
    volatileDirectory = directory;
    durableDirectory = false;
    volatileSize = 0;
    durableSize = 0;
    Arrays.fill(volatileBytes, (byte) 0);
    Arrays.fill(durableBytes, (byte) 0);
  }

  void publishNamespace() {
    durableName = volatileName;
    durableDirectory = volatileDirectory;
  }

  void publishContent(long startInclusive, long endExclusive, ForceMode mode) {
    int start = (int) Math.min(Math.max(0, startInclusive), volatileSize);
    int end = (int) Math.min(endExclusive, volatileSize);
    if (end < start) {
      end = start;
    }
    switch (mode) {
      case CONTENT -> {
        int publishedEnd = Math.min(end, durableSize);
        if (publishedEnd > start) {
          System.arraycopy(volatileBytes, start, durableBytes, start, publishedEnd - start);
        }
      }
      case CONTENT_AND_METADATA -> {
        System.arraycopy(volatileBytes, start, durableBytes, start, end - start);
        if (durableSize > volatileSize) {
          Arrays.fill(durableBytes, volatileSize, durableSize, (byte) 0);
        }
        durableSize = volatileSize;
      }
    }
  }

  void restoreDurable() {
    volatileName = durableName;
    volatileDirectory = durableDirectory;
    System.arraycopy(durableBytes, 0, volatileBytes, 0, durableSize);
    if (volatileSize > durableSize) {
      Arrays.fill(volatileBytes, durableSize, volatileSize, (byte) 0);
    }
    volatileSize = durableSize;
  }
}
