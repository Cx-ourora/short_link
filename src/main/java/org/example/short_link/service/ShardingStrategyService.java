package org.example.short_link.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Redis集群分片策略服务
 */
@Slf4j
@Service
public class ShardingStrategyService {

    private static final int REDIS_CLUSTER_SLOTS = 16384;
    private static final MessageDigest MD5_DIGEST;

    static {
        try {
            MD5_DIGEST = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5算法不可用", e);
        }
    }

    /**
     * 计算Redis集群槽位
     * 使用CRC16算法，与Redis集群保持一致
     */
    public int calculateSlot(String key) {
        if (key == null || key.isEmpty()) {
            return 0;
        }

        // 提取Hash Tag（如果存在）
        String hashKey = extractHashTag(key);
        
        // 使用CRC16算法计算槽位
        return crc16(hashKey.getBytes(StandardCharsets.UTF_8)) % REDIS_CLUSTER_SLOTS;
    }

    /**
     * 提取Hash Tag
     * 格式: {tag} - 确保相关数据在同一分片
     */
    private String extractHashTag(String key) {
        int start = key.indexOf('{');
        if (start != -1) {
            int end = key.indexOf('}', start + 1);
            if (end != -1 && end != start + 1) {
                return key.substring(start + 1, end);
            }
        }
        return key;
    }

    /**
     * CRC16算法实现（与Redis集群一致）
     */
    private int crc16(byte[] bytes) {
        int crc = 0x0000;
        int polynomial = 0x1021;

        for (byte b : bytes) {
            for (int i = 0; i < 8; i++) {
                boolean bit = ((b >> (7 - i) & 1) == 1);
                boolean c15 = ((crc >> 15 & 1) == 1);
                crc <<= 1;
                if (c15 ^ bit) {
                    crc ^= polynomial;
                }
            }
        }

        return crc & 0xffff;
    }

    /**
     * 生成Hash Tag键
     * 确保相关数据（如短链信息、访问计数、URL映射）在同一分片
     */
    public String generateHashTagKey(String prefix, String shortCode) {
        return prefix + "{" + shortCode + "}";
    }

    /**
     * 计算分片分布统计
     */
    public ShardDistribution calculateShardDistribution(java.util.List<String> keys) {
        java.util.Map<Integer, Integer> distribution = new java.util.HashMap<>();
        
        for (String key : keys) {
            int slot = calculateSlot(key);
            distribution.merge(slot, 1, Integer::sum);
        }
        
        return new ShardDistribution(distribution);
    }

    /**
     * 分片分布统计结果
     */
    public static class ShardDistribution {
        private final java.util.Map<Integer, Integer> distribution;
        
        public ShardDistribution(java.util.Map<Integer, Integer> distribution) {
            this.distribution = distribution;
        }
        
        public java.util.Map<Integer, Integer> getDistribution() {
            return distribution;
        }
        
        public int getTotalKeys() {
            return distribution.values().stream().mapToInt(Integer::intValue).sum();
        }
        
        public double getAverageKeysPerShard() {
            return distribution.isEmpty() ? 0 : (double) getTotalKeys() / distribution.size();
        }
        
        public int getMaxKeysInShard() {
            return distribution.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        }
        
        public int getMinKeysInShard() {
            return distribution.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        }
    }
}