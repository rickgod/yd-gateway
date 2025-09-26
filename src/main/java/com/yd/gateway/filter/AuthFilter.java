package com.yd.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class AuthFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();

        log.info("=== 服务发现日志 ===");
        log.info("请求路径: {}", path);
        log.info("请求URI: {}", request.getURI());
        log.info("请求主机: {}", request.getHeaders().getHost());
        log.info("请求协议: {}", request.getURI().getScheme());

        // 白名单路径，不需要认证
        if (isWhiteList(path)) {
            log.info("路径 {} 在白名单中，跳过认证，继续转发", path);
            return chain.filter(exchange);
        }

        log.info("路径 {} 不在白名单中，需要认证", path);

        // 获取token
        String token = getToken(request);
        if (token == null) {
            log.warn("请求路径 {} 缺少认证token", path);
            return unauthorized(exchange);
        }

        // 验证token（这里简化处理）
        if (!isValidToken(token)) {
            log.warn("请求路径 {} 的token无效", path);
            return unauthorized(exchange);
        }

        log.info("请求路径 {} 认证通过，继续转发", path);
        return chain.filter(exchange);
    }

    private boolean isWhiteList(String path) {
        return path.startsWith("/center/test") ||
                path.startsWith("/actuator") ||
                path.startsWith("/flow/test");
    }

    private String getToken(ServerHttpRequest request) {
        String authHeader = request.getHeaders().getFirst("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return null;
    }

    private boolean isValidToken(String token) {
        // 这里应该调用认证服务验证token
        return !token.isEmpty();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return response.setComplete();
    }

    @Override
    public int getOrder() {
        return -100;
    }
}