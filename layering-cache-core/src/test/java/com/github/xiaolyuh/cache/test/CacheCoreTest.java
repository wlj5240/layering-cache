package com.github.xiaolyuh.cache.test;

import com.github.xiaolyuh.cache.Cache;
import com.github.xiaolyuh.cache.LayeringCache;
import com.github.xiaolyuh.cache.config.CacheConfig;
import com.github.xiaolyuh.cache.redis.RedisCache;
import com.github.xiaolyuh.cache.redis.RedisCacheKey;
import com.github.xiaolyuh.manager.CacheManager;
import com.github.xiaolyuh.setting.FirstCacheSetting;
import com.github.xiaolyuh.setting.LayeringCacheSetting;
import com.github.xiaolyuh.setting.SecondaryCacheSetting;
import com.github.xiaolyuh.stats.CacheStats;
import com.github.xiaolyuh.support.ExpireMode;
import com.github.xiaolyuh.support.Lock;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

// SpringJUnit4ClassRunner再Junit环境下提供Spring TestContext Framework的功能。
@RunWith(SpringJUnit4ClassRunner.class)
// @ContextConfiguration用来加载配置ApplicationContext，其中classes用来加载配置类
@ContextConfiguration(classes = {CacheConfig.class})
public class CacheCoreTest {
    private Logger logger = LoggerFactory.getLogger(CacheCoreTest.class);

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private LayeringCacheSetting layeringCacheSetting1;
    private LayeringCacheSetting layeringCacheSetting2;

    @Before
    public void testGetCache() {
        // 测试 CacheManager getCache方法
        FirstCacheSetting firstCacheSetting1 = new FirstCacheSetting(10, 1000, 4, TimeUnit.SECONDS, ExpireMode.WRITE);
        SecondaryCacheSetting secondaryCacheSetting1 = new SecondaryCacheSetting(10, 4, TimeUnit.SECONDS, true);
        layeringCacheSetting1 = new LayeringCacheSetting(firstCacheSetting1, secondaryCacheSetting1, "");

        FirstCacheSetting firstCacheSetting2 = new FirstCacheSetting(10, 1000, 5, TimeUnit.SECONDS, ExpireMode.WRITE);
        SecondaryCacheSetting secondaryCacheSetting2 = new SecondaryCacheSetting(3000, 14, TimeUnit.SECONDS, true);
        layeringCacheSetting2 = new LayeringCacheSetting(firstCacheSetting2, secondaryCacheSetting2, "");

        String cacheName = "cache:name";
        Cache cache1 = cacheManager.getCache(cacheName, layeringCacheSetting1);
        Cache cache2 = cacheManager.getCache(cacheName, layeringCacheSetting1);
        Assert.assertEquals(cache1, cache2);

        Cache cache3 = cacheManager.getCache(cacheName, layeringCacheSetting2);
        Collection<Cache> caches = cacheManager.getCache(cacheName);
        Assert.assertTrue(caches.size() == 2);
        Assert.assertNotEquals(cache1, cache3);
    }


    @Test
    public void testCacheExpiration() {
        // 测试 缓存过期时间
        String cacheName = "cache:name";
        String cacheKey1 = "cache:key1";
        LayeringCache cache1 = (LayeringCache) cacheManager.getCache(cacheName, layeringCacheSetting1);
        cache1.get(cacheKey1, () -> initCache(String.class));
        // 测试一级缓存值及过期时间
        String str1 = cache1.getFirstCache().get(cacheKey1, String.class);
        String st2 = cache1.getFirstCache().get(cacheKey1, () -> initCache(String.class));
        logger.debug("========================:{}", str1);
        Assert.assertTrue(str1.equals(st2));
        Assert.assertTrue(str1.equals(initCache(String.class)));
        sleep(5);
        Assert.assertNull(cache1.getFirstCache().get(cacheKey1, String.class));
        // 看日志是不是走了二级缓存
        cache1.get(cacheKey1, () -> initCache(String.class));

        // 测试二级缓存
        str1 = cache1.getSecondCache().get(cacheKey1, String.class);
        st2 = cache1.getSecondCache().get(cacheKey1, () -> initCache(String.class));
        Assert.assertTrue(st2.equals(str1));
        Assert.assertTrue(str1.equals(initCache(String.class)));
        sleep(5);
        // 看日志是不是走了自动刷新
        RedisCacheKey redisCacheKey = ((RedisCache) cache1.getSecondCache()).getRedisCacheKey(cacheKey1);
        cache1.get(cacheKey1, () -> initCache(String.class));
        sleep(6);
        Long ttl = redisTemplate.getExpire(redisCacheKey.getKey());
        logger.debug("========================ttl 1:{}", ttl);
        Assert.assertNotNull(cache1.getSecondCache().get(cacheKey1));
        sleep(5);
        ttl = redisTemplate.getExpire(redisCacheKey.getKey());
        logger.debug("========================ttl 2:{}", ttl);
        Assert.assertNull(cache1.getSecondCache().get(cacheKey1));
    }

    @Test
    public void testGetType() throws Exception {
        // 测试 缓存过期时间
        String cacheName = "cache:name";
        String cacheKey1 = "cache:key:22";
        LayeringCache cache1 = (LayeringCache) cacheManager.getCache(cacheName, layeringCacheSetting1);
        cache1.get(cacheKey1, () -> null);
        String str1 = cache1.get(cacheKey1, String.class);
        Assert.assertNull(str1);
        sleep(11);
        cache1.get(cacheKey1, () -> initCache(String.class));

        str1 = cache1.get(cacheKey1, String.class);
        Assert.assertEquals(str1, initCache(String.class));
    }

    @Test
    public void testCacheEvict() throws Exception {
        // 测试 缓存过期时间
        String cacheName = "cache:name";
        String cacheKey1 = "cache:key2";
        String cacheKey2 = "cache:key3";
        LayeringCache cache1 = (LayeringCache) cacheManager.getCache(cacheName, layeringCacheSetting1);
        cache1.get(cacheKey1, () -> initCache(String.class));
        cache1.get(cacheKey2, () -> initCache(String.class));
        // 测试删除方法
        cache1.evict(cacheKey1);
        Thread.sleep(500);
        String str1 = cache1.get(cacheKey1, String.class);
        String str2 = cache1.get(cacheKey2, String.class);
        Assert.assertNull(str1);
        Assert.assertNotNull(str2);
        // 测试删除方法
        cache1.evict(cacheKey1);
        Thread.sleep(500);
        str1 = cache1.get(cacheKey1, () -> initCache(String.class));
        str2 = cache1.get(cacheKey2, String.class);
        Assert.assertNotNull(str1);
        Assert.assertNotNull(str2);
    }

    @Test
    public void testCacheClear() throws Exception {
        // 测试 缓存过期时间
        String cacheName = "cache:name";
        String cacheKey1 = "cache:key4";
        String cacheKey2 = "cache:key5";
        LayeringCache cache = (LayeringCache) cacheManager.getCache(cacheName, layeringCacheSetting1);
        cache.get(cacheKey1, () -> initCache(String.class));
        cache.get(cacheKey2, () -> initCache(String.class));
        // 测试清除方法
        cache.clear();
        Thread.sleep(500);
        String str1 = cache.get(cacheKey1, String.class);
        String str2 = cache.get(cacheKey2, String.class);
        Assert.assertNull(str1);
        Assert.assertNull(str2);
        // 测试清除方法
        cache.clear();
        Thread.sleep(500);
        str1 = cache.get(cacheKey1, () -> initCache(String.class));
        str2 = cache.get(cacheKey2, () -> initCache(String.class));
        Assert.assertNotNull(str1);
        Assert.assertNotNull(str2);
    }

    @Test
    public void testCachePut() throws Exception {
        // 测试 缓存过期时间
        String cacheName = "cache:name";
        String cacheKey1 = "cache:key6";
        LayeringCache cache = (LayeringCache) cacheManager.getCache(cacheName, layeringCacheSetting1);
        String str1 = cache.get(cacheKey1, String.class);
        Assert.assertNull(str1);

        cache.put(cacheKey1, "test1");
        str1 = cache.get(cacheKey1, String.class);
        Assert.assertEquals(str1, "test1");

        cache.put(cacheKey1, "test2");
        str1 = cache.get(cacheKey1, String.class);
        Assert.assertEquals(str1, "test2");
    }

    @Test
    public void testCachePutIfAbsent() throws Exception {
        // 测试 缓存过期时间
        String cacheName = "cache:name";
        String cacheKey1 = "cache:key7";
        LayeringCache cache = (LayeringCache) cacheManager.getCache(cacheName, layeringCacheSetting1);
        cache.putIfAbsent(cacheKey1, "test1");
        String str1 = cache.get(cacheKey1, String.class);
        Assert.assertEquals(str1, "test1");

        cache.putIfAbsent(cacheKey1, "test2");
        str1 = cache.get(cacheKey1, String.class);
        Assert.assertEquals(str1, "test1");
    }


    /**
     * 测试统计
     */
    @Test
    public void testStats() {
        // 测试 缓存过期时间
        String cacheName = "cache:name:1";
        String cacheKey1 = "cache:key:123";
        LayeringCache cache1 = (LayeringCache) cacheManager.getCache(cacheName, layeringCacheSetting1);
        cache1.get(cacheKey1, () -> initCache(String.class));
        cache1.get(cacheKey1, () -> initCache(String.class));
        sleep(5);
        cache1.get(cacheKey1, () -> initCache(String.class));

        sleep(11);
        cache1.get(cacheKey1, () -> initCache(String.class));

        CacheStats cacheStats  = cache1.getCacheStats();
        CacheStats cacheStats2  = cache1.getCacheStats();
        Assert.assertEquals(cacheStats.getCacheRequestCount().longValue(), cacheStats2.getCacheRequestCount().longValue());
        Assert.assertEquals(cacheStats.getCachedMethodRequestCount().longValue(), cacheStats2.getCachedMethodRequestCount().longValue());
        Assert.assertEquals(cacheStats.getCachedMethodRequestTime().longValue(), cacheStats2.getCachedMethodRequestTime().longValue());

        logger.debug("缓请求数：{}", cacheStats.getCacheRequestCount());
        logger.debug("被缓存方法请求数：{}", cacheStats.getCachedMethodRequestCount());
        logger.debug("被缓存方法请求总耗时：{}", cacheStats.getCachedMethodRequestTime());

        Assert.assertEquals(cacheStats.getCacheRequestCount().longValue(), 4);
        Assert.assertEquals(cacheStats.getCachedMethodRequestCount().longValue(), 2);
        Assert.assertTrue(cacheStats.getCachedMethodRequestTime().longValue() >= 0);
    }

    /**
     * 测试锁
     */
    @Test
    public void testLock() {
        Lock lock = new Lock(redisTemplate, "test:123");
        lock.lock();
        lock.unlock();
    }

    private <T> T initCache(Class<T> t) {
        logger.debug("加载缓存");
        return (T) "test";
    }

    private void sleep(int time) {
        try {
            Thread.sleep(time * 1000);
        } catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
    }
}

