package charred;



public class CanonicalStrings {
  final float loadFactor = 0.75f;
  LeafNode[] data;
  int mask;
  int threshold;
  int length;

  public static class LeafNode {
    public final String k;
    public final int hashcode;
    LeafNode nextNode;
    public LeafNode(final String k, final int hc, final LeafNode nn) {
      this.k = k;
      this.hashcode = hc;
      this.nextNode = nn;
    }
  };

  //Taken from openjdk ArraysSupport class
  public static int hashCode(final char[] a, final int fromIndex, final int end) {
    int result = 1;
    for (int i = fromIndex; i < end; i++) {
      result = 31 * result + a[i];
    }
    return result;
  }

  public CanonicalStrings() {
    data = new LeafNode[128];
    mask = data.length - 1;
    this.threshold = (int)loadFactor * data.length;
    this.length = 0;
  }


  String checkResize(String rv) {
    if(this.length >= this.threshold) {
      final int newCap = data.length * 2;
      final LeafNode[] newD = new LeafNode[newCap];
      final LeafNode[] oldD = this.data;
      final int oldCap = oldD.length;
      final int mask = newCap - 1;
      for(int idx = 0; idx < oldCap; ++idx) {
	LeafNode lf;
	if((lf = oldD[idx]) != null) {
	  oldD[idx] = null;
	  if(lf.nextNode == null) {
	    newD[lf.hashcode & mask] = lf;
	  } else {
	    //https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/HashMap.java#L722
	    //Because we only allow capacities that are powers of two, we have
	    //exactly 2 locations in the new data array where these can go.  We want
	    //to avoid writing to any locations more than once and instead make the
	    //at most two new linked lists, one for the new high position and one
	    //for the new low position.
	    LeafNode loHead = null, loTail = null, hiHead = null, hiTail = null;
	    while(lf != null) {
	      LeafNode e = lf;
	      lf = lf.nextNode;
	      //Check high bit
	      if((e.hashcode & oldCap) == 0) {
		if(loTail == null) loHead = e;
		else loTail.nextNode = e;
		loTail = e;
	      } else {
		if(hiTail == null) hiHead = e;
		else hiTail.nextNode = e;
		hiTail = e;
	      }
	    }
	    if(loHead != null) {
	      loTail.nextNode = null;
	      newD[idx] = loHead;
	    }
	    if(hiHead != null) {
	      hiTail.nextNode = null;
	      newD[idx+oldCap] = hiHead;
	    }
	  }
	}
      }
      this.threshold = (int)(newCap * this.loadFactor);
      this.mask = mask;
      this.data = newD;
    }
    return rv;
  }
  boolean equals(final char[] data, final int sidx, final int len, final String v) {
    if(v.length() != len) return false;
    for(int idx = 0; idx < len; ++idx) {
      if(v.charAt(idx) != data[idx+sidx]) return false;
    }
    return true;
  }
  public String put(final char[] data, final int sidx, final int eidx) {
    final int len = eidx - sidx;
    final int hc = hashCode(data, sidx, eidx);
    final int idx = hc & this.mask;
    final LeafNode lastNode = this.data[idx];
    //Avoid unneeded calls to both equals and checkResize
    for(LeafNode e = lastNode; e != null; e = e.nextNode) {
      if(equals(data, sidx, len, e.k))
	return e.k;
    }
    final String rv = new String(data, sidx, len);
    this.data[idx] = new LeafNode(rv, hc, lastNode);
    ++this.length;
    return checkResize(rv);
  }
  public int size() { return this.length; }
}
