package charred;


import java.io.Reader;
import java.io.IOException;


public class LineNumberReader {
  final char[] buffer;
  final Reader reader;
  int position = 0;
  int len = 0;
  int line = 1;
  int column = 1;
  int lastColumn = 0;
  public LineNumberReader(Reader r, int buflen) {
    this.reader = r;
    this.buffer = new char[buflen];
  }
  public LineNumberReader(Reader r) {
    this(r, 2056);
  }
  public int getLineNumber() { return this.line; }
  public int getColumnNumber() { return this.column; }
  public char update(char b) {
    if(b == '\n') {
      ++line;
      lastColumn = column;
      column = 1;
    } else {
      ++column;
    }
    return b;
  }
  public int read() throws IOException {
    final char[] b = buffer();
    if(b == null ) {
      return -1;      
    }
    return update(buffer[position++]);
  }
  public void unread() {
    if(position == 0 || len == 0)
      throw new RuntimeException("Too many unread ops.");
    //unread on eof is a noop
    if(len != -1) {
      position--;
      if(buffer[position] == '\n') {
	--line;
	column = lastColumn;
      } else {
	--column;
      }
    }
  }
  
  public char[] buffer() throws IOException {
    if(len == -1)
      return null;
    if(position == len) {
      len = reader.read(buffer, 0, buffer.length-1);
      if(len == -1)
	return null;
      int writepos = 0;
      boolean cret = false;
      final int l = len;
      for(int idx = 0; idx < l; ++idx) {
	final char c = buffer[idx];
	switch(c) {
	case '\r':
	  cret = true;
	  break;
	case '\n':
	  cret = false;
	  buffer[writepos++] = c;
	  break;
	default:
	  if(cret) {
	    buffer[writepos++] = '\n';
	    cret = false;
	  }
	  buffer[writepos++] = c;
	}
      }
      if(cret) {
	buffer[writepos++] = '\n';
	int nextChar = reader.read();
	if(nextChar != '\n' && nextChar != -1) {
	  buffer[writepos++] = (char)nextChar;
	}
      }
      len = writepos;
      position = 0;
    }
    return this.buffer;
  }
  public int position() { return this.position; }
  public void setPosition(int pos) { this.position = pos; }
  public int len() { return this.len; }
}
