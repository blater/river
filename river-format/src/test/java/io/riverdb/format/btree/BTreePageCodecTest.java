package io.riverdb.format.btree;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.riverdb.base.error.StatusCode;
import io.riverdb.base.key.OrderedKey;
import io.riverdb.format.FormatBytes;
import io.riverdb.format.page.PageCodec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class BTreePageCodecTest {
  @Test
  void separatesLongLeafValuesFromInternalPageReferences() {
    ByteBuffer leafBytes = ByteBuffer.allocate(80).order(ByteOrder.BIG_ENDIAN);
    assertEquals(
        StatusCode.OK,
        BTreePageCodec.encodeHeader(
            leafBytes, 8, BTreePageCodec.TYPE_LEAF, 1, 0, OrderedKey.INFINITY_SPACE, 0));
    assertEquals(
        StatusCode.OK,
        BTreePageCodec.encodeLeaf(leafBytes, 56, 7, Long.MIN_VALUE, Long.MAX_VALUE));
    assertArrayEquals(
        HexFormat.of().parseHex(
            "5054425245564952030000000100000001000000180000000000000000000000"
                + "0000000000000000ffffff7f000000000000000000000080ffffffffffffff7f"
                + "0700000000000000"),
        Arrays.copyOfRange(leafBytes.array(), 8, 80));
    BTreePageHeader header = new BTreePageHeader();
    assertEquals(StatusCode.OK, BTreePageCodec.decodeHeader(leafBytes, 8, header));
    assertEquals(BTreePageCodec.TYPE_LEAF, header.type());
    BTreeLeafEntry leaf = new BTreeLeafEntry();
    assertEquals(StatusCode.OK, BTreePageCodec.decodeLeaf(leafBytes, 8, header, 0, leaf));
    assertEquals(Long.MAX_VALUE, leaf.logicalRowId());
    assertEquals(Long.MIN_VALUE, leaf.key());
    assertEquals(StatusCode.OK, BTreePageCodec.validatePage(leafBytes, 8, header));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        BTreePageCodec.decodeInternal(leafBytes, 8, header, 0, new BTreeInternalEntry()));

    ByteBuffer bytes = ByteBuffer.allocate(72).order(ByteOrder.BIG_ENDIAN);
    assertEquals(
        StatusCode.OK,
        BTreePageCodec.encodeHeader(
            bytes, 8, BTreePageCodec.TYPE_INTERNAL, 1, 1, 9, 100));
    assertEquals(
        StatusCode.OK,
        BTreePageCodec.encodeInternal(bytes, 56, 8, Long.MAX_VALUE, Integer.MAX_VALUE));
    assertArrayEquals(
        HexFormat.of().parseHex(
            "5054425245564952030000000200000001000000100000000100000000000000"
                + "64000000000000000900000000000000ffffffffffffff7fffffff7f08000000"),
        Arrays.copyOfRange(bytes.array(), 8, 72));
    BTreeInternalEntry internal = new BTreeInternalEntry();
    assertEquals(StatusCode.OK, BTreePageCodec.decodeHeader(bytes, 8, header));
    assertEquals(StatusCode.OK, BTreePageCodec.decodeInternal(bytes, 8, header, 0, internal));
    assertEquals(Integer.MAX_VALUE, internal.rightChildPageId());
    assertEquals(Long.MAX_VALUE, internal.key());
    assertEquals(StatusCode.OK, BTreePageCodec.validatePage(bytes, 8, header));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        BTreePageCodec.decodeLeaf(bytes, 8, header, 0, new BTreeLeafEntry()));
  }

  @Test
  void rejectsOldPageVersionsAndCrossDomainValues() {
    ByteBuffer bytes = ByteBuffer.allocate(96);
    assertEquals(
        StatusCode.OK,
        BTreePageCodec.encodeHeader(
            bytes, 0, BTreePageCodec.TYPE_LEAF, 0, 0, OrderedKey.INFINITY_SPACE, 0));
    FormatBytes.putInt(bytes, 8, 2);
    assertEquals(
        StatusCode.CORRUPTION,
        BTreePageCodec.decodeHeader(bytes, 0, new BTreePageHeader()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        BTreePageCodec.encodeLeaf(bytes, 48, 0, 1, 0));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        BTreePageCodec.encodeInternal(bytes, 48, 0, 1, 0));

    ByteBuffer fullPage = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    assertEquals(
        StatusCode.OK,
        BTreePageCodec.encodeHeader(
            fullPage,
            0,
            BTreePageCodec.TYPE_LEAF,
            BTreePageCodec.MAXIMUM_LEAF_ENTRIES,
            0,
            OrderedKey.INFINITY_SPACE,
            0));
    assertEquals(
        StatusCode.OK,
        BTreePageCodec.encodeHeader(
            fullPage,
            0,
            BTreePageCodec.TYPE_INTERNAL,
            BTreePageCodec.MAXIMUM_INTERNAL_ENTRIES,
            1,
            OrderedKey.INFINITY_SPACE,
            0));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        BTreePageCodec.encodeHeader(
            fullPage,
            0,
            BTreePageCodec.TYPE_LEAF,
            BTreePageCodec.MAXIMUM_LEAF_ENTRIES + 1,
            0,
            OrderedKey.INFINITY_SPACE,
            0));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        BTreePageCodec.encodeHeader(
            fullPage,
            0,
            BTreePageCodec.TYPE_INTERNAL,
            BTreePageCodec.MAXIMUM_INTERNAL_ENTRIES + 1,
            1,
            OrderedKey.INFINITY_SPACE,
            0));

    ByteBuffer emptyRoot = ByteBuffer.allocate(BTreePageCodec.HEADER_BYTES);
    assertEquals(
        StatusCode.OK,
        BTreePageCodec.encodeHeader(
            emptyRoot, 0, BTreePageCodec.TYPE_LEAF, 0, 0, OrderedKey.INFINITY_SPACE, 0));
    assertEquals(
        StatusCode.OK,
        BTreePageCodec.validatePage(emptyRoot, 0, new BTreePageHeader()));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        BTreePageCodec.encodeHeader(
            emptyRoot, 0, BTreePageCodec.TYPE_LEAF, 0, 0, 0, 10));
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        BTreePageCodec.encodeHeader(
            emptyRoot, 0, BTreePageCodec.TYPE_LEAF, 0, 1, OrderedKey.INFINITY_SPACE, 0));

    ByteBuffer reused = ByteBuffer.allocate(PageCodec.MAX_PAYLOAD_BYTES);
    Arrays.fill(reused.array(), (byte) 7);
    assertEquals(
        StatusCode.INVALID_EXTERNAL_INPUT,
        BTreePageCodec.initializePage(
            reused, 0, BTreePageCodec.TYPE_INTERNAL, 0, OrderedKey.INFINITY_SPACE, 0));
    assertEquals(7, reused.get(0));
    assertEquals(
        StatusCode.OK,
        BTreePageCodec.initializePage(
            reused, 0, BTreePageCodec.TYPE_LEAF, 0, OrderedKey.INFINITY_SPACE, 0));
    assertEquals(
        StatusCode.OK,
        BTreePageCodec.validatePage(reused, 0, new BTreePageHeader()));
  }

  @Test
  void validatesStrictOrderingFenceAndTypedValuesAcrossTheWholePage() {
    ByteBuffer bytes = ByteBuffer.allocate(128);
    assertEquals(
        StatusCode.OK,
        BTreePageCodec.encodeHeader(bytes, 0, BTreePageCodec.TYPE_LEAF, 2, 1, 0, 20));
    assertEquals(StatusCode.OK, BTreePageCodec.encodeLeaf(bytes, 48, 0, 10, 1));
    assertEquals(StatusCode.OK, BTreePageCodec.encodeLeaf(bytes, 72, 0, 15, 2));
    BTreePageHeader header = new BTreePageHeader();
    assertEquals(StatusCode.OK, BTreePageCodec.validatePage(bytes, 0, header));

    FormatBytes.putLong(bytes, 72, 10);
    assertEquals(StatusCode.CORRUPTION, BTreePageCodec.validatePage(bytes, 0, header));
    FormatBytes.putLong(bytes, 72, 9);
    assertEquals(StatusCode.CORRUPTION, BTreePageCodec.validatePage(bytes, 0, header));
    FormatBytes.putLong(bytes, 72, 20);
    assertEquals(StatusCode.CORRUPTION, BTreePageCodec.validatePage(bytes, 0, header));
    FormatBytes.putLong(bytes, 72, 15);
    FormatBytes.putLong(bytes, 80, 0);
    assertEquals(StatusCode.CORRUPTION, BTreePageCodec.validatePage(bytes, 0, header));
  }
}
