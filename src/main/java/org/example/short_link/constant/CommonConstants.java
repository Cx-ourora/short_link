package org.example.short_link.constant;

/**
 * 通用常量
 */
public class CommonConstants {
    /**
     * 原分库数量
     */
    public static final int SHARDING_DATABASE_COUNT = 16;

    /**
     * 原分表数量
     */
    public static final int SHARDING_TABLE_COUNT = 64;

    /**
     * 新分库数量（扩容后）
     */
    public static final int NEW_SHARDING_DATABASE_COUNT = 32;

    /**
     * 新分表数量（扩容后）
     */
    public static final int NEW_SHARDING_TABLE_COUNT = 64;
    
    /**
     * 短链编码长度（更新为10位）
     */
    public static final int SHORT_CODE_LENGTH = 10;
}