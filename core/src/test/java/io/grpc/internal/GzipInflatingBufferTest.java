/*
 * Copyright 2017, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link GzipInflatingBuffer}. */
@RunWith(JUnit4.class)
public class GzipInflatingBufferTest {
  private static final String UNCOMPRESSABLE_FILE = "/io/grpc/internal/uncompressable.bin";

  private static final int GZIP_HEADER_MIN_SIZE = 10;
  private static final int GZIP_TRAILER_SIZE = 8;
  private static final int GZIP_HEADER_FLAG_INDEX = 3;

  /** GZIP header magic number. */
  public static final int GZIP_MAGIC = 0x8b1f;

  /*
   * File header flags.
   */
  private static final int FTEXT = 1; // Extra text
  private static final int FHCRC = 2; // Header CRC
  private static final int FEXTRA = 4; // Extra field
  private static final int FNAME = 8; // File name
  private static final int FCOMMENT = 16; // File comment

  private static final int TRUNCATED_DATA_SIZE = 10;

  private byte[] originalData;
  private byte[] gzippedData;
  private byte[] gzipHeader;
  private byte[] deflatedBytes;
  private byte[] gzipTrailer;
  private byte[] truncatedData;
  private byte[] gzippedTruncatedData;

  private GzipInflatingBuffer gzipInflatingBuffer;
  private CompositeReadableBuffer outputBuffer;

  @Before
  public void setUp() {
    gzipInflatingBuffer = new GzipInflatingBuffer();
    outputBuffer = new CompositeReadableBuffer();
    try {
      InputStream inputStream = getClass().getResourceAsStream(UNCOMPRESSABLE_FILE);

      ByteArrayOutputStream originalDataOutputStream = new ByteArrayOutputStream();
      ByteArrayOutputStream gzippedOutputStream = new ByteArrayOutputStream();
      OutputStream gzippingOutputStream = new GZIPOutputStream(gzippedOutputStream);

      byte[] buffer = new byte[512];
      int n;
      while ((n = inputStream.read(buffer)) > 0) {
        originalDataOutputStream.write(buffer, 0, n);
        gzippingOutputStream.write(buffer, 0, n);
      }
      gzippingOutputStream.close();
      gzippedData = gzippedOutputStream.toByteArray();
      originalData = originalDataOutputStream.toByteArray();
      gzipHeader = Arrays.copyOf(gzippedData, GZIP_HEADER_MIN_SIZE);
      deflatedBytes =
          Arrays.copyOfRange(
              gzippedData, GZIP_HEADER_MIN_SIZE, gzippedData.length - GZIP_TRAILER_SIZE);
      gzipTrailer =
          Arrays.copyOfRange(
              gzippedData, gzippedData.length - GZIP_TRAILER_SIZE, gzippedData.length);

      truncatedData = Arrays.copyOf(originalData, TRUNCATED_DATA_SIZE);
      ByteArrayOutputStream truncatedGzippedOutputStream = new ByteArrayOutputStream();
      OutputStream smallerGzipCompressingStream =
          new GZIPOutputStream(truncatedGzippedOutputStream);
      smallerGzipCompressingStream.write(truncatedData);
      smallerGzipCompressingStream.close();
      gzippedTruncatedData = truncatedGzippedOutputStream.toByteArray();
    } catch (Exception e) {
      throw new RuntimeException("Failed to set up compressed data", e);
    }
  }

  @After
  public void tearDown() {
    gzipInflatingBuffer.close();
    outputBuffer.close();
  }

  @Test
  public void gzipInflateWorks() throws Exception {
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));

    assertEquals(
        gzippedData.length, gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer));
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
  }

  @Test
  public void splitGzipStreamWorks() throws Exception {
    int initialBytes = 100;

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData, 0, initialBytes));
    assertEquals(initialBytes, gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer));
    assertTrue("inflated bytes expected", outputBuffer.readableBytes() > 0);

    gzipInflatingBuffer.addGzippedBytes(
        ReadableBuffers.wrap(gzippedData, initialBytes, gzippedData.length - initialBytes));
    assertEquals(
        gzippedData.length - initialBytes,
        gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer));
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
  }

  @Test
  public void concatenatedStreamsWorks() throws Exception {
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedTruncatedData));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedTruncatedData));

    assertEquals(
        gzippedData.length, gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer));
    assertEquals(
        gzippedTruncatedData.length,
        gzipInflatingBuffer.inflateBytes(truncatedData.length, outputBuffer));
    assertEquals(
        gzippedData.length, gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer));
    assertEquals(
        gzippedTruncatedData.length,
        gzipInflatingBuffer.inflateBytes(truncatedData.length, outputBuffer));
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
    assertTrue("inflated data does not match", bufferStartsWithData(truncatedData));
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
    assertTrue("inflated data does not match", bufferStartsWithData(truncatedData));
  }

  @Test
  public void inflateBytesWithSecondRequest_doesNotOverflow() throws Exception {
    gzipInflatingBuffer.inflateBytes(1, outputBuffer);
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));

    assertEquals(
        gzippedData.length, gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer));
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
  }

  @Test
  public void requestingTooManyBytesStillReturnsEndOfBlock() throws Exception {
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));

    assertEquals(
        gzippedData.length,
        gzipInflatingBuffer.inflateBytes(2 * originalData.length, outputBuffer));
    assertTrue(gzipInflatingBuffer.isStalled());
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
  }

  @Test
  public void decreasingNumberOfBytesRequested_obeysCurrentRequestLimit() throws Exception {
    gzipInflatingBuffer.inflateBytes(10, outputBuffer);
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedTruncatedData));
    gzipInflatingBuffer.inflateBytes(1, outputBuffer);

    assertEquals(1, outputBuffer.readableBytes());
  }

  @Test
  public void closeStopsDecompression() throws Exception {
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));

    gzipInflatingBuffer.inflateBytes(1, outputBuffer);
    gzipInflatingBuffer.close();
    try {
      gzipInflatingBuffer.inflateBytes(1, outputBuffer);
      fail("Expected IllegalStateException");
    } catch (IllegalStateException expectedException) {
      assertEquals("GzipInflatingBuffer is closed", expectedException.getMessage());
    }
  }

  @Test
  public void isStalledReturnsTrueAtEndOfStream() throws Exception {
    int bytesToWithhold = 10;

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));
    gzipInflatingBuffer.inflateBytes(originalData.length - bytesToWithhold, outputBuffer);
    assertFalse("gzipInflatingBuffer is stalled", gzipInflatingBuffer.isStalled());

    gzipInflatingBuffer.inflateBytes(bytesToWithhold, outputBuffer);
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
    assertTrue("gzipInflatingBuffer is not stalled", gzipInflatingBuffer.isStalled());
  }

  @Test
  public void isStalledReturnsFalseBetweenStreams() throws Exception {
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));

    gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
    assertFalse("gzipInflatingBuffer is stalled", gzipInflatingBuffer.isStalled());

    gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
    assertTrue("gzipInflatingBuffer is not stalled", gzipInflatingBuffer.isStalled());
  }

  @Test
  public void isStalledReturnsFalseBetweenSmallStreams() throws Exception {
    // Use small streams to make sure that they all fit in the inflater buffer
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedTruncatedData));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedTruncatedData));

    gzipInflatingBuffer.inflateBytes(truncatedData.length, outputBuffer);
    assertTrue("inflated data does not match", bufferStartsWithData(truncatedData));
    assertFalse("gzipInflatingBuffer is stalled", gzipInflatingBuffer.isStalled());

    gzipInflatingBuffer.inflateBytes(truncatedData.length, outputBuffer);
    assertTrue("inflated data does not match", bufferStartsWithData(truncatedData));
    assertTrue("gzipInflatingBuffer is not stalled", gzipInflatingBuffer.isStalled());
  }

  @Test
  public void isStalledReturnsFalseWithPartialNextHeaderAvailable() throws Exception {
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedTruncatedData));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(new byte[1]));

    gzipInflatingBuffer.inflateBytes(truncatedData.length, outputBuffer);
    assertTrue("inflated data does not match", bufferStartsWithData(truncatedData));
    assertFalse("gzipInflatingBuffer is stalled", gzipInflatingBuffer.isStalled());
  }

  @Test
  public void hasPartialData() throws Exception {
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(new byte[1]));

    gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
    assertTrue("partial data expected", gzipInflatingBuffer.hasPartialData());
  }

  @Test
  public void inflatingCompleteGzipStreamConsumesTrailer() throws Exception {
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedData));

    gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
    assertFalse("no partial data expected", gzipInflatingBuffer.hasPartialData());
  }

  @Test
  public void bytesConsumedForPartiallyInflatedBlock() throws Exception {
    int bytesToWithhold = 1;
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzippedTruncatedData));

    assertEquals(
        gzippedTruncatedData.length - bytesToWithhold - GZIP_TRAILER_SIZE,
        gzipInflatingBuffer.inflateBytes(truncatedData.length - bytesToWithhold, outputBuffer));
    assertEquals(
        bytesToWithhold + GZIP_TRAILER_SIZE,
        gzipInflatingBuffer.inflateBytes(bytesToWithhold, outputBuffer));
    assertTrue("inflated data does not match", bufferStartsWithData(truncatedData));
  }

  @Test
  public void getAndResetCompressedBytesConsumedReportsHeaderFlagBytes() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] =
        (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FTEXT | FHCRC | FEXTRA | FNAME | FCOMMENT);
    int len = 1025;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};
    byte[] fExtra = new byte[len];
    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    }
    ByteArrayOutputStream newHeader = new ByteArrayOutputStream();
    newHeader.write(gzipHeader);
    newHeader.write(fExtraLen);
    newHeader.write(fExtra);
    newHeader.write(zeroTerminatedBytes); // FNAME
    newHeader.write(zeroTerminatedBytes); // FCOMMENT
    byte[] headerCrc16 = getHeaderCrc16Bytes(newHeader.toByteArray());

    assertEquals(0, gzipInflatingBuffer.inflateBytes(1, outputBuffer));

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    assertEquals(gzipHeader.length, gzipInflatingBuffer.inflateBytes(1, outputBuffer));

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(fExtraLen));
    assertEquals(fExtraLen.length, gzipInflatingBuffer.inflateBytes(1, outputBuffer));

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(fExtra));
    assertEquals(fExtra.length, gzipInflatingBuffer.inflateBytes(1, outputBuffer));

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    assertEquals(zeroTerminatedBytes.length, gzipInflatingBuffer.inflateBytes(1, outputBuffer));

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    assertEquals(zeroTerminatedBytes.length, gzipInflatingBuffer.inflateBytes(1, outputBuffer));

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(headerCrc16));
    assertEquals(headerCrc16.length, gzipInflatingBuffer.inflateBytes(1, outputBuffer));

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    assertEquals(
        deflatedBytes.length, gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer));

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));
    assertEquals(gzipTrailer.length, gzipInflatingBuffer.inflateBytes(1, outputBuffer));
  }

  @Test
  public void wrongHeaderMagicShouldFail_onFirstTwoBytes() throws Exception {
    byte[] headerWithWrongGzipMagic = {(byte) GZIP_MAGIC, (byte) ~(GZIP_MAGIC >> 8)};
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(headerWithWrongGzipMagic));
    try {
      gzipInflatingBuffer.inflateBytes(1, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Not in GZIP format", expectedException.getMessage());
    }
  }

  @Test
  public void wrongHeaderCompressionMethodShouldFail_onFirstThreeBytes() throws Exception {
    byte[] headerWithWrongCompressionMethod = {
      (byte) GZIP_MAGIC, (byte) (GZIP_MAGIC >> 8), 7 /* Should be 8 */
    };
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(headerWithWrongCompressionMethod));
    try {
      gzipInflatingBuffer.inflateBytes(1, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Unsupported compression method", expectedException.getMessage());
    }
  }

  @Test
  public void allHeaderFlagsWork() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] =
        (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FTEXT | FHCRC | FEXTRA | FNAME | FCOMMENT);
    int len = 1025;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};
    byte[] fExtra = new byte[len];
    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    }
    ByteArrayOutputStream newHeader = new ByteArrayOutputStream();
    newHeader.write(gzipHeader);
    newHeader.write(fExtraLen);
    newHeader.write(fExtra);
    newHeader.write(zeroTerminatedBytes); // FNAME
    newHeader.write(zeroTerminatedBytes); // FCOMMENT
    byte[] headerCrc16 = getHeaderCrc16Bytes(newHeader.toByteArray());
    newHeader.write(headerCrc16);

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(newHeader.toByteArray()));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
  }

  @Test
  public void headerFTextFlagIsIgnored() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FTEXT);
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    assertEquals(
        gzippedData.length, gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer));
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
  }

  @Test
  public void headerFhcrcFlagWorks() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FHCRC);

    byte[] headerCrc16 = getHeaderCrc16Bytes(gzipHeader);

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(headerCrc16));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
  }

  @Test
  public void headerInvalidFhcrcFlagFails() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FHCRC);

    byte[] headerCrc16 = getHeaderCrc16Bytes(gzipHeader);
    headerCrc16[0] = (byte) ~headerCrc16[0];

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(headerCrc16));
    try {
      gzipInflatingBuffer.inflateBytes(1, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Corrupt GZIP header", expectedException.getMessage());
    }
  }

  @Test
  public void headerFExtraFlagWorks() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FEXTRA);

    int len = 1025;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};
    byte[] fExtra = new byte[len];

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(fExtraLen));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(fExtra));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
  }

  @Test
  public void headerFExtraFlagWithZeroLenWorks() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FEXTRA);
    byte[] fExtraLen = new byte[2];

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(fExtraLen));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
  }

  @Test
  public void headerFExtraFlagWithMissingExtraLenFails() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FEXTRA);

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    try {
      gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue(
          "wrong exception message",
          expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void headerFExtraFlagWithMissingExtraBytesFails() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FEXTRA);

    int len = 5;
    byte[] fExtraLen = {(byte) len, (byte) (len >> 8)};

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(fExtraLen));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    try {
      gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue(
          "wrong exception message",
          expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void headerFNameFlagWorks() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FNAME);
    int len = 1025;
    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    }

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
  }

  @Test
  public void headerFNameFlagWithMissingBytesFail() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FNAME);
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    try {
      gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue(
          "wrong exception message",
          expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void headerFCommentFlagWorks() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FCOMMENT);
    int len = 1025;
    byte[] zeroTerminatedBytes = new byte[len];
    for (int i = 0; i < len - 1; i++) {
      zeroTerminatedBytes[i] = 1;
    }

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(zeroTerminatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
    assertTrue("inflated data does not match", bufferStartsWithData(originalData));
  }

  @Test
  public void headerFCommentFlagWithMissingBytesFail() throws Exception {
    gzipHeader[GZIP_HEADER_FLAG_INDEX] = (byte) (gzipHeader[GZIP_HEADER_FLAG_INDEX] | FCOMMENT);

    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));
    try {
      gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue(
          "wrong exception message",
          expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  @Test
  public void wrongTrailerCrcShouldFail() throws Exception {
    gzipTrailer[0] = (byte) ~gzipTrailer[0];
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    try {
      gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Corrupt GZIP trailer", expectedException.getMessage());
    }
  }

  @Test
  public void wrongTrailerISizeShouldFail() throws Exception {
    gzipTrailer[GZIP_TRAILER_SIZE - 1] = (byte) ~gzipTrailer[GZIP_TRAILER_SIZE - 1];
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(deflatedBytes));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipTrailer));

    try {
      gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
      fail("Expected ZipException");
    } catch (ZipException expectedException) {
      assertEquals("Corrupt GZIP trailer", expectedException.getMessage());
    }
  }

  @Test
  public void invalidDeflateBlockShouldFail() throws Exception {
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(gzipHeader));
    gzipInflatingBuffer.addGzippedBytes(ReadableBuffers.wrap(new byte[10]));

    try {
      gzipInflatingBuffer.inflateBytes(originalData.length, outputBuffer);
      fail("Expected DataFormatException");
    } catch (DataFormatException expectedException) {
      assertTrue(
          "wrong exception message",
          expectedException.getMessage().startsWith("Inflater data format exception:"));
    }
  }

  private byte[] getHeaderCrc16Bytes(byte[] headerBytes) {
    CRC32 crc = new CRC32();
    crc.update(headerBytes);
    byte[] headerCrc16 = {(byte) crc.getValue(), (byte) (crc.getValue() >> 8)};
    return headerCrc16;
  }

  private boolean bufferStartsWithData(byte[] data) {
    byte[] buf = new byte[data.length];
    outputBuffer.readBytes(buf, 0, data.length);
    return Arrays.equals(data, buf);
  }
}
