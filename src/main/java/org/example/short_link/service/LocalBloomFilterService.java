package org.example.short_link.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.short_link.filter.TimeSliceBloomFilter;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地时间片布隆过滤器服务
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class LocalBloomFilterService {
    // Redisson 客户端实例
    private final RedissonClient redissonClient;

    // 每个时间片的时长
    @Value("${shortlink.bloom.time-slice.hours:6}")
    private int timeSliceHours;

    // 本地内存中保留的最大时间片数量
    @Value("${shortlink.bloom.time-slice.local-keep-count:8}")
    private int localKeepSliceCount;

    // 本地时间片布隆过滤器的缓存容器
    private final ConcurrentMap<String, TimeSliceBloomFilter> localTimeSlices = new ConcurrentHashMap<>();

    // 当前所处时间片的关键标识
    private volatile String currentLocalTimeSlice;
    // 预热进度控制（内存计数器与基准头片）
    private volatile String prewarmBaselineHeadKey;
    // 已初始化的本地时间片计数
    private final AtomicInteger prewarmInitializedCount = new AtomicInteger(0);
    // 预热是否已全部完成的标志
    private volatile boolean prewarmAllDone = false;

    /**
     * 初始化当前时间片
     */
    @PostConstruct
    public void init() {
        currentLocalTimeSlice = getCurrentLocalSliceKey();
        createLocalTimeSlice(currentLocalTimeSlice);
        prewarmBaselineHeadKey = currentLocalTimeSlice;
        prewarmInitializedCount.set(1); // 当前片已初始化
        prewarmAllDone = false;
        log.info("本地时间片初始化完成，当前片: {}", currentLocalTimeSlice);
    }

    /**
     * 检查短链码是否可能存在于本地任意时间片中
     * @param shortCode
     * @return
     */
    public boolean mightContain(String shortCode) {
        for (TimeSliceBloomFilter slice : localTimeSlices.values()) {
            if (slice.mightContain(shortCode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将短链码添加到本地当前时间片的布隆过滤器中
     */
    public void addLocal(String shortCode) {
        String key = currentLocalTimeSlice;
        if (key == null) {
            key = getCurrentLocalSliceKey();
            createLocalTimeSlice(key);
            currentLocalTimeSlice = key;
        }
        TimeSliceBloomFilter slice = localTimeSlices.get(key);
        if (slice != null) {
            slice.add(shortCode);
        }
        log.debug("本地添加短链到时间片布隆过滤器: {} (片: {})", shortCode, key);
    }

    /**
     * 清理过期时间片
     */
    @Scheduled(fixedRate = 300000)
    public void cleanupLocalSlices() {
        try {
            doCleanupLocalSlices();
        } catch (Exception e) {
            log.error("清理本地过期时间片失败", e);
        }
    }

    private void doCleanupLocalSlices() {
        List<String> expired = new ArrayList<>();
        for (String sliceKey : localTimeSlices.keySet()) {
            if (isLocalSliceExpired(sliceKey)) {
                expired.add(sliceKey);
            }
        }
        for (String key : expired) {
            TimeSliceBloomFilter removed = localTimeSlices.remove(key);
            if (removed != null) {
                log.info("清理本地过期时间片: {}, 估计元素数: {}", key, removed.getApproximateElementCount());
            }
        }
        log.info("本地时间片清理完成，当前活跃片数: {}", localTimeSlices.size());
    }

    /**
     * 每次仅预热一个缺失的本地时间片（按 Redis 时间片从新到旧）
     * 当目标范围内全部本地片已初始化，则本次预热跳过
     */
    @Scheduled(fixedRateString = "${shortlink.bloom.local.prewarm.fixed-rate-ms:300000}")
    public void initLocalSlicesFromRedis() {
        try {
            // 若时间片头部发生变化（进入新时间片），重置预热进度
            String expectedHead = getCurrentLocalSliceKey();
            if (prewarmBaselineHeadKey == null || !prewarmBaselineHeadKey.equals(expectedHead)) {
                prewarmBaselineHeadKey = expectedHead;
                prewarmInitializedCount.set(localTimeSlices.containsKey(expectedHead) ? 1 : 0);
                prewarmAllDone = false;
                log.debug("预热基准头片更新为: {}，已初始化计数: {}", expectedHead, prewarmInitializedCount.get());
            }

            // 若已完成，直接跳过
            if (prewarmAllDone) {
                log.debug("预热跳过：预热已完成，计数: {}/{}", prewarmInitializedCount.get(), Math.max(localKeepSliceCount, 1));
                return;
            }

            List<String> redisKeys = listExistingRedisSliceKeysSorted();
            int target = Math.max(localKeepSliceCount, 1);
            // 组装目标范围（最新到旧）
            List<String> targetKeys = new java.util.ArrayList<>();
            for (String rk : redisKeys) {
                targetKeys.add(rk);
                if (targetKeys.size() >= target) {
                    break;
                }
            }

            // 计算当前已初始化数量，并找出缺失的第一个本地片
            int currentCount = 0;
            String firstMissingLocalKey = null;
            for (String rk : targetKeys) {
                String lk = toLocalSliceKey(rk);
                if (localTimeSlices.containsKey(lk)) {
                    currentCount++;
                } else if (firstMissingLocalKey == null) {
                    firstMissingLocalKey = lk;
                }
            }
            prewarmInitializedCount.set(currentCount);

            // 若目标范围已全部存在，则标记完成并跳过
            if (currentCount >= target || firstMissingLocalKey == null) {
                prewarmAllDone = true;
                log.debug("预热跳过：目标范围已全部存在，计数: {}/{}", currentCount, target);
                return;
            }

            // 每次仅初始化一个缺失片
            createLocalTimeSlice(firstMissingLocalKey);
            int after = prewarmInitializedCount.incrementAndGet();
            log.info("预热本地时间片: {}，进度: {}/{}", firstMissingLocalKey, after, target);
            if (after >= target) {
                prewarmAllDone = true;
                log.info("本地时间片预热全部完成: {}/{}", after, target);
            }
        } catch (Exception e) {
            log.error("本地时间片预热失败", e);
        }
    }

    /**
     * 获取本地布隆过滤器服务的统计信息
     * @return
     */
    public String getLocalStats() {
        long totalElements = localTimeSlices.values().stream()
                .mapToLong(TimeSliceBloomFilter::getApproximateElementCount).sum();
        return String.format("本地时间分片统计 - 活跃片数: %d, 当前片: %s, 总元素数: %d, 保留策略: %d天",
                localTimeSlices.size(), currentLocalTimeSlice,
                totalElements, (timeSliceHours * localKeepSliceCount) / 24);
    }

    /**
     * 根据时间窗口探测可能存在的Redis时间片，并按新到旧排序
     * @return
     */
    private List<String> listExistingRedisSliceKeysSorted() {
        List<String> keys = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        int window = Math.max(localKeepSliceCount, 1) * 4; // 探测更宽窗口，避免遗漏
        for (int i = 0; i < window; i++) {
            LocalDateTime t = now.minusHours(i * timeSliceHours);
            String sliceKey = getRedisSliceKey(t);
            try {
                RBloomFilter<String> slice = redissonClient.getBloomFilter(sliceKey);
                if (slice.isExists()) {
                    keys.add(sliceKey);
                }
            } catch (Exception e) {
                log.warn("探测Redis时间片失败: {}", sliceKey);
            }
        }
        keys.sort((a, b) -> parseRedisSliceTime(b).compareTo(parseRedisSliceTime(a)));
        return keys;
    }

    /**
     * 创建本地时间片布隆过滤器实例
     * @param sliceKey
     */
    private void createLocalTimeSlice(String sliceKey) {
        localTimeSlices.put(sliceKey, new TimeSliceBloomFilter(sliceKey));
    }

    /**
     * 判断给定的本地时间片是否已过期
     * @param sliceKey
     * @return
     */
    private boolean isLocalSliceExpired(String sliceKey) {
        try {
            LocalDateTime sliceTime = LocalDateTime.parse(sliceKey,
                    DateTimeFormatter.ofPattern("yyyyMMdd_HH"));
            LocalDateTime expireTime = sliceTime.plusHours(timeSliceHours * localKeepSliceCount);
            return LocalDateTime.now().isAfter(expireTime);
        } catch (Exception e) {
            log.warn("解析本地时间片key失败: {}", sliceKey);
            return true;
        }
    }

    /**
     * 获取当前时间所处的本地时间片 key
     * @return
     */
    private String getCurrentLocalSliceKey() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sliceTime = now.withMinute(0).withSecond(0).withNano(0)
                .withHour((now.getHour() / timeSliceHours) * timeSliceHours);
        return sliceTime.format(DateTimeFormatter.ofPattern("yyyyMMdd_HH"));
    }

    /**
     * 根据给定的日期时间生成 Redis 布隆过滤器的 key
     * @param dateTime
     * @return
     */
    private String getRedisSliceKey(LocalDateTime dateTime) {
        LocalDateTime sliceTime = dateTime.withMinute(0).withSecond(0).withNano(0)
                .withHour((dateTime.getHour() / timeSliceHours) * timeSliceHours);
        return "redis_bloom_" + sliceTime.format(DateTimeFormatter.ofPattern("yyyyMMdd_HH"));
    }

    /**
     * 将 Redis 布隆过滤器 key 转换为本地时间片 key
     * @param redisSliceKey
     * @return
     */
    private String toLocalSliceKey(String redisSliceKey) {
        return redisSliceKey.replace("redis_bloom_", "");
    }

    /**
     * 从 Redis 布隆过滤器 key 中解析出时间戳
     * @param redisSliceKey
     * @return
     */
    private LocalDateTime parseRedisSliceTime(String redisSliceKey) {
        try {
            String timeStr = toLocalSliceKey(redisSliceKey);
            return LocalDateTime.parse(timeStr, DateTimeFormatter.ofPattern("yyyyMMdd_HH"));
        } catch (Exception e) {
            log.warn("解析Redis时间片失败: {}", redisSliceKey);
            return LocalDateTime.MIN;
        }
    }
}
