package charred;


import java.math.BigInteger;


/**
 * Allocation-free parsing of JSON numbers directly from a char array.
 *
 * Integers of up to 18 digits are accumulated directly into a long.  Doubles use
 * Clinger's fast path when the mantissa and exponent are small enough to be exactly
 * representable and the Eisel-Lemire algorithm otherwise.  Anything this class cannot
 * parse with certainty is reported as unparsed so the caller can fall back to the JDK
 * parsers - this guarantees results bit-identical to Double.parseDouble.
 */
public final class NumberParser {
  /** Returned when the input could not be parsed by the fast paths. */
  public static final Object UNPARSED = new Object();

  static final double[] POW10 = {
    1e0, 1e1, 1e2, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11,
    1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18, 1e19, 1e20, 1e21, 1e22
  };

  static final int MIN_EXP10 = -348;
  static final int MAX_EXP10 = 347;
  //128 bit mantissas of 10^e, rounded down, normalized so the high bit is set.
  //Laid out as [hi0, lo0, hi1, lo1, ...] for e in [MIN_EXP10, MAX_EXP10].
  static final long[] POW10_MANTISSAS = buildPow10Mantissas();

  static long[] buildPow10Mantissas() {
    final int n = MAX_EXP10 - MIN_EXP10 + 1;
    final long[] retval = new long[n * 2];
    final BigInteger five = BigInteger.valueOf(5);
    for (int e = MIN_EXP10; e <= MAX_EXP10; ++e) {
      BigInteger m;
      if (e >= 0) {
	//10^e = 5^e * 2^e - the mantissa of 5^e is all we need.
	m = five.pow(e);
	final int shift = m.bitLength() - 128;
	m = shift > 0 ? m.shiftRight(shift) : m.shiftLeft(-shift);
      } else {
	final BigInteger p = five.pow(-e);
	//floor(2^k/p) with k chosen so the result has exactly 128 bits.
	m = BigInteger.ONE.shiftLeft(p.bitLength() + 127).divide(p);
      }
      final int idx = (e - MIN_EXP10) * 2;
      retval[idx] = m.shiftRight(64).longValue();
      retval[idx + 1] = m.longValue();
    }
    return retval;
  }

  static long unsignedMultiplyHigh(long x, long y) {
    final long x0 = x & 0xFFFFFFFFL, x1 = x >>> 32;
    final long y0 = y & 0xFFFFFFFFL, y1 = y >>> 32;
    final long w0 = x0 * y0;
    final long t = x1 * y0 + (w0 >>> 32);
    final long w1 = (t & 0xFFFFFFFFL) + x0 * y1;
    return x1 * y1 + (t >>> 32) + (w1 >>> 32);
  }

  /**
   * Eisel-Lemire - port of the Go standard library's eiselLemire64.  Returns NaN when
   * the result cannot be determined with certainty.
   */
  static double eiselLemire(long man, int exp10, boolean neg) {
    if (exp10 < MIN_EXP10 || exp10 > MAX_EXP10)
      return Double.NaN;
    final int clz = Long.numberOfLeadingZeros(man);
    man <<= clz;
    long retExp2 = ((217706L * exp10) >> 16) + 64 + 1023 - clz;
    final int idx = (exp10 - MIN_EXP10) * 2;
    final long powHi = POW10_MANTISSAS[idx];
    final long powLo = POW10_MANTISSAS[idx + 1];
    long xHi = unsignedMultiplyHigh(man, powHi);
    long xLo = man * powHi;
    //Wider approximation
    if ((xHi & 0x1FF) == 0x1FF && Long.compareUnsigned(xLo + man, man) < 0) {
      final long yHi = unsignedMultiplyHigh(man, powLo);
      final long yLo = man * powLo;
      long mergedHi = xHi;
      final long mergedLo = xLo + yHi;
      if (Long.compareUnsigned(mergedLo, xLo) < 0)
	++mergedHi;
      if ((mergedHi & 0x1FF) == 0x1FF && mergedLo + 1 == 0
	  && Long.compareUnsigned(yLo + man, man) < 0)
	return Double.NaN;
      xHi = mergedHi;
      xLo = mergedLo;
    }
    //Shift to 54 bits
    final long msb = xHi >>> 63;
    long retMantissa = xHi >>> (msb + 9);
    retExp2 -= 1 ^ msb;
    //Half-way ambiguity
    if (xLo == 0 && (xHi & 0x1FF) == 0 && (retMantissa & 3) == 1)
      return Double.NaN;
    //From 54 to 53 bits
    retMantissa += retMantissa & 1;
    retMantissa >>>= 1;
    if ((retMantissa >>> 53) > 0) {
      retMantissa >>>= 1;
      retExp2 += 1;
    }
    //Subnormal, infinite or NaN results are left to the JDK.
    if (retExp2 <= 0 || retExp2 >= 0x7FF)
      return Double.NaN;
    long retBits = (retExp2 << 52) | (retMantissa & 0x000FFFFFFFFFFFFFL);
    if (neg)
      retBits |= 0x8000000000000000L;
    return Double.longBitsToDouble(retBits);
  }

  static boolean isDigit(char c) { return c >= '0' && c <= '9'; }

  /**
   * Parse a strict JSON number in buf[start,end).  Returns a Long for integers with
   * at most 18 digits, a Double for floating point numbers when parseDoubles is true
   * or Boolean.FALSE for valid floating point numbers when parseDoubles is false.
   * Returns UNPARSED when the input is not a strict JSON number or cannot be parsed
   * exactly by the fast paths.
   */
  public static Object parse(final char[] buf, final int start, final int end,
			     final boolean parseDoubles) {
    int pos = start;
    final boolean neg = buf[pos] == '-';
    if (neg) ++pos;
    if (pos == end || !isDigit(buf[pos])) return UNPARSED;
    long man = 0;
    int nDigits = 0;
    //Integer part - no leading zeros
    if (buf[pos] == '0') {
      ++pos;
    } else {
      final int intStart = pos;
      for (; pos < end && isDigit(buf[pos]); ++pos)
	man = man * 10 + (buf[pos] - '0');
      nDigits = pos - intStart;
    }
    if (pos == end) {
      if (nDigits > 18) return UNPARSED;
      return Long.valueOf(neg ? -man : man);
    }
    //Fraction - digits after the significant digits ran out
    int nFracDigits = 0;
    if (buf[pos] == '.') {
      ++pos;
      final int fracStart = pos;
      //Leading zeros are not significant
      if (nDigits == 0)
	for (; pos < end && buf[pos] == '0'; ++pos);
      final int sigStart = pos;
      for (; pos < end && isDigit(buf[pos]); ++pos)
	man = man * 10 + (buf[pos] - '0');
      if (pos == fracStart) return UNPARSED;
      nDigits += pos - sigStart;
      nFracDigits = pos - fracStart;
    }
    int exp10 = 0;
    if (pos < end && (buf[pos] == 'e' || buf[pos] == 'E')) {
      ++pos;
      boolean expNeg = false;
      if (pos < end && (buf[pos] == '-' || buf[pos] == '+')) {
	expNeg = buf[pos] == '-';
	++pos;
      }
      final int expStart = pos;
      for (; pos < end && isDigit(buf[pos]); ++pos) {
	//Huge exponents are left to the JDK.
	if (exp10 < 100000)
	  exp10 = exp10 * 10 + (buf[pos] - '0');
      }
      if (pos == expStart) return UNPARSED;
      if (expNeg) exp10 = -exp10;
    }
    if (pos != end) return UNPARSED;
    if (!parseDoubles) return Boolean.FALSE;
    //More than 19 significant digits may overflow the mantissa.
    if (nDigits > 19) return UNPARSED;
    exp10 -= nFracDigits;
    if (man == 0)
      return neg ? -0.0 : 0.0;
    //man is unsigned from here - 19 digits can exceed Long.MAX_VALUE.
    //Clinger's fast path - both operands are exact so the result is correctly rounded.
    if (exp10 >= -22 && exp10 <= 22 && man > 0 && man <= (1L << 53)) {
      double d = (double)man;
      d = exp10 < 0 ? d / POW10[-exp10] : d * POW10[exp10];
      return neg ? -d : d;
    }
    final double d = eiselLemire(man, exp10, neg);
    return Double.isNaN(d) ? UNPARSED : d;
  }
}
