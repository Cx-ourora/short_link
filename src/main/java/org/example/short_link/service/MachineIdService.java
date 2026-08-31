package org.example.short_link.service;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 机器ID分配服务
 * 为分布式环境中的每个节点自动分配唯一的机器ID
 */
@Slf4j
@Service
public class MachineIdService implements InitializingBean {

    // redisson服务
    private final RedissonClient redissonClient;
    // 分布式锁服务
    private final DistributedLockService lockService;
    
    private static final String MACHINE_ID_KEY = "shortlink:machine-id-registry";
    private static final String MACHINE_ID_LOCK = "shortlink:machine-id-lock";
    private static final long MAX_MACHINE_ID = 1023; // 10位机器ID的最大值
    
    private volatile long machineId = -1;
    private String nodeIdentifier;
    
    public MachineIdService(RedissonClient redissonClient, DistributedLockService lockService) {
        this.redissonClient = redissonClient;
        this.lockService = lockService;
    }
    
    @Override
    public void afterPropertiesSet() {
        try {
            // 获取本机信息作为唯一标识
            nodeIdentifier = getNodeIdentifier();
            
            // 尝试分配机器ID
            assignMachineId();
            
            // 启动心跳任务，保持机器ID活跃
            startHeartbeat();
            
            log.info("机器ID服务初始化完成 - 节点: {}, 机器ID: {}", nodeIdentifier, machineId);
        } catch (Exception e) {
            log.error("机器ID服务初始化失败", e);
            throw new RuntimeException("无法初始化机器ID服务", e);
        }
    }
    
    /**
     * 分配机器ID
     */
    private void assignMachineId() {
        RMap<String, Long> machineIdMap = redissonClient.getMap(MACHINE_ID_KEY);
        
        // 检查是否已有分配的ID
        if (machineIdMap.containsKey(nodeIdentifier)) {
            machineId = machineIdMap.get(nodeIdentifier);
            log.info("复用已分配的机器ID: {}", machineId);
            return;
        }
        
        // 分配新ID
        machineId = lockService.executeWithLock(MACHINE_ID_LOCK, 10, 30, TimeUnit.SECONDS, () -> {
            // 再次检查（双重检查）
            if (machineIdMap.containsKey(nodeIdentifier)) {
                return machineIdMap.get(nodeIdentifier);
            }
            
            // 找到未使用的最小ID
            Set<Long> usedIds = new HashSet<>(machineIdMap.values());
            for (long i = 0; i <= MAX_MACHINE_ID; i++) {
                if (!usedIds.contains(i)) {
                    machineIdMap.put(nodeIdentifier, i);
                    log.info("自动分配机器ID: {}", i);
                    return i;
                }
            }
            
            throw new RuntimeException("无可用机器ID，已达到最大限制: " + MAX_MACHINE_ID);
        });
    }
    
    /**
     * 获取节点唯一标识
     */
    private String getNodeIdentifier() {
        try {
            String hostname = InetAddress.getLocalHost().getHostName();
            String ip = InetAddress.getLocalHost().getHostAddress();
            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            return String.format("%s-%s-%s", hostname, ip, pid);
        } catch (Exception e) {
            // 备用方案
            String randomId = String.valueOf(System.currentTimeMillis() % 100000);
            log.warn("无法获取节点信息，使用备用标识: node-{}", randomId);
            return "node-" + randomId;
        }
    }
    
    /**
     * 启动心跳任务，定期更新机器ID注册表
     */
    private void startHeartbeat() {
        Thread heartbeatThread = new Thread(() -> {
            RMap<String, Long> machineIdMap = redissonClient.getMap(MACHINE_ID_KEY);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 更新心跳时间
                    machineIdMap.put(nodeIdentifier, machineId);
                    
                    // 每30秒心跳一次
                    Thread.sleep(30000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("机器ID心跳线程被中断");
                    break;
                } catch (Exception e) {
                    log.error("机器ID心跳更新失败", e);
                    try {
                        Thread.sleep(5000); // 失败后短暂等待
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        
        heartbeatThread.setName("machine-id-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
        
        // 添加关闭钩子，释放机器ID
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                RMap<String, Long> machineIdMap = redissonClient.getMap(MACHINE_ID_KEY);
                machineIdMap.remove(nodeIdentifier);
                log.info("应用关闭，释放机器ID: {}", machineId);
            } catch (Exception e) {
                log.error("释放机器ID失败", e);
            }
        }));
    }
    
    /**
     * 获取当前节点的机器ID
     */
    public long getMachineId() {
        if (machineId < 0) {
            throw new IllegalStateException("机器ID尚未初始化");
        }
        return machineId;
    }
}