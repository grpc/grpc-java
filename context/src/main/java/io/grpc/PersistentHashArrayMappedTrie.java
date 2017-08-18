package io.grpc;

import java.util.Arrays;

/**
 * A persistent (copy-on-write) hash tree/trie. Collisions are handled
 * linearly. Delete is not supported, but replacement is. The implementation
 * favors simplicity and low memory allocation during insertion. Although the
 * asymptotics are good, it is optimized for small sizes like less than 20;
 * "unbelievably large" would be 100.
 *
 * <p>Inspired by popcnt-based compression seen in Ideal Hash Trees, Phil
 * Bagwell (2000). The rest of the implementation is ignorant of/ignores the
 * paper.
 */
public class PersistentHashArrayMappedTrie<K,V> {
  private final Node<K,V> root;

  public PersistentHashArrayMappedTrie() {
    this(null);
  }

  private PersistentHashArrayMappedTrie(Node<K,V> root) {
    this.root = root;
  }

  public V get(K key) {
    if (root == null) {
      return null;
    }
    return root.get(key, key.hashCode(), 0);
  }

  public PersistentHashArrayMappedTrie<K,V> put(K key, V value) {
    if (root == null) {
      return new PersistentHashArrayMappedTrie<K,V>(new Leaf<K,V>(key, value));
    } else {
      return new PersistentHashArrayMappedTrie<K,V>(root.put(key, value, key.hashCode(), 0));
    }
  }

  private static class Leaf<K,V> implements Node<K,V> {
    private final K key;
    private final V value;

    public Leaf(K key, V value) {
      this.key = key;
      this.value = value;
    }

    @Override
    public V get(K key, int hash, int bitsConsumed) {
      if (this.key == key) {
        return value;
      } else {
        return null;
      }
    }

    @Override
    public Node<K,V> put(K key, V value, int hash, int bitsConsumed) {
      int thisHash = this.key.hashCode();
      if (thisHash != hash) {
        // Insert
        return CompressedIndex.combine(
            new Leaf<K,V>(key, value), hash, this, thisHash, bitsConsumed);
      } else if (this.key == key) {
        // Replace
        return new Leaf<K,V>(key, value);
      } else {
        // Hash collision
        return new CollisionLeaf<K,V>(this.key, this.value, key, value);
      }
    }
  }

  private static class CollisionLeaf<K,V> implements Node<K,V> {
    // All keys must have same hash, but not have the same reference
    private final K[] keys;
    private final V[] values;

    @SuppressWarnings("unchecked")
    public CollisionLeaf(K key1, V value1, K key2, V value2) {
      this((K[]) new Object[] {key1, key2}, (V[]) new Object[] {value1, value2});
      assert key1 != key2;
      if (key1.hashCode() != key2.hashCode()) {
        System.out.println("sfdbg bad!");
      }
      assert key1.hashCode() == key2.hashCode();
    }

    private CollisionLeaf(K[] keys, V[] values) {
      this.keys = keys;
      this.values = values;
    }

    @Override
    public V get(K key, int hash, int bitsConsumed) {
      for (int i = 0; i < keys.length; i++) {
        if (keys[i] == key) {
          return values[i];
        }
      }
      return null;
    }

    @Override
    public Node<K,V> put(K key, V value, int hash, int bitsConsumed) {
      int thisHash = keys[0].hashCode();
      int keyIndex;
      if (thisHash != hash) {
        // Insert
        return CompressedIndex.combine(
            new Leaf<K,V>(key, value), hash, this, thisHash, bitsConsumed);
      } else if ((keyIndex = indexOfKey(key)) != -1) {
        // Replace
        K[] newKeys = Arrays.copyOf(keys, keys.length);
        V[] newValues = Arrays.copyOf(values, keys.length);
        newKeys[keyIndex] = key;
        newValues[keyIndex] = value;
        return new CollisionLeaf<K,V>(newKeys, newValues);
      } else {
        // Yet another hash collision
        K[] newKeys = Arrays.copyOf(keys, keys.length + 1);
        V[] newValues = Arrays.copyOf(values, keys.length + 1);
        newKeys[keys.length] = key;
        newValues[keys.length] = value;
        return new CollisionLeaf<K,V>(newKeys, newValues);
      }
    }

    // -1 if not found
    private int indexOfKey(K key) {
      for (int i = 0; i < keys.length; i++) {
        if (keys[i] == key) {
          return i;
        }
      }
      return -1;
    }
  }

  private static class CompressedIndex<K,V> implements Node<K,V> {
    private static final int BITS = 5;
    private static final int BITS_MASK = 0x1F;

    private final int bitmap;
    private final Node<K,V>[] values;

    private CompressedIndex(int bitmap, Node<K,V>[] values) {
      this.bitmap = bitmap;
      this.values = values;
    }

    @Override
    public V get(K key, int hash, int bitsConsumed) {
      int indexBit = indexBit(hash, bitsConsumed);
      if ((bitmap & indexBit) == 0) {
        return null;
      }
      return values[compressedIndex(indexBit)].get(key, hash, bitsConsumed + BITS);
    }

    @Override
    public Node<K,V> put(K key, V value, int hash, int bitsConsumed) {
      int indexBit = indexBit(hash, bitsConsumed);
      int compressedIndex = compressedIndex(indexBit);
      if ((bitmap & indexBit) == 0) {
        // Insert
        int newBitmap = bitmap | indexBit;
        @SuppressWarnings("unchecked")
        Node<K,V>[] newValues = (Node<K,V>[]) new Node<?,?>[values.length + 1];
        System.arraycopy(values, 0, newValues, 0, compressedIndex);
        newValues[compressedIndex] = new Leaf<K,V>(key, value);
        System.arraycopy(
            values,
            compressedIndex,
            newValues,
            compressedIndex + 1,
            values.length - compressedIndex);
        return new CompressedIndex<K,V>(newBitmap, newValues);
      } else {
        // Replace
        Node<K,V>[] newValues = Arrays.copyOf(values, values.length);
        newValues[compressedIndex] =
            values[compressedIndex].put(key, value, hash, bitsConsumed + BITS);
        return new CompressedIndex<K,V>(bitmap, newValues);
      }
    }

    private static <K,V> Node<K,V> combine(
        Node<K,V> node1, int hash1, Node<K,V> node2, int hash2, int bitsConsumed) {
      assert hash1 != hash2;
      int indexBit1 = indexBit(hash1, bitsConsumed);
      int indexBit2 = indexBit(hash2, bitsConsumed);
      if (indexBit1 == indexBit2) {
        Node<K,V> node = combine(node1, hash1, node2, hash2, bitsConsumed + BITS);
        @SuppressWarnings("unchecked")
        Node<K,V>[] values = (Node<K,V>[]) new Node<?,?>[] {node};
        return new CompressedIndex<K,V>(indexBit1, values);
      } else {
        // Make node1 the smallest
        if (hash1 > hash2) {
          Node<K,V> nodeCopy = node1;
          node1 = node2;
          node2 = nodeCopy;
        }
        @SuppressWarnings("unchecked")
        Node<K,V>[] values = (Node<K,V>[]) new Node<?,?>[] {node1, node2};
        return new CompressedIndex<K,V>(indexBit1 | indexBit2, values);
      }
    }

    private static int indexBit(int hash, int bitsConsumed) {
      int uncompressedIndex = (hash >>> bitsConsumed) & BITS_MASK;
      return 1 << uncompressedIndex;
    }

    private int compressedIndex(int indexBit) {
      return Integer.bitCount(bitmap & (indexBit - 1));
    }
  }

  private interface Node<K,V> {
    public V get(K key, int hash, int bitsConsumed);
    public Node<K,V> put(K key, V value, int hash, int bitsConsumed);
  }
}