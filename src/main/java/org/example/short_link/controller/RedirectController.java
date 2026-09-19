package org.example.short_link.controller;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import io.micrometer.core.instrument.Counter;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.short_link.service.ShortUrlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
public class RedirectController {
    @Resource
    @Qualifier("shortLinkAccessCounter")
    private Counter shortLinkAccessCounter;

    private static final Logger logger = LoggerFactory.getLogger(RedirectController.class);

    @Resource
    private ShortUrlService shortUrlService;

    /**
     * 短链跳转
     */
    @GetMapping("/{shortCode}")
    @SentinelResource(
            value = "redirectShortUrl",
            blockHandler = "redirectBlockHandler",
            fallback = "redirectFallback"
    )
    public void redirect(@PathVariable("shortCode") String shortCode,
                         HttpServletResponse response) throws IOException {

        try {
            // 获取短链信息
            var shortUrlInfo = shortUrlService.getShortUrlInfo(shortCode);

            if (shortUrlInfo == null) {
                response.setStatus(HttpStatus.NOT_FOUND.value());
                response.getWriter().write("短链不存在或已过期");
                return;
            }

            // 异步更新访问次数
            try {
                shortUrlService.updateAccessCountAsync(shortCode);
            } catch (Exception e) {
                logger.warn("更新访问次数失败: {}", e.getMessage());
            }

            // 打点：每次短链被访问（无论命中与否）都 +1
            shortLinkAccessCounter.increment();
            // 执行重定向
            response.setStatus(HttpStatus.MOVED_PERMANENTLY.value());
            response.setHeader("Location", shortUrlInfo.getOriginUrl());
            response.setHeader("Cache-Control", "public, max-age=3600");

        } catch (Exception e) {
            logger.error("短链跳转异常: {}", e.getMessage(), e);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.getWriter().write("系统内部错误");
        }
    }

    // Sentinel 限流处理
    public void redirectBlockHandler(String shortCode,
                                     HttpServletRequest request,
                                     HttpServletResponse response,
                                     BlockException ex) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.getWriter().write("访问过于频繁，请稍后重试");
    }

    // Sentinel 降级处理
    public void redirectFallback(String shortCode,
                                 HttpServletRequest request,
                                 HttpServletResponse response,
                                 Throwable ex) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.getWriter().write("服务暂时不可用，请稍后重试");
    }
}