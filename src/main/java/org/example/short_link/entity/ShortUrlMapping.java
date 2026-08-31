package org.example.short_link.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

import static org.example.short_link.constant.CommonConstants.SHORT_CODE_LENGTH;


/**
 * 短链实体类
 */
@Data
@Entity
@Table(name = "short_url_mapping", indexes = {
        @Index(name = "idx_short_code", columnList = "shortCode", unique = true),
        @Index(name = "idx_create_time", columnList = "createTime")
})
public class ShortUrlMapping {

    @Id
    @Column(name = "short_code", length = SHORT_CODE_LENGTH)
    @NotBlank(message = "短链编码不能为空")
    @Size(max = SHORT_CODE_LENGTH, message = "短链编码长度不能超过" + SHORT_CODE_LENGTH)
    private String shortCode;

    @Column(name = "origin_url", columnDefinition = "VARCHAR", nullable = false)
    @NotBlank(message = "原始URL不能为空")
    @Size(max = 2048, message = "原始URL长度不能超过2048")
    private String originUrl;

    @Column(name = "origin_url_hash", columnDefinition = "VARCHAR", nullable = false)
    @NotBlank(message = "原始URL哈希值不能为空")
    private String originUrlHash;

    @Column(name = "create_time", nullable = false, updatable = false)
    @CreationTimestamp
    private LocalDateTime createTime;

    @Column(name = "update_time")
    @UpdateTimestamp
    private LocalDateTime updateTime;

    @Column(name = "expire_days", nullable = false)
    private Integer expireDays = 7;

    @Column(name = "access_count", nullable = false)
    private Long accessCount = 0L;

    @Column(name = "status", nullable = false)
    private Integer status = 1; // 1:正常 0:禁用

    @Column(name = "creator", nullable = true)
    @Size(max = 30, message = "创建人长度不能超过30字符")
    private String creator;

    /**
     * 是否过期
     *
     * @return 是否过期
     */
    public boolean isExpired() {
        // 如果过期天数为null或小于等于0，表示永不过期
        if (expireDays == null || expireDays <= 0) {
            return false;
        }

        // 如果创建时间为null，认为已过期（异常情况）
        if (createTime == null) {
            return true;
        }

        // 计算过期时间：创建时间 + 过期天数
        LocalDateTime expireTime = createTime.plusDays(expireDays);

        // 判断当前时间是否超过过期时间
        return LocalDateTime.now().isAfter(expireTime);
    }
}