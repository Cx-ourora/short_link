package org.example.short_link.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 布隆过滤器 Stream同步服务
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class BloomFilterStreamService implements ApplicationContextAware {

    private final RedisTemplate<String, Object> redisTemplate;
    private final LocalBloomFilterService localBloomFilter;

    private ApplicationContext applicationContext;

    @Value("${server.port:8888}")
    private String serverPort;

    private static final String STREAM_KEY = "bloom_filter_stream";
    private static final String CONSUMER_GROUP_BASE = "bloom_sync_group";

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
            consumerName = "consumer-" + nodeId;
            groupName = CONSUMER_GROUP_BASE + ":" + nodeId;

            // 创建消费者组（如果不存在）
            try {
                redisTemplate.opsForStream().createGroup(STREAM_KEY, groupName);
                log.info("创建Redis Stream消费者组: {}", groupName);
            } catch (Exception e) {
                log.info("消费者组已存在: {}", groupName);
            }


            log.info("BloomFilterStreamService初始化完成");

        } catch (Exception e) {
            log.error("初始化BloomFilterStreamService失败", e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("应用启动完成，开始启动布隆过滤器Stream消费者");
        // 通过ApplicationContext获取代理
        BloomFilterStreamService proxy = applicationContext.getBean(BloomFilterStreamService.class);
        proxy.startConsumer();
    }

    @Async("bloomFilterExecutor")
    public void startConsumer() {
        log.info("消费者线程启动 - 线程名: {}, 线程ID: {}",
                Thread.currentThread().getName(),
                Thread.currentThread().getId());
        running.set(true);
        log.info("启动布隆过滤器Stream消费者: {}", consumerName);

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
                log.error("消费布隆过滤器Stream消息失败", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("布隆过滤器Stream消费者已停止: {}", consumerName);
    }

    private void processRecords(List<MapRecord<String, Object, Object>> records) {
        for (MapRecord<String, Object, Object> record : records) {
            try {
                String shortCode = (String) record.getValue().get("shortCode");
                String sourceNode = (String) record.getValue().get("sourceNode");
                String action = (String) record.getValue().get("action");
                Long timestamp = (Long) record.getValue().get("timestamp");

                // 不处理自己发送的消息
                if (!nodeId.equals(sourceNode)) {
                    if ("ADD".equals(action)) {
                        localBloomFilter.addLocal(shortCode);
                        log.debug("从Stream同步短链到本地布隆过滤器: {} (来源: {})", shortCode, sourceNode);
                    }
                }

                // 确认消息处理完成
                redisTemplate.opsForStream().acknowledge(STREAM_KEY, groupName, record.getId());

            } catch (Exception e) {
                log.error("处理Stream记录失败: {}", record, e);
            }
        }
    }

    /**
     * 发布新增短链到Stream
     */
    public void publishNewShortCode(String shortCode) {
        try {
            Map<String, Object> message = Map.of(
                    "shortCode", shortCode,
                    "sourceNode", nodeId,
                    "action", "ADD",
                    "timestamp", System.currentTimeMillis()
            );

            RecordId recordId = redisTemplate.opsForStream().add(STREAM_KEY, message);
            log.debug("发布短链到Stream: {} (recordId: {})", shortCode, recordId);

        } catch (Exception e) {
            log.error("发布短链到Stream失败: shortCode={}", shortCode, e);
        }
    }

    /**
     * 批量发布短链
     */
    public void publishBatchShortCodes(List<String> shortCodes) {
        try {
            for (String shortCode : shortCodes) {
                publishNewShortCode(shortCode);
            }
            log.info("批量发布{}个短链到Stream", shortCodes.size());

        } catch (Exception e) {
            log.error("批量发布短链到Stream失败", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        log.info("正在关闭布隆过滤器Stream服务...");
    }

    public String getNodeId() {
        return nodeId;
    }
}
