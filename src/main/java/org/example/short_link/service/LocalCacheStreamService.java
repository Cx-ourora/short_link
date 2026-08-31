package org.example.short_link.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.short_link.entity.ShortUrlMapping;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本地缓存Redis Stream同步服务
 * 解决多节点本地缓存数据不一致问题
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class LocalCacheStreamService implements ApplicationContextAware {

    private final RedisTemplate<String, Object> redisTemplate;
    private ApplicationContext applicationContext;

    @Value("${server.port:8888}")
    private String serverPort;

    // Stream配置
    private static final String STREAM_KEY = "local_cache_stream";
    private static final String CONSUMER_GROUP_BASE = "cache_sync_group";

    // 运行状态控制
    private final AtomicBoolean running = new AtomicBoolean(false);
    private String nodeId;
    private String consumerName;
    private String groupName;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        try {
            // 生成节点ID
            nodeId = InetAddress.getLocalHost().getHostAddress() + ":" + serverPort;
            consumerName = "cache-consumer-" + nodeId;
            groupName = CONSUMER_GROUP_BASE + ":" + nodeId;

            // 创建消费者组（如果不存在）
            try {
                redisTemplate.opsForStream().createGroup(STREAM_KEY, groupName);
                log.info("创建本地缓存Stream消费者组: {}", groupName);
            } catch (Exception e) {
                log.info("本地缓存消费者组已存在: {}", groupName);
            }

            log.info("LocalCacheStreamService初始化完成 - 节点: {}", nodeId);

        } catch (Exception e) {
            log.error("初始化LocalCacheStreamService失败", e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("应用启动完成，开始启动本地缓存Stream消费者");
        LocalCacheStreamService proxy = applicationContext.getBean(LocalCacheStreamService.class);
        proxy.startConsumer();
    }

    @Async("bloomFilterExecutor")
    public void startConsumer() {
        log.info("本地缓存消费者线程启动 - 线程名: {}, 节点: {}",
                Thread.currentThread().getName(), nodeId);
        running.set(true);

        while (running.get()) {
            try {
                // 读取消息
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                        .read(Consumer.from(groupName, consumerName),
                                StreamReadOptions.empty().count(10).block(Duration.ofSeconds(2)),
                                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));

                if (records != null && !records.isEmpty()) {
                    processRecords(records);
                }

            } catch (Exception e) {
                log.error("消费本地缓存Stream消息失败", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("本地缓存Stream消费者已停止: {}", consumerName);
    }

    private void processRecords(List<MapRecord<String, Object, Object>> records) {
        for (MapRecord<String, Object, Object> record : records) {
            try {
                String action = (String) record.getValue().get("action");
                String shortCode = (String) record.getValue().get("shortCode");
                String sourceNode = (String) record.getValue().get("sourceNode");
                Long timestamp = (Long) record.getValue().get("timestamp");

                // 不处理自己发送的消息
                if (!nodeId.equals(sourceNode)) {
                    switch (action) {
                        case "PUT" -> {
                            // 从Redis获取完整数据并同步到本地缓存
                            syncCacheFromRedis(shortCode, sourceNode);
                        }
                        case "EVICT" -> {
                            // 从本地缓存移除
                            LocalCacheService localCacheService = applicationContext.getBean(LocalCacheService.class);
                            localCacheService.safeEvictFromLocalCache(shortCode);
                            log.debug("从Stream同步移除本地缓存: {} (来源: {})", shortCode, sourceNode);
                        }
                        default -> log.warn("未知的缓存操作: {}", action);
                    }
                }

                // 确认消息处理完成
                redisTemplate.opsForStream().acknowledge(STREAM_KEY, groupName, record.getId());

            } catch (Exception e) {
                log.error("处理本地缓存Stream记录失败: {}", record, e);
            }
        }
    }

    /**
     * 从Redis同步数据到本地缓存
     */
    private void syncCacheFromRedis(String shortCode, String sourceNode) {
        try {
            ClusterAwareCacheService clusterCacheService =
                    applicationContext.getBean(ClusterAwareCacheService.class);

            ShortUrlMapping mapping = clusterCacheService.getFromCache(shortCode);
            if (mapping != null) {
                // 通过ApplicationContext获取LocalCacheService
                LocalCacheService localCacheService = applicationContext.getBean(LocalCacheService.class);
                localCacheService.safePutToLocalCache(shortCode, mapping);
                log.debug("从Stream同步数据到本地缓存: {} (来源: {})", shortCode, sourceNode);
            }
        } catch (Exception e) {
            log.error("从Redis同步数据到本地缓存失败: shortCode={}, sourceNode={}",
                    shortCode, sourceNode, e);
        }
    }

    /**
     * 发布缓存更新事件
     */
    public void publishCachePut(String shortCode) {
        publishCacheEvent(shortCode, "PUT");
    }

    /**
     * 发布缓存移除事件
     */
    public void publishCacheEvict(String shortCode) {
        publishCacheEvent(shortCode, "EVICT");
    }

    private void publishCacheEvent(String shortCode, String action) {
        try {
            Map<String, Object> message = Map.of(
                    "shortCode", shortCode,
                    "sourceNode", nodeId,
                    "action", action,
                    "timestamp", System.currentTimeMillis()
            );

            RecordId recordId = redisTemplate.opsForStream().add(STREAM_KEY, message);
            log.debug("发布本地缓存{}事件到Stream: {} (recordId: {})", action, shortCode, recordId);

        } catch (Exception e) {
            log.error("发布本地缓存事件到Stream失败: shortCode={}, action={}", shortCode, action, e);
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        log.info("正在关闭本地缓存Stream服务...");
    }

    public String getNodeId() {
        return nodeId;
    }
}
