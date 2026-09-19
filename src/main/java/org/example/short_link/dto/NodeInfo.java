package org.example.short_link.dto;

import lombok.Data;

@Data
public class NodeInfo {
    // Getter方法
    private String nodeId;
    private long timestamp;
    private boolean localServiceActive;
    private boolean redisServiceActive;
    private boolean streamServiceActive;
    private String localStats;
    private String redisStats;
    private long memoryUsedMB;
    private long memoryTotalMB;
    private long memoryMaxMB;
    private double memoryUsagePercent;

    // 私有构造函数
    private NodeInfo() {}

    // Builder模式
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private NodeInfo nodeInfo = new NodeInfo();

        public Builder nodeId(String nodeId) {
            nodeInfo.nodeId = nodeId;
            return this;
        }

        public Builder timestamp(long timestamp) {
            nodeInfo.timestamp = timestamp;
            return this;
        }

        public Builder localServiceActive(boolean localServiceActive) {
            nodeInfo.localServiceActive = localServiceActive;
            return this;
        }

        public Builder redisServiceActive(boolean redisServiceActive) {
            nodeInfo.redisServiceActive = redisServiceActive;
            return this;
        }

        public Builder streamServiceActive(boolean streamServiceActive) {
            nodeInfo.streamServiceActive = streamServiceActive;
            return this;
        }

        public Builder localStats(String localStats) {
            nodeInfo.localStats = localStats;
            return this;
        }

        public Builder redisStats(String redisStats) {
            nodeInfo.redisStats = redisStats;
            return this;
        }

        public Builder memoryUsedMB(long memoryUsedMB) {
            nodeInfo.memoryUsedMB = memoryUsedMB;
            return this;
        }

        public Builder memoryTotalMB(long memoryTotalMB) {
            nodeInfo.memoryTotalMB = memoryTotalMB;
            return this;
        }

        public Builder memoryMaxMB(long memoryMaxMB) {
            nodeInfo.memoryMaxMB = memoryMaxMB;
            return this;
        }

        public Builder memoryUsagePercent(double memoryUsagePercent) {
            nodeInfo.memoryUsagePercent = memoryUsagePercent;
            return this;
        }

        public NodeInfo build() {
            return nodeInfo;
        }
    }

    @Override
    public String toString() {
        return String.format(
                "NodeInfo{nodeId='%s', timestamp=%d, services=[local=%s, redis=%s, stream=%s], " +
                        "memory=[used=%dMB, total=%dMB, max=%dMB, usage=%.2f%%], " +
                        "localStats='%s', redisStats='%s'}",
                nodeId, timestamp, localServiceActive, redisServiceActive, streamServiceActive,
                memoryUsedMB, memoryTotalMB, memoryMaxMB, memoryUsagePercent,
                localStats, redisStats
        );
    }
}
