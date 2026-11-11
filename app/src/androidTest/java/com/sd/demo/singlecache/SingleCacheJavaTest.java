package com.sd.demo.singlecache;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.sd.lib.singlecache.SingleCache;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;

/** Java入口支持静态重载并拒绝空默认值 */
@RunWith(AndroidJUnit4.class)
public class SingleCacheJavaTest {
  @Test
  public void testFactoryOverloads() {
    AtomicInteger count = new AtomicInteger();
    SingleCache<TestJavaFactoryModel> first = SingleCache.get(TestJavaFactoryModel.class, () -> {
      count.incrementAndGet();
      return new TestJavaFactoryModel("default");
    });
    SingleCache<TestJavaFactoryModel> second = SingleCache.get(TestJavaFactoryModel.class, false, () -> {
      count.incrementAndGet();
      return new TestJavaFactoryModel("default");
    });
    SingleCache<TestJavaFactoryModel> third = SingleCache.Companion.get(TestJavaFactoryModel.class, true, () -> {
      count.incrementAndGet();
      return new TestJavaFactoryModel("default");
    });

    assertNotSame(first, second);
    assertNotSame(second, third);
    assertNotSame(first, third);
    assertEquals(3, count.get());
  }

  @Test
  public void testNullDefaultRejected() {
    AtomicInteger count = new AtomicInteger();
    for (boolean memoryCache : new boolean[] {true, false}) {
      IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
        SingleCache.get(TestJavaNullDefaultModel.class, memoryCache, () -> {
          count.incrementAndGet();
          return null;
        })
      );
      assertEquals("getDefault must return a non-null value", error.getMessage());
    }
    assertThrows(IllegalArgumentException.class, () ->
      SingleCache.get(TestJavaNullDefaultModel.class, () -> {
        count.incrementAndGet();
        return null;
      })
    );
    assertEquals(3, count.get());
  }
}
