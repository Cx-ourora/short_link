package org.example.short_link.config;


import lombok.Data;
import org.example.short_link.service.ShortCodeService;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 短链生成策略配置类
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "shortlink.code")
public class ShortCodeConfig {

    /**
     * 短链生成策略
     * DISTRIBUTED_ID: 基于分布式ID
     * URL_HASH_DETERMINISTIC: 基于URL哈希
     * URL_HASH_RANDOM: 基于URL哈希
     */
    private GenerationStrategy strategy = GenerationStrategy.DISTRIBUTED_ID;

    /**
     * 短链长度
     */
    private int length = 8;

    /**
     * 最大重试次数
     */
    private int maxRetries = 3;


    public enum GenerationStrategy {
        DISTRIBUTED_ID {
            @Override
            public String generate(String url, ShortCodeService service) {
                return service.generateUniqueCode();
            }
        },
        URL_HASH_DETERMINISTIC {
            @Override
            public String generate(String url, ShortCodeService service) {
                return service.generateByUrlHashDeterministic(url);
            }
        },
        URL_HASH_RANDOM {
            @Override
            public String generate(String url, ShortCodeService service) {
                return service.generateByUrlHashWithRandomness(url);
            }
        };

        public abstract String generate(String url, ShortCodeService service);
    }
}