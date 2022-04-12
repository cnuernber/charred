package charred;


import java.util.function.Supplier;

public interface CloseableSupplier<T> extends AutoCloseable, Supplier<T> {}
