package org.example.short_link.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建短链请求体
 */
@Data
public class CreateShortUrlRequest {

    @NotBlank(message = "原始URL不能为空")
    @Size(max = 2048, message = "URL长度不能超过2048个字符")
    private String originUrl;

    @Min(value = 1, message = "过期天数最少为1天")
    @Max(value = 7, message = "过期天数最多为7天")
    private Integer expireDays;

    private String creator;

    private String customCode;
}