package com.pingcap.tikv.codec;

import com.google.protobuf.ByteString;
import gnu.trove.list.array.TByteArrayList;
import io.netty.handler.codec.CodecException;
import java.lang.reflect.Field;
import java.nio.ByteOrder;
import sun.misc.Unsafe;

public class NewCodecDataInput {
  private static final Unsafe UNSAFE;
  private static final int BYTE_ARRAY_BASE_OFFSET;
  private static final boolean IS_LITTLE_ENDIAN;

  static {
    try {
      Field field = Unsafe.class.getDeclaredField("theUnsafe");
      field.setAccessible(true);
      UNSAFE = (Unsafe)field.get(null);
      BYTE_ARRAY_BASE_OFFSET = UNSAFE.arrayBaseOffset(byte[].class);
      IS_LITTLE_ENDIAN = ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN);
    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private final byte[] backingBuffer;
  private int pos;
  private final int count;
  private int mark;

  private void checkLength(int required) {
    if (backingBuffer.length - pos < required) {
      throw new CodecException("EOF reached");
    }
  }

  public NewCodecDataInput(ByteString data) {
    this(data.toByteArray());
  }

  public NewCodecDataInput(byte[] buf) {
    backingBuffer = buf;
    count = backingBuffer.length;
    mark = 0;
  }

  void readFully(byte[] b, int len) {
    System.arraycopy(backingBuffer, pos, b, 0, len);
    pos += len;
  }

  public void skipBytes(int n) {
    pos += n;
  }

  byte readByte() {
    byte b = backingBuffer[pos];
    pos ++;
    return b;
  }

  public int readUnsignedByte() {
    int r = backingBuffer[pos] & 0xFFFF;
    pos ++;
    return r;
  }

  public int readPartialUnsignedShort() {
    if (count > pos) {
      short r = UNSAFE.getShort(backingBuffer, (long) pos + BYTE_ARRAY_BASE_OFFSET);
      if (IS_LITTLE_ENDIAN) {
        r = Short.reverseBytes(r);
      }
      pos += 2;
      return r & 0xFFFF;
    }

    if (count - pos == 1) {
      int r = (backingBuffer[pos] & 0xFF) << 8;
      pos ++;
      return r;
    }

    return 0;
  }

  long readLong() {
    checkLength(8);
    long r = UNSAFE.getLong(backingBuffer, (long)pos + BYTE_ARRAY_BASE_OFFSET);
    if (IS_LITTLE_ENDIAN) {
      r = Long.reverseBytes(r);
    }
    pos += 8;
    return r;
  }

  final long readPartialLong() {
    if (available() >= 8) {
      return readLong();
    }

    int shift = 56;
    long r = 0;
    while (available() > 0 && shift >= 0) {
      r += (backingBuffer[pos] & 0xFF) << shift;
      pos++;
      shift -= 8;
    }
    return r;
  }

  private static final int GRP_SIZE = 8;
  private static final int MARKER = 0xFF;
  private static final byte PAD = (byte) 0x0;

  // readBytes decodes bytes which is encoded by EncodeBytes before,
  // returns the leftover bytes and decoded value if no error.
  public byte[] readBytes() {
    TByteArrayList cdo = new TByteArrayList(Math.min(available(), 256));
    while (true) {
      int padCount;
      int marker = backingBuffer[pos + GRP_SIZE] & 0xff;
      int curPos = pos;
      pos += GRP_SIZE + 1;
      padCount = MARKER - marker;

      if (padCount > GRP_SIZE) {
        throw new IllegalArgumentException("Wrong padding count");
      }
      int realGroupSize = GRP_SIZE - padCount;
      cdo.add(backingBuffer, curPos, realGroupSize);

      if (padCount != 0) {
        // Check validity of padding bytes.
        for (int i = realGroupSize; i < GRP_SIZE; i++) {
          if (backingBuffer[i + curPos] != 0) {
            throw new IllegalArgumentException();
          }
        }
        break;
      }
    }

    return cdo.toArray();
  }

  public int peekByte() {
    return backingBuffer[pos] & 0xFF;
  }

  public void mark(int givenPos) {
    mark = givenPos;
  }

  public void reset() {
    pos = mark;
  }

  public boolean eof() {
    return available() == 0;
  }

  public int size() {
    return backingBuffer.length;
  }

  public int available() {
    return count - pos;
  }

  public byte[] toByteArray() {
    return backingBuffer;
  }
}