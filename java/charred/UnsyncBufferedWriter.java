package charred;


import java.io.IOException;
import java.io.Writer;


/**
 * Unsynchronized buffered writer.  java.io.BufferedWriter and StringWriter take a lock
 * on every call which dominates the cost of writing small json tokens.
 */
public final class UnsyncBufferedWriter extends Writer {
  final Writer w;
  final char[] buf;
  int pos;

  public UnsyncBufferedWriter(Writer _w, int bufsize) {
    w = _w;
    buf = new char[Math.max(bufsize, 64)];
    pos = 0;
  }
  public UnsyncBufferedWriter(Writer _w) {
    this(_w, 8192);
  }

  final void flushBuffer() throws IOException {
    if (pos > 0) {
      w.write(buf, 0, pos);
      pos = 0;
    }
  }

  public final void write(int c) throws IOException {
    if (pos == buf.length)
      flushBuffer();
    buf[pos++] = (char)c;
  }

  public final void write(char[] data, int off, int len) throws IOException {
    if (len > buf.length - pos) {
      flushBuffer();
      if (len > buf.length) {
	w.write(data, off, len);
	return;
      }
    }
    System.arraycopy(data, off, buf, pos, len);
    pos += len;
  }

  public final void write(String data, int off, int len) throws IOException {
    if (len > buf.length - pos) {
      flushBuffer();
      if (len > buf.length) {
	w.write(data, off, len);
	return;
      }
    }
    data.getChars(off, off + len, buf, pos);
    pos += len;
  }

  public final void write(String data) throws IOException {
    write(data, 0, data.length());
  }

  //Long.MIN_VALUE has 20 characters.
  public final void writeLong(long v) throws IOException {
    if (v == Long.MIN_VALUE) {
      write("-9223372036854775808");
      return;
    }
    if (buf.length - pos < 20)
      flushBuffer();
    final char[] b = buf;
    int p = pos;
    if (v < 0) {
      b[p++] = '-';
      v = -v;
    }
    int nDigits = 1;
    for (long t = v; t >= 10; t /= 10) ++nDigits;
    int idx = p + nDigits;
    p = idx;
    do {
      b[--idx] = (char)('0' + (v % 10));
      v /= 10;
    } while (v != 0);
    pos = p;
  }

  public final void flush() throws IOException {
    flushBuffer();
    w.flush();
  }

  public final void close() throws IOException {
    flushBuffer();
    w.close();
  }
}
