package org.example.short_link.controller;


import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.example.short_link.dto.ApiResponse;
import org.example.short_link.dto.CreateShortUrlRequest;
import org.example.short_link.dto.CreateShortUrlResponse;
import org.example.short_link.service.ShortUrlService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

@RequestMapping("/shortUrl")
@RestController
public class ShortUrlController {

    @Resource
    private ShortUrlService shortUrlService;

    /**
     * 业务指标 Counter / Timer（由 CustomPrometheusConfig 注册的 Bean）
     * 多个同类型 Bean 必须用 @Qualifier 指定名称，否则 Spring 无法按类型注入
     */
    @Resource
    @Qualifier("shortLinkCreateCounter")
    private Counter shortLinkCreateCounter;

    @Resource
    @Qualifier("shortLinkAccessCounter")
    private Counter shortLinkAccessCounter;

    @Resource
    @Qualifier("shortLinkCreateTimer")
    private Timer shortLinkCreateTimer;

    /**
     * 创建短链
     */
    @PostMapping("/api/short-url")
    public ApiResponse<CreateShortUrlResponse> createShortUrl(
            @Valid @RequestBody CreateShortUrlRequest request) {
        long startNs = System.nanoTime();
        try {
            CreateShortUrlResponse response = shortUrlService.createShortUrl(request);
            // 打点：创建成功 +1，并记录耗时
            shortLinkCreateCounter.increment();
            shortLinkCreateTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
            return ApiResponse.success("短链创建成功", response);
        } catch (RuntimeException e) {
            shortLinkCreateTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            shortLinkCreateTimer.record(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
            return ApiResponse.internalError("系统内部错误");
        }
    }

    /**
     * 短链跳转
     */
    @GetMapping("/{shortCode}")
    public void redirect(@PathVariable("shortCode") String shortCode, HttpServletResponse response)
            throws IOException {
        try {
            CreateShortUrlResponse shortUrlInfo = shortUrlService.getShortUrlInfo(shortCode);

            // 打点：每次短链被访问（无论命中与否）都 +1
            shortLinkAccessCounter.increment();

            if (Objects.isNull(shortUrlInfo)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "短链不存在或已过期");
                return;
            }

            // 302重定向，优化缓存策略
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", shortUrlInfo.getOriginUrl());
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "0");
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "系统内部错误");
        }
    }
}