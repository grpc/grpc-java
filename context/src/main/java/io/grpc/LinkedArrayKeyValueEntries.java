package io.grpc;

/**
 * A {@link Context.KeyValueEntries} that is implemented as a linked list of arrays. Key-values
 * are stored in alternating slots of an array. Modifications are performed by appending to the
 * end of the linked list. Reads are performed by following the parent pointers until the desired
 * key is found, or if it is determined to be absent.
 *
 * A bloom filter is used to fast-fail reading keys that are absent.
 */
final class LinkedArrayKeyValueEntries extends Context.KeyValueEntries {
  private static final Object[] EMPTY_ENTRIES = new Object[0];

  private final LinkedArrayKeyValueEntries parent;
  // A 64 bit bloom filter of all the Key.bloomFilterMask values in this Context and all the parents
  // this will help us detect failed lookups faster.  In fact if there are fewer than 64 key objects
  // this will be perfect (though we don't currently take advantage of that fact).
  private final long keyBloomFilter;
  // Alternating Key, Object entries
  private final Object[] keyValueEntries;

  public static final LinkedArrayKeyValueEntries EMPTY =
      new LinkedArrayKeyValueEntries(null, EMPTY_ENTRIES);

  private LinkedArrayKeyValueEntries(LinkedArrayKeyValueEntries parent, Object[] overwrites) {
    this.parent = parent;
    keyBloomFilter = computeFilter(parent, overwrites);
    keyValueEntries = overwrites;
  }

  /**
   * Lookup the value for a key in the context inheritance chain.
   */
  @Override
  public Object lookup(Context.Key<?> key) {
    if ((computeBloomFilterMask(key) & keyBloomFilter) == 0) {
      return null;
    }
    Object[] entries = keyValueEntries;
    LinkedArrayKeyValueEntries current = this;
    while (true) {
      // entries in the table are alternating key value pairs where the even numbered slots are the
      // key objects
      for (int i = 0; i < entries.length; i += 2) {
        // Key objects have identity semantics, compare using ==
        if (key == entries[i]) {
          return entries[i + 1];
        }
      }
      current = current.parent;
      if (current == null) {
        return null;
      }
      entries = current.keyValueEntries;
    }
  }

  private static long computeFilter(LinkedArrayKeyValueEntries parent, Object[] keyValueEntries) {
    long filter = parent != null ? parent.keyBloomFilter : 0;
    for (int i = 0; i < keyValueEntries.length; i += 2) {
      filter |= computeBloomFilterMask((Context.Key<?>) keyValueEntries[i]);
    }
    return filter;
  }

  private static long computeBloomFilterMask(Context.Key<?> entry) {
    return entry.hashCode() % 64;
  }

  @Override
  public <V> Context.KeyValueEntries put(Context.Key<V> key, V value) {
    return new LinkedArrayKeyValueEntries(this, new Object[] {key, value});
  }

  @Override
  public <V1, V2> Context.KeyValueEntries put(
      Context.Key<V1> key1, V1 value1, Context.Key<V2> key2, V2 value2) {
    return new LinkedArrayKeyValueEntries(this, new Object[] {key1, value1, key2, value2});
  }

  @Override
  public <V1, V2, V3> Context.KeyValueEntries put(
      Context.Key<V1> key1, V1 value1,
      Context.Key<V2> key2, V2 value2,
      Context.Key<V3> key3, V3 value3) {
    return new LinkedArrayKeyValueEntries(this, new Object[] {key1, value1, key2, value2, key3, value3});
  }

  @Override
  public <V1, V2, V3, V4> Context.KeyValueEntries put(
      Context.Key<V1> key1, V1 value1,
      Context.Key<V2> key2, V2 value2,
      Context.Key<V3> key3, V3 value3,
      Context.Key<V4> key4, V4 value4) {
    return new LinkedArrayKeyValueEntries(
        this, new Object[] {key1, value1, key2, value2, key3, value3, key4, value4});
  }
}
