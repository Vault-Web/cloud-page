package cloudpage.util;

import java.io.IOException;
import java.io.InputStream;

/** Delivers some bytes, then fails, like an upload whose connection drops part-way. */
public final class FailingInputStream extends InputStream {

  private final byte[] head;
  private int position;

  public FailingInputStream(String head) {
    this.head = head.getBytes();
  }

  @Override
  public int read() throws IOException {
    if (position < head.length) {
      return head[position++];
    }
    throw new IOException("connection dropped");
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) {
      return 0;
    }
    if (position < head.length) {
      int count = Math.min(length, head.length - position);
      System.arraycopy(head, position, buffer, offset, count);
      position += count;
      return count;
    }
    throw new IOException("connection dropped");
  }
}
