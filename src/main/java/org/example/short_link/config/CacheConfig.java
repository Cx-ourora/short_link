package org.example.short_link.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.stream.Collectors;

@Configuration
@EnableCaching
public class CacheConfig {
    /**
     * 专门用于shortCode的Key生成器
     */
    @Bean("shortCodeKeyGenerator")
    public KeyGenerator shortCodeKeyGenerator() {
        return (target, method, params) -> {
            if (params == null || params.length == 0) {
                throw new IllegalArgumentException("shortCode参数不能为空");
            }

            Object shortCodeParam = params[0];
            if (shortCodeParam == null) {
                throw new IllegalArgumentException("shortCode不能为null");
            }

            String shortCode = shortCodeParam.toString();
            if (!StringUtils.hasText(shortCode)) {
                throw new IllegalArgumentException("shortCode不能为空字符串");
            }

            // 添加前缀避免key冲突
            return "shortUrl:" + shortCode;
        };
    }
}