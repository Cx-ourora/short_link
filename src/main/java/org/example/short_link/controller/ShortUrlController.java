package org.example.short_link.controller;


import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.example.short_link.dto.ApiResponse;
import org.example.short_link.dto.CreateShortUrlRequest;
import org.example.short_link.dto.CreateShortUrlResponse;
import org.example.short_link.service.ShortUrlService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}