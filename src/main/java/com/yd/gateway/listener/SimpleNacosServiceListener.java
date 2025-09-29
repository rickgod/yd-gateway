package com.yd.gateway.listener;

import com.alibaba.cloud.nacos.NacosDiscoveryProperties;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.listener.EventListener;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.pojo.ListView;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.route.RouteDefinitionWriter;
import org.springframework.cloud.loadbalancer.cache.LoadBalancerCacheManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import com.alibaba.nacos.api.naming.NamingService;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


@Slf4j
@Component
public class SimpleNacosServiceListener {

    @Autowired
    private NacosDiscoveryProperties nacosDiscoveryProperties;

//    @Autowired
//    private RouteDefinitionWriter routeDefinitionWriter;

    @Resource(name = "caffeineLoadBalancerCacheManager")
    private LoadBalancerCacheManager loadBalancerCacheManager;
    // 当前已订阅的服务集合，用于对比和管理
    private final Set<String> subscribedServices = ConcurrentHashMap.newKeySet();

    // 定时任务的执行状态
    private volatile boolean isRunning = false;
    // 定时任务线程
    private Thread scanThread;

    // 存储每个服务对应的监听器，用于后续取消订阅
    private final Map<String, EventListener> serviceListeners = new ConcurrentHashMap<>();


    @PostConstruct
    public void init() throws NacosException {
        try {
            isRunning = true;
            // 启动定时扫描线程
            scanThread = new Thread(this::periodicScanServices, "nacos-service-scanner");
            scanThread.setDaemon(true);
            scanThread.start();

            log.info("Nacos服务动态监听器初始化成功");
        } catch (Exception e) {
            log.error("初始化Nacos服务动态监听器失败", e);
        }
    }


    @PreDestroy
    public void destroy() {
        isRunning = false;
        if (scanThread != null && scanThread.isAlive()) {
            scanThread.interrupt();
        }
        // 取消所有订阅
        try {
            NamingService namingService = nacosDiscoveryProperties.namingServiceInstance();
            for (Map.Entry<String, EventListener> entry : serviceListeners.entrySet()) {
                namingService.unsubscribe(entry.getKey(), entry.getValue());
            }
            log.info("已取消所有Nacos服务订阅");
        } catch (Exception e) {
            log.error("取消Nacos服务订阅失败", e);
        }
        subscribedServices.clear();
        serviceListeners.clear();
    }

    /**
     * 定期扫描Nacos中的服务列表
     */
    private void periodicScanServices() {
        try{
            while (isRunning) {
                try {
                    // 执行一次服务扫描和订阅更新
                    updateServiceSubscriptions();
                    // 等待1分钟后再次执行
                    TimeUnit.MINUTES.sleep(1);
                } catch (InterruptedException e) {
                    log.info("服务扫描线程被中断");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("执行服务扫描任务异常", e);
                    // 出错时等待10秒后重试
                    try {
                        TimeUnit.SECONDS.sleep(10);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }


        }catch (Exception e){
            log.error("任务异常", e);
        }
    }


    /**
     * 探活等待：反复小页查询服务列表，成功一次即视为连接可用
     */
    private void waitUntilConnected(NamingService namingService) throws InterruptedException {
        int retries = 0;
        while (true) {
            try {
                // 分组可按需指定：若你使用了自定义 group，改为 getServicesOfServer(1,1, group)
                ListView<String> page = namingService.getServicesOfServer(1, 1);
                if (page != null) {
                    log.info("Nacos连接就绪，开始订阅与扫描");
                    return;
                }
            } catch (Exception ignore) {
                // 仍在 STARTING 或网络未通
            }
            retries++;
            long backoffMs = Math.min(5000, 500L * retries);
            log.info("等待Nacos连接中（第{}次）… {}ms后重试", retries, backoffMs);
            Thread.sleep(backoffMs);
        }
    }


    /**
     * 更新服务订阅，添加新服务订阅，取消已移除服务的订阅
     */
    private void updateServiceSubscriptions() throws NacosException {
        NamingService namingService = nacosDiscoveryProperties.namingServiceInstance();
        // 获取当前Nacos注册中心的所有服务
        List<String> currentServices = namingService.getServicesOfServer(1, Integer.MAX_VALUE)
                .getData().stream()
                .filter(Objects::nonNull)
                .filter(service -> !service.trim().isEmpty())
                .collect(Collectors.toList());

        log.debug("当前Nacos注册中心服务数量: {}", currentServices.size());

        Set<String> newServices = new HashSet<>(currentServices);
        Set<String> oldServices = new HashSet<>(subscribedServices);

        // 找出新增的服务
        newServices.removeAll(oldServices);
        // 找出已移除的服务
        oldServices.removeAll(new HashSet<>(currentServices));

        if (!newServices.isEmpty() || !oldServices.isEmpty()) {
            log.info("检测到服务变化 - 新增服务数: {}, 移除服务数: {}",
                    newServices.size(), oldServices.size());
        }

        // 为新增的服务添加订阅
        for (String serviceName : newServices) {
            try {
                subscribeToService(namingService, serviceName);
                subscribedServices.add(serviceName);
            } catch (Exception e) {
                log.error("订阅服务 {} 失败", serviceName, e);
            }
        }

        // 为已移除的服务取消订阅
        for (String serviceName : oldServices) {
            try {
                unsubscribeFromService(namingService, serviceName);
                subscribedServices.remove(serviceName);
            } catch (Exception e) {
                log.error("取消订阅服务 {} 失败", serviceName, e);
            }
        }
    }


    /**
     * 订阅指定服务
     */
    private void subscribeToService(NamingService namingService, String serviceName) throws NacosException {
        // 创建监听器
        EventListener listener = event -> {
            log.debug("收到Nacos事件: {}", event.getClass().getSimpleName());
            if (event instanceof NamingEvent) {
                NamingEvent namingEvent = (NamingEvent) event;
                String changedServiceName = namingEvent.getServiceName();
                int instanceCount = namingEvent.getInstances().size();
                log.info("监听到服务变化 - 服务名: {}, 当前实例数: {}",
                        changedServiceName, instanceCount);
                // 这里可以根据实际需求处理实例变化事件
                // 刷新网关路由缓存
//                routeDefinitionWriter.setRouteDefinitions(Mono.empty()).subscribe();   //不是这样用的
                log.info("刷新负载均衡缓存");
                loadBalancerCacheManager.getCache("CachingServiceInstanceListSupplierCache").clear();
            }
        };

        // 订阅服务
        namingService.subscribe(serviceName, listener);
        // 保存监听器引用以便后续取消订阅
        serviceListeners.put(serviceName, listener);

        log.info("成功订阅服务: {}", serviceName);
    }

    /**
     * 取消订阅指定服务
     */
    private void unsubscribeFromService(NamingService namingService, String serviceName) throws NacosException {
        EventListener listener = serviceListeners.remove(serviceName);
        if (listener != null) {
            namingService.unsubscribe(serviceName, listener);
            log.info("成功取消订阅服务: {}", serviceName);
        }
    }

}




