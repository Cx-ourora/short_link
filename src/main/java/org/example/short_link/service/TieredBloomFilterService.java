package org.example.short_link.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.short_link.dto.NodeInfo;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分层布隆过滤器
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TieredBloomFilterService {
    // Redis时间分片布隆过滤器服务
    private final RedisTimeBasedBloomFilterService redisTimeBasedBloomFilter;

    /**
     * 添加到时间分片布隆过滤器
     */
    public void put(String shortCode) {
        if (shortCode == null || shortCode.trim().isEmpty()) {
            log.warn("shortCode为空或null");
            return;
        }
        try {
            // 统一由RedisTimeBasedBloomFilterService处理（内部包含：本地分片+Redis分片+发布Stream）
            redisTimeBasedBloomFilter.add(shortCode);

            log.debug("时间分片布隆过滤器添加成功: {}", shortCode);
        } catch (Exception e) {
            log.error("时间分片布隆过滤器添加失败: shortCode={}", shortCode, e);
        }
    }
    /**
     * 完全基于时间分片的分层检查
     */
    public boolean mightContain(String shortCode) {
        // 统一委托给RedisTimeBasedBloomFilterService（内部已包含本地+Redis两层检查）
        return redisTimeBasedBloomFilter.mightContain(shortCode);
    }

    /**
     * 获取节点信息
     * 包含节点ID、服务状态、统计信息等
     */
    public NodeInfo getNodeInfo() {
        try {
            // 获取节点ID（通过RedisTimeBasedBloomFilterService）
            String nodeId = redisTimeBasedBloomFilter.getNodeId();

            // 获取服务状态
            boolean localServiceActive = redisTimeBasedBloomFilter != null;
            boolean redisServiceActive = redisTimeBasedBloomFilter != null;
            boolean streamServiceActive = true; // 由统一服务内部发布

            // 获取统计信息
            String localStats = localServiceActive ? redisTimeBasedBloomFilter.getLocalStats() : "服务未激活";
            String redisStats = redisServiceActive ? redisTimeBasedBloomFilter.getRedisStats() : "服务未激活";

            // 获取系统信息
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory();

            return NodeInfo.builder()
                    .nodeId(nodeId)
                    .timestamp(System.currentTimeMillis())
                    .localServiceActive(localServiceActive)
                    .redisServiceActive(redisServiceActive)
                    .streamServiceActive(streamServiceActive)
                    .localStats(localStats)
                    .redisStats(redisStats)
                    .memoryUsedMB(usedMemory / 1024 / 1024)
                    .memoryTotalMB(totalMemory / 1024 / 1024)
                    .memoryMaxMB(maxMemory / 1024 / 1024)
                    .memoryUsagePercent((double) usedMemory / maxMemory * 100)
                    .build();

        } catch (Exception e) {
            log.error("获取节点信息失败", e);
            return NodeInfo.builder()
                    .nodeId("unknown")
                    .timestamp(System.currentTimeMillis())
                    .localServiceActive(false)
                    .redisServiceActive(false)
                    .streamServiceActive(false)
                    .localStats("获取失败: " + e.getMessage())
                    .redisStats("获取失败: " + e.getMessage())
                    .memoryUsedMB(0L)
                    .memoryTotalMB(0L)
                    .memoryMaxMB(0L)
                    .memoryUsagePercent(0.0)
                    .build();
        }
    }
}
