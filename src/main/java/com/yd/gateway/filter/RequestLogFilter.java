package com.yd.gateway.filter;


import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;


@Slf4j
@Component
public class RequestLogFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // 记录请求开始时间
        long startTime = System.currentTimeMillis();
        // 记录详细请求信息
        log.info("=== 网关请求开始 ===");
        log.info("请求路径: {}", request.getPath());
        log.info("请求方法: {}", request.getMethod());
        log.info("请求IP: {}", request.getRemoteAddress());
        log.info("请求头: {}", request.getHeaders());
        log.info("请求参数: {}", request.getQueryParams());
        log.info("请求时间: {}", startTime);

        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;

            log.info("请求路径: {}, 方法: {}, 耗时: {}ms",
                    request.getPath(),
                    request.getMethod(),
                    duration);
        }));
    }

    @Override
    public int getOrder() {
        return -1000;
    }
}