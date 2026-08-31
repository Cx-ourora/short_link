package org.example.short_link.filter;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个时间片的布隆过滤器
 */
@Slf4j
public class TimeSliceBloomFilter {
    /** 当前时间片的唯一标识 */
    private final String sliceKey;
    /** Guava 布隆过滤器实例 */
    private final BloomFilter<String> bloomFilter;
    /** 该时间片的创建时间 */
    private final LocalDateTime createTime;
    /** 当前时间片内已插入的元素个数（近似值） */
    private final AtomicLong elementCount = new AtomicLong(0);
    
    /** 每个时间片预期容量（6小时 * 1万TPS * 3600秒 = 2.16亿） */
    private static final long EXPECTED_INSERTIONS = 216_000_000L;
    /** 布隆过滤器期望的误判率 */
    private static final double FALSE_PROBABILITY = 0.01;
    
    public TimeSliceBloomFilter(String sliceKey) {
        this.sliceKey = sliceKey;
        this.createTime = LocalDateTime.now();
        this.bloomFilter = BloomFilter.create(
            Funnels.stringFunnel(Charset.defaultCharset()),
            EXPECTED_INSERTIONS,
            FALSE_PROBABILITY
        );
        
        log.info("创建时间片布隆过滤器: {}, 预期容量: {}, 误判率: {}", 
            sliceKey, EXPECTED_INSERTIONS, FALSE_PROBABILITY);
    }

    /**
     * 判断给定的短码是否“可能”存在于当前时间片中。
     *
     * @param shortCode 短码字符串
     * @return true 表示可能存在（也可能不存在，取决于误判率）；
     *         false 表示一定不存在。
     */
    public boolean mightContain(String shortCode) {
        return bloomFilter.mightContain(shortCode);
    }

    /**
     * 向当前时间片的布隆过滤器中添加一个短码，
     * 并原子性地增加元素计数。
     *
     * @param shortCode 要添加的短码
     */
    public void add(String shortCode) {
        bloomFilter.put(shortCode);
        elementCount.incrementAndGet();
    }
    
    public long getApproximateElementCount() {
        return elementCount.get();
    }
    
    public String getSliceKey() {
        return sliceKey;
    }
    
    public LocalDateTime getCreateTime() {
        return createTime;
    }
    
    public double getCurrentFalseProbability() {
        return bloomFilter.expectedFpp();
    }
}