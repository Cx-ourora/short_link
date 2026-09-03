package org.example.short_link.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 创建短链响应体
 */
@NoArgsConstructor
@AllArgsConstructor
@Data
public class CreateShortUrlResponse {
    // 短链码
    private String shortCode;
    // 完整短链接地址
    private String shortUrl;
    // 原始长链接
    private String originUrl;
    // 创建时间
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;
    // 过期时间
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime expireTime;
    // 过期天数
    private Integer expireDays;
    // 访问次数
    private Long accessCount = 0L;
}