package charred;


import java.io.EOFException;
import java.util.Collection;
import java.util.List;
import java.util.function.LongPredicate;


public final class CSVReader {
  final CharReader reader;
  final char quot;
  final char sep;
  final char comment;
  public static final int EOF=-1;
  public static final int EOL=-2;
  public static final int SEP=1;
  public static final int QUOT=2;
  public static final int COMMENT=3;

  public CSVReader(CharReader rdr, char _quot, char _sep, char _comment) {
    reader = rdr;
    quot = _quot;
    sep = _sep;
    comment = _comment;
  }

  final void csvReadQuote(CharBuffer sb) throws EOFException {
    char[] buffer = reader.buffer();
    while(buffer != null) {
      int startpos = reader.position();
      int len = buffer.length;
      for(int pos = startpos; pos < len; ++pos) {
	final char curChar = buffer[pos];
	if (curChar == quot) {
	  sb.append(buffer,startpos,pos);
	  if (reader.readFrom(pos+1) == quot) {
	    sb.append(quot);
	    buffer = reader.buffer();
	    len = buffer.length;
	    startpos = reader.position();
	    //account for loop increment
	    pos = startpos - 1;
	  } else {
	    reader.unread();
	    return;
	  }
	}
      }
      sb.append(buffer,startpos,len);
      buffer = reader.nextBuffer();
    }
    throw new EOFException("EOF encountered within quote");
  }
  final void csvReadComment() throws EOFException {
    char[] buffer = reader.buffer();
    while(buffer != null) {
      final int startpos = reader.position();
      final int len = buffer.length;
      for(int pos = startpos; pos < len; ++pos) {
	final char curChar = buffer[pos];
	if (curChar == '\n') {
	  reader.position(pos + 1);
	  return;
	} else if (curChar == '\r') {
	  if (reader.readFrom(pos+1) != '\n' && !reader.eof()) {
	    reader.unread();
	  }
	  return;
	}
      }
      buffer = reader.nextBuffer();
    }
    //EOF encountered inside quote
  }
  //Read a row from a CSV file.
  final int csvRead(CharBuffer sb, final boolean enableComment) throws EOFException {
    char[] buffer = reader.buffer();
    final char localSep = sep;
    final char localQuot = quot;
    final char localComment = comment;
    boolean ec = enableComment;
    while(buffer != null) {
      final int startpos = reader.position();
      final int len = buffer.length;
      for(int pos = startpos; pos < len; ++pos) {
	final char curChar = buffer[pos];
	if (curChar == localComment && ec) {
	  reader.position(pos + 1);
	  return COMMENT;
	} else if (curChar == localQuot) {
	  sb.append(buffer, startpos, pos);
	  reader.position(pos + 1);
	  return QUOT;
	} else if (curChar == localSep) {
	  sb.append(buffer, startpos, pos);
	  reader.position(pos + 1);
	  return SEP;
	} else if (curChar == '\n') {
	  sb.append(buffer, startpos, pos);
	  reader.position(pos + 1);
	  return EOL;
	} else if (curChar == '\r') {
	  sb.append(buffer, startpos, pos);
	  if (reader.readFrom(pos+1) != '\n' && !reader.eof()) {
	    reader.unread();
	  }
	  return EOL;
	}
	ec = false;
      }
      sb.append(buffer, startpos, len);
      buffer = reader.nextBuffer();
    }
    return EOF;
  }

  public static final class RowReader
  {
    final CSVReader rdr;
    final CharBuffer sb;
    LongPredicate pred;
    final JSONReader.ArrayReader arrayReader;

    public RowReader(CharReader _r, CharBuffer cb, LongPredicate _pred, char quot, char sep, char comment,
		     JSONReader.ArrayReader _aryReader) {
      rdr = new CSVReader(_r, quot, sep, comment);
      sb = cb;
      pred = _pred;
      arrayReader = _aryReader;
    }
    public void setPredicate(LongPredicate p) { pred = p; }
    public static final boolean emptyStr(String s) {
      return s == null || s.length() == 0;
    }
    public static final boolean emptyRow(List row) {
      int sz = row.size();
      return sz == 0 || (sz == 1 && emptyStr((String)row.get(0)));
    }
    public final Object nextRow() throws EOFException {
      //It turns out it is fast to just create a new row object
      //rather than clone an existing one.
      Object curRow = arrayReader.newArray();
      sb.clear();
      int tag;
      int colidx = 0;
      boolean comment = true;
      final LongPredicate p = pred;
      do {
	tag = rdr.csvRead(sb, comment);
	comment = false;
	if(tag != QUOT) {
	  if(tag == COMMENT) {
	    rdr.csvReadComment();
	  } else {
	    if (p.test(colidx))
	      curRow = arrayReader.onValue(curRow, sb.toString());
	    ++colidx;
	    sb.clear();
	  }
	} else {
	  rdr.csvReadQuote(sb);
	}
      } while(tag > 0);
      curRow = arrayReader.finalizeArray(curRow);
      if (!(tag == EOF && emptyRow((List)curRow))) {
	return curRow;
      }
      else
	return null;
    }
  }
}
