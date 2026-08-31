package org.example.short_link.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import jakarta.annotation.Resource;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.example.short_link.config.ShortCodeConfig;
import org.example.short_link.constant.CommonConstants;
import org.example.short_link.dao.ShortUrlDao;
import org.example.short_link.entity.ShortUrlMapping;
import org.example.short_link.dto.CacheCheckResult;
import org.example.short_link.dto.CreateShortUrlRequest;
import org.example.short_link.dto.CreateShortUrlResponse;
import org.example.short_link.util.DigestUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.example.short_link.constant.CommonConstants.NEW_SHARDING_DATABASE_COUNT;
import static org.example.short_link.constant.CommonConstants.NEW_SHARDING_TABLE_COUNT;

/**
 * 短链url服务 - 集群分片版本
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class ShortUrlService {
    // 使用集群感知缓存服务替代原有的CacheService
    private final ClusterAwareCacheService clusterAwareCacheService;
    // 短链数据访问层
    private final ShortUrlDao shortUrlDao;
    // Redis集群分片策略服务
    private final ShardingStrategyService shardingStrategyService;
    // 分布式锁服务
    private final DistributedLockService distributedLockService;
    // 短链编码服务
    private final ShortCodeService shortCodeService;
    // 短链生成配置
    private final ShortCodeConfig shortCodeConfig;
    // 布隆过滤器service
    private final TieredBloomFilterService tieredBloomFilterService;
    // 编程式事务管理
    private final TransactionTemplate transactionTemplate;
    // 持久化上下文
    private final EntityManager entityManager;

    @Value("${shortlink.shorturl.domain:http://localhost:8001}")
    private String domain;
    // 双写开关
    @Value("${shortlink.expansion.dual-write-enabled:false}")
    private boolean dualWriteEnabled;
    // 默认过期时间
    @Value("${shortlink.shorturl.default-expire-days:0}")
    private Integer defaultExpireDays;

    /**
     * 创建短链（支持分库分表和Redis集群分片）
     */
    @SentinelResource(
            value = "createShortUrl",
            blockHandler = "createShortUrlBlockHandler",
            fallback = "createShortUrlFallback"
    )
    public CreateShortUrlResponse createShortUrl(CreateShortUrlRequest request) {
        // 生成多重哈希值
        List<String> urlHashes = DigestUtils.generateMultipleUrlHashes(request.getOriginUrl());

        // 缓存检查
        CacheCheckResult cacheCheckResult = smartCacheCheck(request.getOriginUrl(), urlHashes);
        if (cacheCheckResult.getCreateShortUrlResponse() != null) {
            return cacheCheckResult.getCreateShortUrlResponse();
        }
        // 获取可用的哈希值
        String primaryUrlHash = cacheCheckResult.getCurrentHash();

        // 第二层防护：使用分布式锁保证同一URL的串行处理
        String lockKey = "create_url:" + primaryUrlHash;
        return distributedLockService.executeWithLock(lockKey, () -> {
            // 在锁内只检查单个哈希值的缓存状态（避免重复的多重哈希检查）
            String cachedShortCode = clusterAwareCacheService.getShortCodeByUrlHash(primaryUrlHash);
            if (StringUtils.isNotBlank(cachedShortCode)) {
                // 直接从缓存获取完整信息，避免额外的getShortUrlInfo调用
                ShortUrlMapping cachedMapping = clusterAwareCacheService.getFromCache(cachedShortCode);
                if (cachedMapping != null && request.getOriginUrl().equals(cachedMapping.getOriginUrl())) {
                    log.info("分布式锁内缓存命中: shortCode={}, originUrl={}", cachedShortCode, request.getOriginUrl());
                    return buildResponse(cachedMapping);
                }
            }

            // 使用分布式ID生成策略 生成短链code
            final String initialShortCode = shortCodeService.generateByStrategy(request.getOriginUrl());

            // 根据shortCode计算精确的分片位置
            int v = initialShortCode.hashCode() & 0x7fffffff;
            int tableIndex = v % NEW_SHARDING_TABLE_COUNT;
            int dbIndex = (v / NEW_SHARDING_TABLE_COUNT) % NEW_SHARDING_DATABASE_COUNT;

            ShortUrlMapping mapping = shortUrlDao.preCheckByShortCode(dbIndex, tableIndex, initialShortCode, primaryUrlHash, request.getOriginUrl())
                    .orElse(null);
            if (mapping != null) {
                // 缓存查询结果
                clusterAwareCacheService.putToCache(mapping.getShortCode(), mapping);
                clusterAwareCacheService.putUrlHashMapping(primaryUrlHash, mapping.getShortCode());
                log.info("数据库智能查询命中，返回已存在短链: shortCode={}, originUrl={}",
                        mapping.getShortCode(), request.getOriginUrl());
                return buildResponse(mapping);
            }

            try {
                // 第三层防护：数据库事务 + 唯一索引
                return transactionTemplate.execute(status -> {
                    try {
                        String currentShortCode = initialShortCode;

                        // 检查生成的短链code是否已存在（避免哈希冲突）
                        Optional<ShortUrlMapping> existingByCode = shortUrlDao.findByShortCode(currentShortCode);
                        if (existingByCode.isPresent()) {
                            ShortUrlMapping existing = existingByCode.get();
                            // 如果是相同的原始URL，直接返回
                            if (request.getOriginUrl().equals(existing.getOriginUrl())) {
                                clusterAwareCacheService.putToCache(currentShortCode, existing);
                                clusterAwareCacheService.putUrlHashMapping(primaryUrlHash, currentShortCode);
                                log.info("发现相同URL的短链: shortCode={}, originUrl={}, 数据库分片: db={}, table={}, Redis分片槽位={}",
                                        currentShortCode, request.getOriginUrl(),
                                        calculateDatabaseIndex(currentShortCode), calculateTableIndex(currentShortCode),
                                        shardingStrategyService.calculateSlot(currentShortCode));
                                return buildResponse(existing);
                            } else {
                                // 哈希冲突，重新生成
                                int retryCount = 0;
                                int maxRetries = shortCodeConfig.getMaxRetries();
                                while (existingByCode.isPresent() && retryCount < maxRetries) {
                                    currentShortCode = shortCodeService.generateByStrategy(request.getOriginUrl());
                                    existingByCode = shortUrlDao.findByShortCode(currentShortCode);
                                    retryCount++;
                                }
                                if (retryCount >= maxRetries) {
                                    throw new RuntimeException("生成短链失败，请重试");
                                }
                            }
                        }

                        // 创建新的短链记录
                        ShortUrlMapping shortUrlMapping = new ShortUrlMapping();
                        shortUrlMapping.setShortCode(currentShortCode);
                        shortUrlMapping.setOriginUrl(request.getOriginUrl());
                        shortUrlMapping.setOriginUrlHash(primaryUrlHash);
                        shortUrlMapping.setExpireDays(request.getExpireDays() != null ? request.getExpireDays() : defaultExpireDays);
                        shortUrlMapping.setCreator(request.getCreator());
                        shortUrlMapping.setStatus(1);
                        shortUrlMapping.setCreateTime(LocalDateTime.now());

                        // 在保存到数据库之前添加日志
                        log.info("准备保存短链: shortCode={}, originUrl={}, originUrlHash={},Redis分片槽位={}",
                                currentShortCode, request.getOriginUrl(), primaryUrlHash,
                                shardingStrategyService.calculateSlot(currentShortCode));

                        int v2 = currentShortCode.hashCode() & 0x7fffffff;
                        int tableIndex2 = v2 % NEW_SHARDING_TABLE_COUNT;
                        int dbIndex2 = (v2 / NEW_SHARDING_TABLE_COUNT) % NEW_SHARDING_DATABASE_COUNT;
                        shortUrlMapping = shortUrlDao.save(shortUrlMapping, dbIndex2, tableIndex2);

                        entityManager.flush();

                        log.info("短链保存完成: shortCode={}, 实际shortCode={}", currentShortCode, shortUrlMapping.getShortCode());

                        // 添加到布隆过滤器
                        tieredBloomFilterService.put(shortUrlMapping.getShortCode());

                        // 缓存短链信息和URL哈希映射到Redis集群
                        clusterAwareCacheService.putToCache(shortUrlMapping.getShortCode(), shortUrlMapping);
                        clusterAwareCacheService.putUrlHashMapping(primaryUrlHash, shortUrlMapping.getShortCode());

                        log.info("创建短链成功: shortCode={}, originUrl={}, Redis分片槽位={}",
                                shortUrlMapping.getShortCode(), request.getOriginUrl(),
                                shardingStrategyService.calculateSlot(shortUrlMapping.getShortCode()));
                        return buildResponse(shortUrlMapping);

                    } catch (Exception e) {
                        status.setRollbackOnly();
                        throw e;
                    }
                });

            } catch (Exception e) {
                log.error("创建短链失败: originUrl={}, error={}", request.getOriginUrl(), e.getMessage(), e);
                throw new RuntimeException("创建短链失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 智能缓存验证 - 批量检查多个哈希值
     * 返回第一个匹配的缓存结果，如果都不匹配则返回null
     */
    private CacheCheckResult smartCacheCheck(String originalUrl, List<String> urlHashes) {
        for (String urlHash : urlHashes) {
            String cachedShortCode = clusterAwareCacheService.getShortCodeByUrlHash(urlHash);
            if (StringUtils.isNotBlank(cachedShortCode)) {
                // 验证缓存的短链是否有效
                CreateShortUrlResponse cachedResponse = getShortUrlInfo(cachedShortCode);
                if (cachedResponse != null && originalUrl.equals(cachedResponse.getOriginUrl())) {
                    log.info("智能缓存命中: urlHash={}, shortCode={}, originUrl={}",
                            urlHash, cachedShortCode, originalUrl);
                    return new CacheCheckResult(cachedResponse, urlHash);
                } else {
                    // 发现哈希冲突，记录日志但不删除映射（因为这可能是其他URL的正确映射）
                    log.warn("检测到哈希冲突，跳过此哈希值: urlHash={}, 缓存URL={}, 请求URL={}",
                            urlHash, cachedResponse != null ? cachedResponse.getOriginUrl() : "null", originalUrl);
                    // 不删除映射，继续尝试下一个哈希值
                }
            } else {
                // 找到第一个可用的哈希值，直接返回
                log.debug("找到可用哈希值: urlHash={}", urlHash);
                return new CacheCheckResult(null, urlHash);
            }
        }

        // 如果所有哈希值都冲突，抛出异常
        throw new RuntimeException("创建短链hash值失败，请稍后重试");
    }

    /**
     * 获取短链信息（支持分库分表和Redis集群分片）
     */
    @SentinelResource(
            value = "getShortUrlInfo",
            blockHandler = "getShortUrlInfoBlockHandler",
            fallback = "getShortUrlInfoFallback"
    )
    public CreateShortUrlResponse getShortUrlInfo(String shortCode) {
        // 参数校验
        if (StringUtils.isBlank(shortCode)) {
            return null;
        }

        // 先检查布隆过滤器
        if (!tieredBloomFilterService.mightContain(shortCode)) {
            log.debug("布隆过滤器检查失败: shortCode={}", shortCode);
            return null;
        }

        // 获取短链映射（会自动路由到正确分片）
        ShortUrlMapping shortUrlMapping = getShortUrlWithSentinel(shortCode);
        if (shortUrlMapping == null) {
            log.debug("短链不存在: shortCode={}", shortCode);
            return null;
        }

        // 检查是否过期
        if (shortUrlMapping.isExpired()) {
            log.debug("短链已过期: shortCode={}, createTime={}, expireDays={}",
                    shortCode, shortUrlMapping.getCreateTime(), shortUrlMapping.getExpireDays());
            return null;
        }

        // 检查状态
        if (shortUrlMapping.getStatus() == null || shortUrlMapping.getStatus() != 1) {
            log.debug("短链状态异常: shortCode={}, status={}", shortCode, shortUrlMapping.getStatus());
            return null;
        }

        return buildResponse(shortUrlMapping);
    }

    /**
     * 带Sentinel保护的短链查询（支持分库分表和Redis集群分片）
     */
    @SentinelResource(
            value = "databaseQuery",
            blockHandler = "databaseQueryBlockHandler",
            fallback = "databaseQueryFallback"
    )
    private ShortUrlMapping getShortUrlWithSentinel(String shortCode) {
        // 从集群缓存获取
        ShortUrlMapping shortUrlMapping = clusterAwareCacheService.getFromCache(shortCode);
        if (shortUrlMapping != null) {
            return shortUrlMapping;
        }

        // 缓存未命中，查询数据库（ShardingSphere会自动路由到正确的分片）
        try {
            Optional<ShortUrlMapping> optional = shortUrlDao.findByShortCode(shortCode);
            if (optional.isPresent()) {
                shortUrlMapping = optional.get();
                // 缓存查询结果到Redis集群
                clusterAwareCacheService.putToCache(shortCode, shortUrlMapping);
                log.debug("数据库查询成功: shortCode={}, 数据库分片: db={}, table={}, Redis分片槽位={}",
                        shortCode, calculateDatabaseIndex(shortCode), calculateTableIndex(shortCode),
                        shardingStrategyService.calculateSlot(shortCode));
                return shortUrlMapping;
            }
        } catch (Exception e) {
            log.error("数据库查询失败: shortCode={}, error={}", shortCode, e.getMessage(), e);
            throw e;
        }

        recordNotExistLog(shortCode);
        return null;
    }

    /**
     * 构建响应对象
     */
    private CreateShortUrlResponse buildResponse(ShortUrlMapping shortUrlMapping) {
        CreateShortUrlResponse response = new CreateShortUrlResponse();
        response.setShortCode(shortUrlMapping.getShortCode());
        response.setShortUrl(domain + "/" + shortUrlMapping.getShortCode());
        response.setOriginUrl(shortUrlMapping.getOriginUrl());
        response.setCreateTime(shortUrlMapping.getCreateTime());
        response.setExpireDays(shortUrlMapping.getExpireDays());
        response.setAccessCount(shortUrlMapping.getAccessCount());
        return response;
    }

    /**
     * 记录日志
     * @param shortCode
     */
    private void recordNotExistLog(String shortCode) {
        log.debug("短链不存在: shortCode={}", shortCode);
    }

    /**
     * 计算数据库索引（更新为32个数据库）
     */
    private int calculateDatabaseIndex(String shortCode) {
        int v = shortCode.hashCode() & 0x7fffffff;
        if (dualWriteEnabled) {
            return (v / NEW_SHARDING_TABLE_COUNT) % NEW_SHARDING_DATABASE_COUNT;
        }
        return (v / CommonConstants.SHARDING_TABLE_COUNT) % CommonConstants.SHARDING_DATABASE_COUNT;
    }

    /**
     * 计算表索引（更新为256张表）
     */
    private int calculateTableIndex(String shortCode) {
        int v = shortCode.hashCode() & 0x7fffffff;
        if (dualWriteEnabled) {
            return v % NEW_SHARDING_TABLE_COUNT;
        }
        return v % CommonConstants.SHARDING_TABLE_COUNT;
    }

    // ==================== Sentinel 处理方法 ====================

    public CreateShortUrlResponse createShortUrlBlockHandler(CreateShortUrlRequest request, BlockException ex) {
        log.warn("创建短链被限流: originUrl={}", request != null ? request.getOriginUrl() : "null");
        CreateShortUrlResponse response = new CreateShortUrlResponse();
        response.setShortCode("RATE_LIMITED");
        response.setShortUrl("系统繁忙，请稍后重试");
        return response;
    }

    public CreateShortUrlResponse createShortUrlFallback(CreateShortUrlRequest request, Throwable ex) {
        log.error("创建短链降级: originUrl={}, error={}",
                request != null ? request.getOriginUrl() : "null", ex.getMessage());
        CreateShortUrlResponse response = new CreateShortUrlResponse();
        response.setShortCode("SERVICE_DEGRADED");
        response.setShortUrl("创建短链失败，请稍后重试");
        return response;
    }

    public ShortUrlMapping databaseQueryBlockHandler(String shortCode, BlockException ex) {
        log.warn("数据库查询被限流: shortCode={}", shortCode);
        return null;
    }

    public ShortUrlMapping databaseQueryFallback(String shortCode, Throwable ex) {
        log.error("数据库查询降级: shortCode={}, error={}", shortCode, ex.getMessage());
        return null;
    }

    // ============================================================
}
