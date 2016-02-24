package io.grpc;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class AttributesTest {
    private Attributes.Key<String> key1 = new Attributes.Key<>("key1");
    private Attributes.Key<String> key2 = new Attributes.Key<>("key2");
    
    @Test
    public void overrideIsAdditive() {
        Attributes original = Attributes.newBuilder()
                .set(key1, "value1")
                .build();
        
        Attributes override = Attributes.newBuilder()
                .set(key2, "value2")
                .build();
        
        Attributes result = original.overrideWith(override);
        Assert.assertEquals(2, result.size());
        Assert.assertEquals("value1", result.get(key1));
        Assert.assertEquals("value2", result.get(key2));
    }
    
    @Test
    public void overrideWithEmpty() {
        Attributes original = Attributes.newBuilder()
                .set(key1, "value1")
                .build();
        
        Attributes override = Attributes.EMPTY;
        
        Attributes result = original.overrideWith(override);
        Assert.assertEquals(1, result.size());
        Assert.assertEquals("value1", result.get(key1));
    }
    
    @Test
    public void overrideWithOther() {
        Attributes original = Attributes.newBuilder()
                .set(key1, "original")
                .build();
        
        Attributes override = Attributes.newBuilder()
                .set(key1, "override")
                .build();
        
        Attributes result = original.overrideWith(override);
        Assert.assertEquals(1, result.size());
        Assert.assertEquals("override", result.get(key1));
    }
    
}
