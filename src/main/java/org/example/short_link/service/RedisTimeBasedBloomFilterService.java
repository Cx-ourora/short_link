package org.example.short_link.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Redis时间分片布隆过滤器服务
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class RedisTimeBasedBloomFilterService {
    // redisson服务
    private final RedissonClient redissonClient;
    // 布隆过滤器Stream服务
    private final BloomFilterStreamService streamService;
    // 本地布隆过滤器服务
    private final LocalBloomFilterService localBloomFilterService;
    // Redis时间分片布隆过滤器映射
    private final ConcurrentMap<String, RBloomFilter<String>> redisTimeSlices = new ConcurrentHashMap<>();

    @Value("${shortlink.bloom.time-slice.hours:6}")
    private int timeSliceHours;

    @Value("${shortlink.bloom.time-slice.redis-keep-count:32}")
    private int redisKeepSliceCount; // Redis保留的时间片数量（8天）

    // 节点ID缓存
    private volatile String nodeId;

    // Redis当前时间片
    private volatile String currentTimeSlice;

    // 每个时间片配置
    private static final long EXPECTED_INSERTIONS = 216_000_000L; // 6小时预期容量
    private static final double FALSE_PROBABILITY = 0.01;

    @PostConstruct
    public void init() {
        // 初始化Redis当前时间片
        currentTimeSlice = getCurrentTimeSliceKey();
        // 初始化所有活跃的Redis时间分片
        initializeActiveTimeSlices();

        log.info("时间分片布隆过滤器初始化完成 - Redis当前片: {}, Redis活跃片数: {}",
                currentTimeSlice, redisTimeSlices.size());
    }

    /**
     * 初始化所有活跃的时间分片
     * 根据配置的保留时间片数量，加载所有可能存在的时间分片
     */
    private void initializeActiveTimeSlices() {
        LocalDateTime now = LocalDateTime.now();

        // 计算需要加载的时间片范围
        for (int i = 0; i < redisKeepSliceCount; i++) {
            LocalDateTime sliceTime = now.minusHours(i * timeSliceHours);
            String sliceKey = getTimeSliceKey(sliceTime);

            try {
                // 检查Redis中是否存在该时间片
                RBloomFilter<String> slice = redissonClient.getBloomFilter(sliceKey);
                if (slice.isExists()) {
                    redisTimeSlices.put(sliceKey, slice);
                    log.debug("加载已存在的时间分片: {}", sliceKey);
                } else if (sliceKey.equals(currentTimeSlice)) {
                    // 如果是当前时间片但不存在，则创建它
                    createRedisTimeSlice(sliceKey);
                }
            } catch (Exception e) {
                log.warn("初始化时间分片失败: {}, 错误: {}", sliceKey, e.getMessage());
            }
        }

        log.info("活跃时间分片初始化完成，加载了 {} 个时间分片", redisTimeSlices.size());
    }

    /**
     * 根据指定时间生成时间分片key
     */
    private String getTimeSliceKey(LocalDateTime dateTime) {
        LocalDateTime sliceTime = dateTime.withMinute(0).withSecond(0).withNano(0)
                .withHour((dateTime.getHour() / timeSliceHours) * timeSliceHours);
        return "redis_bloom_" + sliceTime.format(DateTimeFormatter.ofPattern("yyyyMMdd_HH"));
    }

    /**
     * 检查短码是否可能存在
     * 输入: 短码字符串
     * 输出: 如果本地或Redis任一时间片包含该短码，返回true，否则false
     */
    public boolean mightContain(String shortCode) {
        // 第一层：本地时间分片，零延迟，命中概率更高（委托本地服务）
        if (localBloomFilterService.mightContain(shortCode)) {
            return true;
        }

        // 第二层：Redis时间分片，兜底（跨节点共享）
        for (RBloomFilter<String> slice : redisTimeSlices.values()) {
            try {
                if (slice.contains(shortCode)) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("检查Redis时间分片失败: {}", e.getMessage());
            }
        }
        return false;
    }

    /**
     * 将短码写入当前时间片
     * 输入: 短码字符串
     * 写入本地与Redis时间分片并发布Stream事件
     */
    public void add(String shortCode) {
        // 处理Redis时间片（使用同步块避免并发重复构造）
        String currentRedisSlice = getCurrentTimeSliceKey();
        if (!currentRedisSlice.equals(currentTimeSlice)) {
            synchronized (this) {
                if (!currentRedisSlice.equals(currentTimeSlice)) {
                    createRedisTimeSlice(currentRedisSlice);
                    currentTimeSlice = currentRedisSlice;
                    log.info("创建新的Redis时间片: {}", currentRedisSlice);
                }
            }
        }

        // 添加到当前Redis时间片
        RBloomFilter<String> redisSlice = redisTimeSlices.get(currentTimeSlice);
        if (redisSlice != null) {
            try {
                redisSlice.add(shortCode);
            } catch (Exception e) {
                log.error("添加到Redis时间分片失败: slice={}, shortCode={}", currentTimeSlice, shortCode, e);
            }
        }

        // 发布到Stream同步其他节点的本地时间片
        try {
            streamService.publishNewShortCode(shortCode);
        } catch (Exception e) {
            log.warn("发布Stream事件失败: {}", e.getMessage());
        }
    }

    /**
     * 初始化Redis当前时间片
     * @return
     */
    private String getCurrentTimeSliceKey() {
        return getTimeSliceKey(LocalDateTime.now());
    }

    /**
     * 创建当前时间分片
     * @param sliceKey
     */
    private void createRedisTimeSlice(String sliceKey) {
        try {
            RBloomFilter<String> slice = redissonClient.getBloomFilter(sliceKey);
            if (!slice.isExists()) {
                slice.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);
            }
            redisTimeSlices.put(sliceKey, slice);
            log.info("创建Redis时间分片: {}", sliceKey);
        } catch (Exception e) {
            log.error("创建Redis时间分片失败: {}", sliceKey, e);
        }
    }

    /**
     * 定时清理过期的Redis时间片
     * 简化的清理方法：所有节点都执行，无需分布式锁
     */
    @Scheduled(fixedRate = 300000) // 每5分钟检查一次
    public void cleanupExpiredSlices() {
        try {
            log.debug("开始清理过期时间片 - 节点: {}", getNodeId());
            doCleanupExpiredSlices();
        } catch (Exception e) {
            log.error("清理过期时间片失败 - 节点: {}", getNodeId(), e);
        }
    }

    /**
     * 实际的清理逻辑（支持多节点并发执行）
     */
    private void doCleanupExpiredSlices() {
        try {
            List<String> expiredSlices = new ArrayList<>();

            // 1. 找出过期的时间片
            for (String sliceKey : redisTimeSlices.keySet()) {
                if (isSliceExpired(sliceKey)) {
                    expiredSlices.add(sliceKey);
                }
            }

            int memoryCleanedCount = 0;
            int redisCleanedCount = 0;

            // 2. 清理过期时间片
            for (String expiredSlice : expiredSlices) {
                // 2.1 清理内存映射（每个节点清理自己的）
                RBloomFilter<String> removed = redisTimeSlices.remove(expiredSlice);
                if (removed != null) {
                    memoryCleanedCount++;

                    try {
                        // 2.2 清理Redis数据（多节点并发执行，幂等安全）
                        if (removed.isExists()) {
                            removed.delete();
                            redisCleanedCount++;
                            log.info("✅ 清理时间片成功: {} - 节点: {}", expiredSlice, getNodeId());
                        } else {
                            log.debug("时间片已被清理: {} - 节点: {}", expiredSlice, getNodeId());
                        }
                    } catch (Exception e) {
                        log.warn("Redis清理失败: {} - 节点: {}, 错误: {}",
                                expiredSlice, getNodeId(), e.getMessage());
                        // 注意：即使Redis删除失败，内存已经清理，不会影响应用运行
                    }
                }
            }

            if (memoryCleanedCount > 0 || redisCleanedCount > 0) {
                log.info("🧹 清理完成 - 节点: {}, 内存清理: {}, Redis清理: {}, 剩余片数: {}",
                        getNodeId(), memoryCleanedCount, redisCleanedCount, redisTimeSlices.size());
            }

        } catch (Exception e) {
            log.error("清理过期时间片失败 - 节点: {}", getNodeId(), e);
        }
    }

    /**
     * 获取当前节点ID
     * 格式: hostname-pid-timestamp
     */
    public String getNodeId() {
        if (nodeId == null) {
            synchronized (this) {
                if (nodeId == null) {
                    try {
                        String hostname = InetAddress.getLocalHost().getHostName();
                        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
                        long timestamp = System.currentTimeMillis() % 100000; // 取后5位避免太长
                        nodeId = String.format("%s-%s-%d", hostname, pid, timestamp);
                    } catch (Exception e) {
                        // 如果获取失败，使用备用方案
                        nodeId = "node-" + System.currentTimeMillis() % 10000;
                        log.warn("获取节点信息失败，使用备用节点ID: {}", nodeId);
                    }
                    log.info("节点ID初始化: {}", nodeId);
                }
            }
        }
        return nodeId;
    }

    /**
     * 判断当前时间片是否过期
     * @param sliceKey
     * @return
     */
    private boolean isSliceExpired(String sliceKey) {
        try {
            // 从sliceKey中提取时间片标识符，格式为 "redis_bloom_yyyyMMdd_HH"
            String timeStr = sliceKey.replace("redis_bloom_", "");
            LocalDateTime sliceTime = LocalDateTime.parse(timeStr,
                    DateTimeFormatter.ofPattern("yyyyMMdd_HH"));
            // 使用Redis专用的保留时间片数量
            LocalDateTime expireTime = sliceTime.plusHours(timeSliceHours * redisKeepSliceCount);
            return LocalDateTime.now().isAfter(expireTime);
        } catch (Exception e) {
            log.warn("解析Redis时间片key失败: {}", sliceKey);
            return true; // 解析失败认为已过期
        }
    }

}
