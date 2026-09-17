package org.example.short_link.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Prometheus配置
 */
@Configuration
public class CustomPrometheusConfig {

    /**
     * 统一添加固定标签
     * @return
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> {
            registry.config()
                    .commonTags("application", "short-link")
                    .commonTags("version", "1.0.0")
                    .commonTags("environment", "local");
        };
    }

    // -------------- 自定义业务指标 ---------------------------------
    @Bean
    public Counter shortLinkCreateCounter(MeterRegistry meterRegistry) {
        return Counter.builder("shortlink_create_total")
                .description("短链创建总数")
                .register(meterRegistry);
    }

    @Bean
    public Counter shortLinkAccessCounter(MeterRegistry meterRegistry) {
        return Counter.builder("shortlink_access_total")
                .description("短链访问总数")
                .register(meterRegistry);
    }

    @Bean
    public Timer shortLinkCreateTimer(MeterRegistry meterRegistry) {
        return Timer.builder("shortlink_create_duration")
                .description("短链创建耗时")
                .register(meterRegistry);
    }
}