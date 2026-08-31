package org.example.short_link.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.short_link.entity.ShortUrlMapping;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * 集群感知缓存服务
 * 使用Redis集群分片和Hash Tag策略优化数据分布
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ClusterAwareCacheService {
    private static final String CACHE_PREFIX = "short-link:";
    private static final String URL_CACHE_KEY = CACHE_PREFIX + "url:";
    private static final String COUNT_CACHE_KEY = CACHE_PREFIX + "count:";
    private static final String HASH_MAPPING_KEY = CACHE_PREFIX + "hash:";

    // 缓存过期时间
    private static final Duration DEFAULT_EXPIRE_TIME = Duration.ofHours(1);
    private static final Duration HOT_DATA_EXPIRE_TIME = Duration.ofHours(24);

    // redisson服务service
    private final RedissonClient redissonClient;
    // 本地缓存服务service
    private final LocalCacheService localCacheService;
    // Json序列化服务
    private final ObjectMapper objectMapper;
    // Redis集群分片策略服务
    private final ShardingStrategyService shardingStrategyService;

    @Value("${shortlink.cluster.enable-hash-tag:true}")
    private boolean enableHashTag;

    /**
     * 获取URL哈希映射
     * originUrlHash：原始 URL 的哈希值
     * 输出对应的短码，如果不存在则返回 null
     */
    public String getShortCodeByUrlHash(String originUrlHash) {
        try {
            String key = generateHashTagKey(HASH_MAPPING_KEY, originUrlHash);
            RBucket<String> bucket = redissonClient.getBucket(key);
            return bucket.get();
        } catch (Exception e) {
            log.error("获取URL哈希映射失败: hash={}, error={}", originUrlHash, e.getMessage());
            return null;
        }
    }

    /**
     * URL哈希映射缓存
     */
    public void putUrlHashMapping(String originUrlHash, String shortCode) {
        try {
            String key = generateHashTagKey(HASH_MAPPING_KEY, originUrlHash);
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.set(shortCode, DEFAULT_EXPIRE_TIME);

            log.debug("缓存URL哈希映射: hash={}, shortCode={}, 分片槽位: {}",
                    originUrlHash, shortCode, shardingStrategyService.calculateSlot(key));
        } catch (Exception e) {
            log.error("缓存URL哈希映射失败: hash={}, error={}", originUrlHash, e.getMessage());
        }
    }

    /**
     * 将短链信息放入集群缓存（支持Hash Tag）
     */
    public void putToCache(String shortCode, ShortUrlMapping shortUrlMapping) {
        if (!StringUtils.hasText(shortCode) || shortUrlMapping == null) {
            log.warn("参数为空，跳过缓存操作: shortCode={}", shortCode);
            return;
        }

        // 放入本地缓存
        localCacheService.putToLocalCache(shortCode, shortUrlMapping);

        // 放入Redis集群缓存
        cacheToRedisCluster(shortCode, shortUrlMapping);

        log.debug("缓存短链信息到集群: {}, 分片槽位: {}",
                shortCode, shardingStrategyService.calculateSlot(shortCode));
    }

    /**
     * 缓存到Redis集群
     */
    private void cacheToRedisCluster(String shortCode, ShortUrlMapping shortUrlMapping) {
        try {
            String key = generateHashTagKey(URL_CACHE_KEY, shortCode);
            // 转为json
            String json = objectMapper.writeValueAsString(shortUrlMapping);

            RBucket<String> bucket = redissonClient.getBucket(key);
            Duration expireTime = isHotData(shortUrlMapping) ? HOT_DATA_EXPIRE_TIME : DEFAULT_EXPIRE_TIME;
            bucket.set(json, expireTime);

            log.debug("Redis集群缓存成功: {}", shortCode);
        } catch (JsonProcessingException e) {
            log.error("Redis集群序列化失败: shortCode={}, error={}", shortCode, e.getMessage());
        } catch (Exception e) {
            log.error("Redis集群缓存失败: shortCode={}, error={}", shortCode, e.getMessage());
        }
    }

    /**
     * 判断是否为热点数据
     */
    private boolean isHotData(ShortUrlMapping shortUrlMapping) {
        return shortUrlMapping.getAccessCount() != null && shortUrlMapping.getAccessCount() > 1000;
    }

    /**
     * 从集群缓存获取短链信息（支持Hash Tag）
     */
    public ShortUrlMapping getFromCache(String shortCode) {
        if (!StringUtils.hasText(shortCode)) {
            log.warn("shortCode为空，无法获取缓存");
            return null;
        }

        // 先从本地缓存获取
        ShortUrlMapping shortUrlMapping = localCacheService.getFromLocalCache(shortCode);
        if (shortUrlMapping != null) {
            log.debug("本地缓存命中: {}", shortCode);
            return shortUrlMapping;
        }

        // 从Redis集群获取
        shortUrlMapping = getFromRedisCluster(shortCode);
        if (shortUrlMapping != null) {
            log.debug("Redis集群缓存命中: {}, 分片槽位: {}",
                    shortCode, shardingStrategyService.calculateSlot(shortCode));
            // 将Redis数据放入本地缓存
            localCacheService.putToLocalCache(shortCode, shortUrlMapping);
        }

        return shortUrlMapping;
    }

    /**
     * 从Redis集群获取数据
     */
    private ShortUrlMapping getFromRedisCluster(String shortCode) {
        try {
            String key = generateHashTagKey(URL_CACHE_KEY, shortCode);
            RBucket<String> bucket = redissonClient.getBucket(key);
            String json = bucket.get();

            if (json != null) {
                return objectMapper.readValue(json, ShortUrlMapping.class);
            }
        } catch (JsonProcessingException e) {
            log.error("Redis集群反序列化失败: shortCode={}, error={}", shortCode, e.getMessage());
        } catch (Exception e) {
            log.error("Redis集群查询失败: shortCode={}, error={}", shortCode, e.getMessage());
        }
        return null;
    }

    /**
     * 生成Hash Tag键（确保相关数据在同一分片）
     */
    private String generateHashTagKey(String prefix, String shortCode) {
        if (enableHashTag) {
            return prefix + "{" + shortCode + "}";
        }
        return prefix + shortCode;
    }
}