package charred;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public final class ChunkReader extends Reader {

    private final Queue<String> chunks;
    private StringReader reader;

    public ChunkReader(List<String> chunks) {
        this.chunks = new LinkedList<>(chunks);
        this.reader = null;
    }

    @Override
    public int read(char[] cbuf, int off, int len) throws IOException {
        while (true) {
            if (reader == null) {
                String s = chunks.peek();
                if (s == null) {
                    return -1;
                }
                chunks.remove();
                reader = new StringReader(s);
            }
            int n = reader.read(cbuf, off, len);
            if (n >= 0) {
                return n;
            }
            reader = null;
        }
    }

    @Override
    public void close() throws IOException {
    }

}
