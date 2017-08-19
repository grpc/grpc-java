package io.grpc;

import static junit.framework.TestCase.assertTrue;
import static org.junit.Assert.assertSame;

import io.grpc.PersistentHashArrayMappedTrie.CollisionLeaf;
import io.grpc.PersistentHashArrayMappedTrie.CompressedIndex;
import io.grpc.PersistentHashArrayMappedTrie.Leaf;
import io.grpc.PersistentHashArrayMappedTrie.Node;
import org.junit.Test;

public class PersistentHashArrayMappedTrieTest {
  @Test
  public void leaf_replace() {
    Key key = new Key(0);
    Object value1 = new Object();
    Object value2 = new Object();
    Leaf<Key, Object> leaf = new Leaf<Key, Object>(key, value1);
    Node<Key, Object> ret = leaf.put(key, value2, key.hashCode(), 0);
    assertTrue(ret instanceof Leaf);
    assertSame(value2, ret.get(key, key.hashCode(), 0));

    assertSame(value1, leaf.get(key, key.hashCode(), 0));
  }

  @Test
  public void leaf_collision() {
    Key key1 = new Key(0);
    Key key2 = new Key(0);
    Object value1 = new Object();
    Object value2 = new Object();
    Leaf<Key, Object> leaf = new Leaf<Key, Object>(key1, value1);
    Node<Key, Object> ret = leaf.put(key2, value2, key2.hashCode(), 0);
    assertTrue(ret instanceof CollisionLeaf);
    assertSame(value1, ret.get(key1, key1.hashCode(), 0));
    assertSame(value2, ret.get(key2, key2.hashCode(), 0));

    assertSame(value1, leaf.get(key1, key1.hashCode(), 0));
    assertSame(null, leaf.get(key2, key2.hashCode(), 0));
  }

  @Test
  public void leaf_insert() {
    Key key1 = new Key(0);
    Key key2 = new Key(1);
    Object value1 = new Object();
    Object value2 = new Object();
    Leaf<Key, Object> leaf = new Leaf<Key, Object>(key1, value1);
    Node<Key, Object> ret = leaf.put(key2, value2, key2.hashCode(), 0);
    assertTrue(ret instanceof CompressedIndex);
    assertSame(value1, ret.get(key1, key1.hashCode(), 0));
    assertSame(value2, ret.get(key2, key2.hashCode(), 0));

    assertSame(value1, leaf.get(key1, key1.hashCode(), 0));
    assertSame(null, leaf.get(key2, key2.hashCode(), 0));
  }

  @Test(expected = AssertionError.class)
  public void collisionLeaf_assertKeysDifferent() {
    Key key1 = new Key(0);
    new CollisionLeaf<Key, Object>(key1, new Object(), key1, new Object());
  }

  @Test(expected = AssertionError.class)
  public void collisionLeaf_assertHashesSame() {
    new CollisionLeaf<Key, Object>(new Key(0), new Object(), new Key(1), new Object());
  }

  @Test
  public void collisionLeaf_insert() {
    Key key1 = new Key(0);
    Key key2 = new Key(key1.hashCode());
    Key insertKey = new Key(1);
    Object value1 = new Object();
    Object value2 = new Object();
    Object insertValue = new Object();
    CollisionLeaf<Key, Object> leaf =
        new CollisionLeaf<Key, Object>(new Key[]{key1, key2}, new Object[]{value1, value2});

    Node<Key, Object> ret = leaf.put(insertKey, insertValue, insertKey.hashCode(), 0);
    assertTrue(ret instanceof CompressedIndex);
    assertSame(value1, ret.get(key1, key1.hashCode(), 0));
    assertSame(value2, ret.get(key2, key2.hashCode(), 0));
    assertSame(insertValue, ret.get(insertKey, insertKey.hashCode(), 0));

    assertSame(value1, leaf.get(key1, key1.hashCode(), 0));
    assertSame(value2, leaf.get(key2, key2.hashCode(), 0));
    assertSame(null, leaf.get(insertKey, insertKey.hashCode(), 0));

  }

  @Test
  public void collisionLeaf_replace() {
    Key replaceKey = new Key(0);
    Object originalValue = new Object();
    Key key = new Key(replaceKey.hashCode());
    Object value = new Object();
    CollisionLeaf<Key, Object> leaf =
        new CollisionLeaf<Key, Object>(
            new Key[]{replaceKey, key},
            new Object[]{originalValue, value});
    Object replaceValue = new Object();
    Node<Key, Object> ret = leaf.put(replaceKey, replaceValue, replaceKey.hashCode(), 0);
    assertTrue(ret instanceof CollisionLeaf);
    assertSame(replaceValue, ret.get(replaceKey, replaceKey.hashCode(), 0));
    assertSame(value, ret.get(key, key.hashCode(), 0));

    assertSame(value, leaf.get(key, key.hashCode(), 0));
    assertSame(originalValue, leaf.get(replaceKey, replaceKey.hashCode(), 0));
  }

  @Test
  public void collisionLeaf_collision() {
    Key key1 = new Key(0);
    Key key2 = new Key(key1.hashCode());
    Key key3 = new Key(key1.hashCode());
    Object value1 = new Object();
    Object value2 = new Object();
    Object value3 = new Object();
    CollisionLeaf<Key, Object> leaf =
        new CollisionLeaf<Key, Object>(new Key[]{key1, key2}, new Object[]{value1, value2});

    Node<Key, Object> ret = leaf.put(key3, value3, key3.hashCode(), 0);
    assertTrue(ret instanceof CollisionLeaf);
    assertSame(value1, ret.get(key1, key1.hashCode(), 0));
    assertSame(value2, ret.get(key2, key2.hashCode(), 0));
    assertSame(value3, ret.get(key3, key3.hashCode(), 0));

    assertSame(value1, leaf.get(key1, key1.hashCode(), 0));
    assertSame(value2, leaf.get(key2, key2.hashCode(), 0));
    assertSame(null, leaf.get(key3, key3.hashCode(), 0));
  }

  /**
   * A key with a settable hashcode.
   */
  static final class Key {
    private int hashCode;

    Key(int hashCode) {
      this.hashCode = hashCode;
    }

    @Override
    public int hashCode() {
      return hashCode;
    }

    @Override
    public String toString() {
      return String.format("Key(hashCode=%x)", hashCode);
    }
  }
}
