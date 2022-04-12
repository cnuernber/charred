package charred;


import java.io.Writer;
import java.io.IOException;
import java.math.BigInteger;
import java.math.BigDecimal;
import java.util.Iterator;
import java.util.function.BiConsumer;
import java.util.List;
import java.util.Map;
import clojure.lang.Ratio;


public class JSONWriter implements AutoCloseable {
  int indent;
  public final Writer w;
  public final boolean escapeJSSep;
  public final boolean escapeSlash;
  public final boolean escapeUnicode;
  public final String indentStr;
  public final BiConsumer<JSONWriter,Object> objConsumer;
  final CharBuffer charBuffer;
  public JSONWriter(Writer _w,
		    boolean _escapeJSSep,
		    boolean _escapeSlash,
		    boolean _escapeUnicode,
		    String _indentStr,
		    BiConsumer<JSONWriter,Object> _objConsumer) {
    w = _w;
    indent = 0;
    charBuffer = new CharBuffer();
    escapeJSSep = _escapeJSSep;
    escapeSlash = _escapeSlash;
    escapeUnicode = _escapeUnicode;
    indentStr = _indentStr != null && _indentStr.length() != 0 ? _indentStr : null;
    objConsumer = _objConsumer;
  }
  public int indent() { return indent; }
  public void indent(int i) { indent = i; }
  public CharBuffer charBuffer() { charBuffer.clear(); return charBuffer; }
  public static void escape(final CharBuffer cb, final char data) {
    cb.append('\\');
    cb.append(data);
  }
  public static void toHexString(final CharBuffer cb, final char data) {
    escape(cb, 'u');
    String hexData = Integer.toHexString(data);
    switch(hexData.length()) {
    case 1:
      cb.append('0');
      cb.append('0');
      cb.append('0');
      break;
    case 2:
      cb.append('0');
      cb.append('0');
      break;
    case 3:
      cb.append('0');
      break;
    }
    cb.append(hexData);
  }
  public static void writeBuffer(Writer w, CharBuffer b) throws IOException {
    w.write(b.buffer(), 0, b.length());
  }
  public static boolean isJSSep(final char data) {
    return data == 8232 || data == 8233;
  }
  public void writeString(CharSequence data) throws IOException {
    final CharBuffer cb = charBuffer();
    cb.append('"');
    final int dlen = data.length();
    for (int idx = 0; idx < dlen; ++idx ) {
      final char cdata = data.charAt(idx);
      switch(cdata) {
      case '\\':
      case '"':
	escape(cb,cdata);
	break;
      case '/':
	if (escapeSlash)
	  escape(cb,cdata);
	else
	  cb.append(cdata);
	break;
      case '\f': escape(cb,'f'); break;
      case '\n': escape(cb, 'n'); break;
      case '\r': escape(cb, 'r'); break;
      case '\b': escape(cb, 'b'); break;
      case '\t': escape(cb, 't'); break;
      default:
	if (cdata < 32 ||
	    (escapeJSSep && isJSSep(cdata))) {
	  toHexString(cb, cdata);
	} else if (escapeUnicode && (cdata >= 128)) {
	  toHexString(cb,cdata);
	} else {
	  cb.append(cdata);
	}
	break;
      }
    }
    cb.append('"');
    writeBuffer(w,cb);
  }
  public void writeNumber(Number n) throws Exception {
    if (n instanceof Ratio)
      n = ((Ratio)n).doubleValue();

    if (n instanceof Double) {
      final Double dn = (Double)n;
      if (!Double.isFinite(dn)) {
	if (Double.isNaN(dn))
	  throw new Exception("JSON encoding error - NAN detected");
	else
	  throw new Exception("JSON encoding error - +/-INF detected");
      }
    } else if (n instanceof Float) {
      final Float dn = (Float)n;
      if (!Float.isFinite(dn)) {
	if (Float.isNaN(dn))
	  throw new Exception("JSON encoding error - NAN detected");
	else
	  throw new Exception("JSON encoding error - +/-INF detected");
      }
    }
    w.write(n.toString());
  }

  public void writeIndent() throws Exception {
    if (indent == 0 || indentStr == null)
      return;
    if (indent == 1)
      w.write(indentStr);
    else {
      for(int idx = 0; idx < indent; ++idx)
	w.write(indentStr);
    }
  }
  public void writeObject(Object obj) throws Exception {
    final BiConsumer<JSONWriter,Object> consumer = objConsumer;
    if (obj instanceof String) {
      writeString((String)obj);
    } else if (obj instanceof Number) {
      writeNumber((Number)obj);
    } else if (obj == null) {
      w.write("null");
    }
    else if (obj instanceof Boolean) {
      if ((Boolean)obj)
	w.write("true");
      else
	w.write("false");
    } else {
      //We bail here to the consumer so we can do things like stringify keywords and such.
      //Thus we accept a very small hit to perf in the case where that isn't necessary.
      consumer.accept(this,obj);
    }
  }
  public void writeArray(Iterator<Object> iter) throws Exception {
    ++indent;
    w.write('[');
    boolean first = true;
    for(boolean c = iter.hasNext(); c; c = iter.hasNext() ) {
      if(!first)
	w.write(',');
      first = false;
      writeObject(iter.next());
    }
    w.write(']');
    --indent;
  }
  public void writeMap(Iterator<Map.Entry> iter) throws Exception {
    boolean hasIndent = indentStr != null;
    if (hasIndent) {
      if (indent != 0) w.write('\n');
      writeIndent();
    }
    w.write('{');
    ++indent;
    boolean first = true;
    for (boolean c = iter.hasNext(); c; c = iter.hasNext()) {
      Map.Entry val = iter.next();
      Object k = val.getKey();
      Object v = val.getValue();
      if (! (k instanceof String) )
	throw new Exception("JSON encoding error - Map keys must be strings");
      if (!first) {
	w.write(",");
      }
      if(hasIndent) {
	w.write('\n');
	writeIndent();
      }
      first = false;
      writeString((String)k);
      w.write(": ");
      writeObject(v);
    }
    --indent;
    if (hasIndent && !first) {
      w.write('\n');
      writeIndent();
    }
    w.write('}');
  }
  public void close() throws Exception {
    w.close();
  }
}
