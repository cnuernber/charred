package charred;


import java.util.Iterator;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class CharReader implements AutoCloseable
{
  Supplier<char[]> buffers;
  char[] curBuffer;
  int curPos;

  public static class SingletonData implements Supplier<char[]> {
    char[] data;
    SingletonData(char[] _d) {
      data = _d;
    }
    public char[] get() {
      char[] retval = data;
      data = null;
      return retval;
    }
  }

  public CharReader(Supplier<char[]> _buf) {
    buffers = _buf;
    nextBuffer();
  }

  public CharReader(char[] data) {
    this(new SingletonData(data));
  }

  public CharReader(String data) {
    this(data.toCharArray());
  }

  public final char[] buffer() { return curBuffer; }
  public final int position() { return curPos; }
  public final void position(int pos) { curPos = pos; }
  public final int bufferLength() {
    return curBuffer != null ? curBuffer.length : 0;
  }
  public final int remaining() {
    return bufferLength() - curPos;
  }
  public final char eatwhite() {
    char[] buffer = curBuffer;
    while(buffer != null) {
      final int len = buffer.length;
      int pos = curPos;
      for(; pos < len && Character.isWhitespace(buffer[pos]); ++pos);
      if (pos < len) {
	final char retval = buffer[pos];
	position(pos+1);
	return retval;
      }
      buffer = nextBuffer();
    }
    return 0;
  }
  public boolean eof() { return curBuffer == null; }
  public final char[] nextBuffer() {
    char[] nextbuf;
    if (buffers != null)
      nextbuf = buffers.get();
    else
      nextbuf = null;

    if (nextbuf != null) {
      curBuffer = nextbuf;
      curPos = 0;
    } else {
      curBuffer = null;
      curPos = -1;
    }
    return nextbuf;
  }

  public final int read() {
    //common case
    if (remaining() > 0) {
      char retval = curBuffer[curPos];
      ++curPos;
      return retval;
    } else {
      nextBuffer();
      if (eof())
	return -1;
      return read();
    }
  }
  public final int read(char[] buffer, int off, int len) {
    //common case first
    int leftover = len;
    char[] srcBuf = curBuffer;
    while(leftover > 0 && srcBuf != null) {
      final int pos = curPos;
      final int rem = srcBuf.length - pos;
      int readlen = Math.min(rem,leftover);
      System.arraycopy(srcBuf, pos, buffer, off, readlen);
      off += readlen;
      leftover -= readlen;
      if (leftover == 0) {
	curPos = pos + readlen;
	return len;
      }
      srcBuf = nextBuffer();
    }
    return -1;
  }

  public final void unread() {
    --curPos;
    //Unread only works until you reach the beginning of the current buffer.  If you just
    //loaded a new buffer and read a char and then have to unread more than one character
    //then we need to allocate a new buffer and reset curPos and such.  Will implement
    //if needed.
    if (curPos < 0) {
      throw new RuntimeException("Unread too far - current buffer empty");
    }
  }

  final int readFrom(int pos) {
    curPos = pos;
    return read();
  }

  public void close() throws Exception {
    if (buffers != null) {
      if (buffers instanceof AutoCloseable) {
	((AutoCloseable)buffers).close();
      }
      buffers = null;
    }
  }
}
