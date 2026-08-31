package org.example.short_link.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.short_link.config.ShortCodeConfig;
import org.example.short_link.generator.ShortCodeGenerator;
import org.example.short_link.util.Base62Util;
import org.example.short_link.util.DigestUtils;
import org.springframework.stereotype.Service;

/**
 * 短链编码服务
 * 支持多种生成策略：分布式ID、URL哈希、随机生成
 */
@Slf4j
@AllArgsConstructor
@Service
public class ShortCodeService {
    // 分布式短码生成器服务
    private final ShortCodeGenerator shortCodeGenerator;
    // 短码生成配置
    private final ShortCodeConfig shortCodeConfig;

    /**
     * 根据配置策略生成短链
     */
    public String generateByStrategy(String url) {
        return shortCodeConfig.getStrategy().generate(url, this);
    }

    /**
     * 生成唯一短链编码（推荐使用 - 基于分布式ID）
     * 优势：全局唯一、高性能、时间有序
     */
    public String generateUniqueCode() {
        long id = shortCodeGenerator.generateId();
        String shortCode = Base62Util.encodeWithMinLength(id, shortCodeConfig.getLength());

        // 确保不超过8位长度
        if (shortCode.length() > shortCodeConfig.getLength()) {
            shortCode = shortCode.substring(0, shortCodeConfig.getLength());
        }

        return shortCode;
    }

    /**
     * 基于URL哈希生成短链编码（确定性生成）
     * 优势：相同URL生成相同短链，缓存友好
     */
    public String generateByUrlHashDeterministic(String url) {
        // 不添加时间戳，保证相同URL生成相同短链
        String hash = DigestUtils.md5(url);
        return convertHashToBase62(hash);
    }

    /**
     * 基于URL哈希生成短链编码（带随机性）
     * 用于需要避免相同URL生成相同短链的场景
     */
    public String generateByUrlHashWithRandomness(String url) {
        String hash = DigestUtils.md5(url + System.currentTimeMillis());
        return convertHashToBase62(hash);
    }

    /**
     * 将哈希值转换为Base62 - 确保固定8位长度
     */
    private String convertHashToBase62(String hash) {
        // 取哈希值前16位作为数字，增加随机性
        String hashPrefix = hash.substring(0, Math.min(16, hash.length()));
        long num = Long.parseUnsignedLong(hashPrefix, 16);

        // 确保不超过8位Base62编码的最大值
        long maxValue = Base62Util.getMaxValue(shortCodeConfig.getLength());
        num = num % maxValue;

        String shortCode = Base62Util.encodeWithMinLength(num, shortCodeConfig.getLength());

        // 双重保险：如果仍然超过8位，截取前8位
        if (shortCode.length() > shortCodeConfig.getLength()) {
            shortCode = shortCode.substring(0, shortCodeConfig.getLength());
        }

        return shortCode;
    }
}