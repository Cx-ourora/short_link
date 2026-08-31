package org.example.short_link.generator;

import lombok.extern.slf4j.Slf4j;
import org.example.short_link.config.ShortCodeConfig;
import org.example.short_link.service.ClockSyncMonitorService;
import org.example.short_link.service.MachineIdService;
import org.example.short_link.util.Base62Util;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 分布式短码生成器
 * 支持动态配置短链长度
 * 增强的时钟回拨处理
 */
@Slf4j
@Component
public class ShortCodeGenerator {

    // 时间戳位数
    private static final long TIMESTAMP_BITS = 41L;
    // 机器ID位数
    private static final long MACHINE_ID_BITS = 10L;
    // 序列号位数
    private static final long SEQUENCE_BITS = 12L;

    // 最大值
    private static final long MAX_MACHINE_ID = (1L << MACHINE_ID_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    // 位移量
    private static final long MACHINE_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + MACHINE_ID_BITS;

    // 起始时间戳 (2024-01-01 00:00:00 UTC)
    private static final long START_TIMESTAMP = 1704067200000L;
    
    // 时钟回拨阈值
    private static final long CLOCK_BACKWARDS_SMALL_THRESHOLD = 5L; // 小幅回拨阈值(ms)
    private static final long CLOCK_BACKWARDS_MEDIUM_THRESHOLD = 50L; // 中等回拨阈值(ms)

    // 机器ID分配服务
    private final MachineIdService machineIdService;
    // 时钟同步监控服务
    private final ClockSyncMonitorService clockSyncMonitorService;
    // 短链生成配置
    private final ShortCodeConfig shortCodeConfig;
    
    private final AtomicLong sequence = new AtomicLong(0L);
    private volatile long lastTimestamp = -1L;
    private final ReentrantLock lock = new ReentrantLock();

    // 缓存最大值，避免重复计算
    private volatile long cachedMaxValue = -1L;
    private volatile int cachedLength = -1;
    
    // 时钟回拨统计
    private volatile int smallBackwardsCount = 0;
    private volatile int mediumBackwardsCount = 0;
    private volatile int severeBackwardsCount = 0;
    private volatile long lastBackwardsTime = 0L;

    /**
     * 构造函数注入配置和服务
     */
    public ShortCodeGenerator(ShortCodeConfig shortCodeConfig,
                              MachineIdService machineIdService,
                              ClockSyncMonitorService clockSyncMonitorService) {
        this.shortCodeConfig = shortCodeConfig;
        this.machineIdService = machineIdService;
        this.clockSyncMonitorService = clockSyncMonitorService;

        // 验证机器ID范围
        long machineId = machineIdService.getMachineId();
        if (machineId < 0 || machineId > MAX_MACHINE_ID) {
            throw new IllegalArgumentException(
                    String.format("机器ID必须在0-%d之间，当前值: %d", MAX_MACHINE_ID, machineId));
        }

        log.info("短码生成器初始化完成 - 机器ID: {}, 配置长度: {}",
                machineId, shortCodeConfig.getLength());
    }

    /**
     * 生成唯一ID (增强的时钟回拨处理)
     */
    public long generateId() {
        lock.lock();
        try {
            long timestamp = getCurrentTimestamp();

            // 增强的时钟回拨检查
            if (timestamp < lastTimestamp) {
                long offset = lastTimestamp - timestamp;
                
                // 记录时钟回拨事件
                recordClockBackwards(offset);
                
                if (offset <= CLOCK_BACKWARDS_SMALL_THRESHOLD) {
                    // 小幅回拨，等待追上
                    try {
                        Thread.sleep(offset << 1);
                        timestamp = getCurrentTimestamp();
                        if (timestamp < lastTimestamp) {
                            // 仍然回拨，使用上次时间戳
                            log.warn("等待后仍检测到时钟回拨({}ms)，使用上次时间戳", offset);
                            timestamp = lastTimestamp;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("等待时钟同步被中断", e);
                    }
                } else if (offset <= CLOCK_BACKWARDS_MEDIUM_THRESHOLD) {
                    // 中等回拨，使用上次时间戳
                    log.warn("检测到中等时钟回拨({}ms)，使用上次时间戳", offset);
                    timestamp = lastTimestamp;
                } else {
                    // 大幅回拨，使用备用时间源
                    log.error("检测到严重时钟回拨({}ms)，启用备用时间源", offset);
                    timestamp = getBackupTimestamp();
                }
            }

            // 同一毫秒内
            if (timestamp == lastTimestamp) {
                long seq = sequence.incrementAndGet() & MAX_SEQUENCE;
                if (seq == 0) {
                    // 序列号用完，等待下一毫秒
                    timestamp = waitNextMillis(timestamp);
                    sequence.set(0L);
                }
            } else {
                // 新的毫秒，重置序列号
                sequence.set(0L);
            }

            lastTimestamp = timestamp;

            // 组装ID
            long id = ((timestamp - START_TIMESTAMP) << TIMESTAMP_SHIFT)
                    | (machineIdService.getMachineId() << MACHINE_ID_SHIFT)
                    | sequence.get();

            // 确保ID不超过指定长度Base62编码的最大值
            long maxValue = getMaxValueForCurrentLength();
            return Math.abs(id) % maxValue;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 记录时钟回拨事件
     */
    private void recordClockBackwards(long offset) {
        long now = System.currentTimeMillis();
        
        // 更新统计
        if (offset <= CLOCK_BACKWARDS_SMALL_THRESHOLD) {
            smallBackwardsCount++;
        } else if (offset <= CLOCK_BACKWARDS_MEDIUM_THRESHOLD) {
            mediumBackwardsCount++;
        } else {
            severeBackwardsCount++;
        }
        
        // 避免日志过多，限制记录频率
        if (now - lastBackwardsTime > 60000) { // 每分钟最多记录一次详细日志
            log.warn("时钟回拨统计 - 小幅: {}, 中等: {}, 严重: {}",
                    smallBackwardsCount, mediumBackwardsCount, severeBackwardsCount);
            lastBackwardsTime = now;
        }
    }
    
    /**
     * 获取备用时间戳（使用时钟同步服务）
     */
    private long getBackupTimestamp() {
        // 使用时钟同步服务获取参考时间
        long referenceTime = clockSyncMonitorService.getReferenceTime();
        
        // 确保时间不会倒退
        return Math.max(referenceTime, lastTimestamp + 1);
    }

    /**
     * 生成短码 - 支持动态长度配置
     */
    public String generateShortCode() {
        long id = generateId();
        int targetLength = shortCodeConfig.getLength();

        // 使用配置的长度生成短码
        String shortCode = Base62Util.encodeWithMinLength(id, targetLength);

        // 确保不超过配置的长度
        if (shortCode.length() > targetLength) {
            shortCode = shortCode.substring(0, targetLength);
            log.debug("短码长度超限，已截取到{}位: {}", targetLength, shortCode);
        }

        return shortCode;
    }

    /**
     * 批量生成短码
     */
    public String[] generateBatchShortCodes(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("批量生成数量必须大于0");
        }

        if (count > 10000) {
            log.warn("批量生成数量较大: {}, 建议分批处理", count);
        }

        return java.util.stream.IntStream.range(0, count)
                .parallel()
                .mapToObj(i -> generateShortCode())
                .toArray(String[]::new);
    }

    /**
     * 获取当前配置长度对应的最大值（带缓存优化）
     */
    private long getMaxValueForCurrentLength() {
        int currentLength = shortCodeConfig.getLength();

        // 如果长度变化了，重新计算最大值
        if (currentLength != cachedLength) {
            synchronized (this) {
                if (currentLength != cachedLength) {
                    cachedMaxValue = Base62Util.getMaxValue(currentLength);
                    cachedLength = currentLength;
                    log.debug("更新缓存的最大值: length={}, maxValue={}",
                            currentLength, cachedMaxValue);
                }
            }
        }

        return cachedMaxValue;
    }

    /**
     * 获取当前时间戳
     */
    private long getCurrentTimestamp() {
        return Instant.now().toEpochMilli();
    }

    /**
     * 等待下一毫秒
     */
    private long waitNextMillis(long lastTimestamp) {
        long timestamp = getCurrentTimestamp();
        while (timestamp <= lastTimestamp) {
            Thread.onSpinWait(); // JDK21优化的自旋等待
            timestamp = getCurrentTimestamp();
        }
        return timestamp;
    }

    /**
     * 获取生成器状态信息（用于监控）
     */
    public GeneratorStatus getStatus() {
        return new GeneratorStatus(
                machineIdService.getMachineId(),
                shortCodeConfig.getLength(),
                lastTimestamp,
                sequence.get(),
                getMaxValueForCurrentLength(),
                smallBackwardsCount,
                mediumBackwardsCount,
                severeBackwardsCount
        );
    }

    /**
     * 生成器状态信息
     */
    public static class GeneratorStatus {
        private final long machineId;
        private final int configuredLength;
        private final long lastTimestamp;
        private final long currentSequence;
        private final long maxValue;
        private final int smallBackwardsCount;
        private final int mediumBackwardsCount;
        private final int severeBackwardsCount;

        public GeneratorStatus(long machineId, int configuredLength,
                               long lastTimestamp, long currentSequence, long maxValue,
                               int smallBackwardsCount, int mediumBackwardsCount, int severeBackwardsCount) {
            this.machineId = machineId;
            this.configuredLength = configuredLength;
            this.lastTimestamp = lastTimestamp;
            this.currentSequence = currentSequence;
            this.maxValue = maxValue;
            this.smallBackwardsCount = smallBackwardsCount;
            this.mediumBackwardsCount = mediumBackwardsCount;
            this.severeBackwardsCount = severeBackwardsCount;
        }

        // Getters
        public long getMachineId() { return machineId; }
        public int getConfiguredLength() { return configuredLength; }
        public long getLastTimestamp() { return lastTimestamp; }
        public long getCurrentSequence() { return currentSequence; }
        public long getMaxValue() { return maxValue; }
        public int getSmallBackwardsCount() { return smallBackwardsCount; }
        public int getMediumBackwardsCount() { return mediumBackwardsCount; }
        public int getSevereBackwardsCount() { return severeBackwardsCount; }

        @Override
        public String toString() {
            return String.format(
                    "GeneratorStatus{machineId=%d, length=%d, lastTimestamp=%d, sequence=%d, maxValue=%d, 时钟回拨统计[小=%d,中=%d,大=%d]}",
                    machineId, configuredLength, lastTimestamp, currentSequence, maxValue,
                    smallBackwardsCount, mediumBackwardsCount, severeBackwardsCount
            );
        }
    }
}