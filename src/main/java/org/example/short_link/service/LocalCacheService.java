package org.example.short_link.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.short_link.entity.ShortUrlMapping;
import org.springframework.aop.framework.AopContext;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 本地缓存服务
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class LocalCacheService {

    private final LocalCacheStreamService streamService;

    /**
     * 从本地缓存获取短链信息 - 使用自定义Key生成器
     */
    @Cacheable(value = "shortUrls", keyGenerator = "shortCodeKeyGenerator")
    public ShortUrlMapping getFromLocalCache(String shortCode) {
        // 参数校验
        if (!StringUtils.hasText(shortCode)) {
            log.error("getFromLocalCache: shortCode为空或null, shortCode={}", shortCode);
            return null;
        }

        log.debug("尝试从本地缓存获取: {}", shortCode);
        return null; // 本地缓存未命中时返回null
    }

    /**
     * 安全的缓存操作 - 带完整异常处理
     */
    public ShortUrlMapping safePutToLocalCache(String shortCode, ShortUrlMapping shortUrlMapping) {
        try {
            // 详细的参数验证和日志
            log.debug("safePutToLocalCache开始: shortCode='{}', shortUrlMapping={}",
                    shortCode, shortUrlMapping != null ? "not null" : "null");

            if (!StringUtils.hasText(shortCode)) {
                log.warn("safePutToLocalCache: shortCode无效, value='{}'", shortCode);
                return shortUrlMapping;
            }

            return  ((LocalCacheService) AopContext.currentProxy()).putToLocalCache(shortCode, shortUrlMapping);
        } catch (Exception e) {
            log.error("safePutToLocalCache失败: shortCode='{}', error={}",
                    shortCode, e.getMessage(), e);
            return shortUrlMapping;
        }
    }

    /**
     * 缓存短链信息到本地缓存 - 使用自定义Key生成器
     */
    @CachePut(value = "shortUrls", keyGenerator = "shortCodeKeyGenerator")
    public ShortUrlMapping putToLocalCache(String shortCode, ShortUrlMapping shortUrlMapping) {
        // 参数校验
        if (!StringUtils.hasText(shortCode)) {
            log.error("putToLocalCache: shortCode为空或null, shortCode={}", shortCode);
            throw new IllegalArgumentException("shortCode不能为空");
        }

        if (shortUrlMapping == null) {
            log.warn("putToLocalCache: shortUrlMapping为空，shortCode: {}", shortCode);
            return null;
        }

        log.debug("放入本地缓存: shortCode={}, originalUrl={}", shortCode, shortUrlMapping.getOriginUrl());

        // 新增：发布缓存更新事件到Stream
        if (streamService != null) {
            streamService.publishCachePut(shortCode);
        }

        return shortUrlMapping;
    }

    /**
     * 安全的缓存移除操作 - 带完整异常处理
     */
    public void safeEvictFromLocalCache(String shortCode) {
        try {
            log.debug("safeEvictFromLocalCache开始: shortCode='{}'", shortCode);

            if (!StringUtils.hasText(shortCode)) {
                log.warn("safeEvictFromLocalCache: shortCode无效, value='{}'", shortCode);
                return;
            }

            ((LocalCacheService) AopContext.currentProxy()).evictFromLocalCache(shortCode);
            log.debug("safeEvictFromLocalCache成功: shortCode='{}'", shortCode);
        } catch (Exception e) {
            log.error("safeEvictFromLocalCache失败: shortCode='{}', error={}",
                    shortCode, e.getMessage(), e);
        }
    }

    /**
     * 从本地缓存中移除指定的短链
     */
    @CacheEvict(value = "shortUrls", keyGenerator = "shortCodeKeyGenerator")
    public void evictFromLocalCache(String shortCode) {
        // 参数校验
        if (!StringUtils.hasText(shortCode)) {
            log.warn("evictFromLocalCache: shortCode为空或null, shortCode={}", shortCode);
            return;
        }

        log.debug("从本地缓存移除: shortCode={}", shortCode);

        // 新增：发布缓存移除事件到Stream
        if (streamService != null) {
            streamService.publishCacheEvict(shortCode);
        }
    }
}