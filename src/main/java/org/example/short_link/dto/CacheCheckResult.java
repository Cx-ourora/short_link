package org.example.short_link.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CacheCheckResult {

    // 创建短链响应体
    private CreateShortUrlResponse createShortUrlResponse;

    // 匹配的哈希值
    private String currentHash;


}
