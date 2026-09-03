package org.example.short_link.controller;


import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.example.short_link.dto.ApiResponse;
import org.example.short_link.dto.CreateShortUrlRequest;
import org.example.short_link.dto.CreateShortUrlResponse;
import org.example.short_link.service.ShortUrlService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Objects;

@AllArgsConstructor
@RequestMapping("/shortUrl")
@RestController
public class ShortUrlController {

    @Resource
    private final ShortUrlService shortUrlService;

    /**
     * 创建短链
     */
    @PostMapping("/api/short-url")
    public ApiResponse<CreateShortUrlResponse> createShortUrl(
            @Valid @RequestBody CreateShortUrlRequest request) {
        try {
            CreateShortUrlResponse response = shortUrlService.createShortUrl(request);
            return ApiResponse.success("短链创建成功", response);
        } catch (RuntimeException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
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