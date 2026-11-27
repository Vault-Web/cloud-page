package cloudpage.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Serializes active-storage quota checks and writes per real user root in this JVM. */
public final class StorageQuotaLock {
  private static final ConcurrentHashMap<Path, Entry> LOCKS = new ConcurrentHashMap<>();

  private StorageQuotaLock() {}

  private static final class Entry {
    private final ReentrantLock lock = new ReentrantLock();
    private int users;
  }

  @FunctionalInterface
  public interface Write {
    void run() throws IOException;
  }

  /** Includes waiters in the reference count so cleanup never creates two locks for one root. */
  public static void withLock(Path root, Write write) throws IOException {
    Path key = root.toRealPath();
    Entry entry =
        LOCKS.compute(
            key,
            (ignored, existing) -> {
              Entry current = existing == null ? new Entry() : existing;
              current.users++;
              return current;
            });
    entry.lock.lock();
    try {
      write.run();
    } finally {
      entry.lock.unlock();
      LOCKS.compute(key, (ignored, current) -> --current.users == 0 ? null : current);
    }
  }
}
