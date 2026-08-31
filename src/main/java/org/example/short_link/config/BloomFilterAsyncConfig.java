package org.example.short_link.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 布隆过滤器线程池配置
 */
@Configuration
public class BloomFilterAsyncConfig {

    @Value("${shortlink.bloom.threadpool.corePoolSize:4}")
    private int corePoolSize;
    @Value("${shortlink.bloom.threadpool.maxPoolSize:8}")
    private int maxPoolSize;
    @Value("${shortlink.bloom.threadpool.queueCapacity:1000}")
    private int queueCapacity;
    @Value("${shortlink.bloom.threadpool.keepAliveSeconds:60}")
    private int keepAliveSeconds;
    @Value("${shortlink.bloom.threadpool.threadNamePrefix:bloom-filter-}")
    private String threadNamePrefix;
    @Value("${shortlink.bloom.threadpool.awaitTerminationSeconds:30}")
    private int awaitTerminationSeconds;

    @Bean("bloomFilterExecutor")
    public Executor bloomFilterExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }
}