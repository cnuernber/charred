package chardata;


import java.util.function.Predicate;


public class CSVWriter {
  public static Predicate<String> makeQuotePredicate(final char sep, final char quote) {
    final char minChar = (char)Math.min('\r', Math.min('\n', Math.min(sep,quote)));
    final char maxChar = (char)Math.max('\r', Math.max('\n', Math.max(sep,quote)));
    return new Predicate<String>() {
      public boolean test(String v) {
	final int slen = v.length();
	for (int idx = 0; idx < slen; ++idx) {
	  final char curChar = v.charAt(idx);
	  if (curChar <= maxChar && curChar >= minChar) {
	    return curChar == '\r' ||
	      curChar == '\n' ||
	      curChar == sep ||
	      curChar == quote;
	  }
	}
	return false;
      }
    };
  }
  public static Predicate<String> truePredicate = a -> true;
  public static void quote(String data, final char quote, final CharBuffer cb) {
    cb.clear();
    cb.append(quote);
    final int slen = data.length();
    for (int idx = 0; idx < slen; ++idx) {
      final char curChar = data.charAt(idx);
      if ( curChar == quote) {
	cb.append(quote);
      }
      cb.append(curChar);
    }
    cb.append(quote);
  }
}
