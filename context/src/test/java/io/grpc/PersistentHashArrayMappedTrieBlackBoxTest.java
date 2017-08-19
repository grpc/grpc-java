package io.grpc;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.Test;

public class PersistentHashArrayMappedTrieBlackBoxTest {
  private static final int NUM_OBJECTS = 20000;
  private static final int WRITES_PER_ITER = 100;
  private static final Random r = new Random(0);
  private final Object[] keys;

  public PersistentHashArrayMappedTrieBlackBoxTest() {
    keys = new Object[NUM_OBJECTS];

    for (int i = 0; i < NUM_OBJECTS; i++) {
      keys[i] = new PersistentHashArrayMappedTrieTest.Key(r.nextInt());
    }
  }

  @Test
  public void test() {
    for (int i = 0; i < 10000; i++) {
      PersistentHashArrayMappedTrie<Object, Object> trie =
        new PersistentHashArrayMappedTrie<Object, Object>();
      Map<Object, Object> map = new HashMap<Object, Object>();
      int numWrites = r.nextInt(WRITES_PER_ITER);

      for (int w = 0; w < numWrites; w++) {
        Object key = keys[r.nextInt(NUM_OBJECTS - 1)];
        Object val = new Object();
        map.put(key, val);
        trie = trie.put(key, val);

        verify(map, trie);
      }
    }
  }

  private void verify(Map<Object, Object> map, PersistentHashArrayMappedTrie<Object, Object> trie) {
    for (Map.Entry<Object, Object> entry : map.entrySet()) {
      Object key = entry.getKey();
      assertEquals(map.get(key), trie.get(key));
    }
  }
}
