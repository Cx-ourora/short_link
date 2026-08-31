package org.example.short_link.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
}
