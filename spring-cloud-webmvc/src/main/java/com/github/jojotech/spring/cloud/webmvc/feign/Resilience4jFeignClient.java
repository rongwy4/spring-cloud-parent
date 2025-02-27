package com.github.jojotech.spring.cloud.webmvc.feign;

import brave.Span;
import brave.Tracer;
import com.alibaba.fastjson.JSON;
import com.github.jojotech.spring.cloud.commons.metric.ServiceInstanceMetrics;
import com.github.jojotech.spring.cloud.webmvc.misc.SpecialHttpStatus;
import feign.Client;
import feign.Request;
import feign.Response;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead;
import io.github.resilience4j.bulkhead.ThreadPoolBulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.ConfigurationNotFoundException;
import io.vavr.control.Try;
import lombok.extern.log4j.Log4j2;
import lombok.extern.slf4j.Slf4j;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.openfeign.FeignClient;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 粘合断路器，线程隔离的核心代码
 * 同时也记录了负载均衡的实际调用数据
 */
@Log4j2
public class Resilience4jFeignClient implements Client {
    private final ServiceInstanceMetrics serviceInstanceMetrics;
    private final ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final Tracer tracer;
    private ApacheHttpClient apacheHttpClient;


    public Resilience4jFeignClient(
            ServiceInstanceMetrics serviceInstanceMetrics, ApacheHttpClient apacheHttpClient,
            ThreadPoolBulkheadRegistry threadPoolBulkheadRegistry,
            CircuitBreakerRegistry circuitBreakerRegistry,
            Tracer tracer
    ) {
        this.serviceInstanceMetrics = serviceInstanceMetrics;
        this.apacheHttpClient = apacheHttpClient;
        this.threadPoolBulkheadRegistry = threadPoolBulkheadRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.tracer = tracer;
    }

    @Override
    public Response execute(Request request, Request.Options options) throws IOException {
        //获取定义 FeignClient 的接口的 FeignClient 注解
        FeignClient annotation = request.requestTemplate().methodMetadata().method().getDeclaringClass().getAnnotation(FeignClient.class);
        //和 Retry 保持一致，使用 contextId，而不是微服务名称
        //contextId 会作为我们后面读取断路器以及线程隔离配置的 key
        String contextId = annotation.contextId();
        //获取实例唯一id
        String serviceInstanceId = getServiceInstanceId(contextId, request);
        //获取实例+方法唯一id
        String serviceInstanceMethodId = getServiceInstanceMethodId(contextId, request);

        ThreadPoolBulkhead threadPoolBulkhead;
        CircuitBreaker circuitBreaker;
        try {
            //每个实例一个线程池
            threadPoolBulkhead = threadPoolBulkheadRegistry.bulkhead(serviceInstanceId, contextId);
        } catch (ConfigurationNotFoundException e) {
            threadPoolBulkhead = threadPoolBulkheadRegistry.bulkhead(serviceInstanceId);
        }
        try {
            //每个服务实例具体方法一个resilience4j熔断记录器，在服务实例具体方法维度做熔断，所有这个服务的实例具体方法共享这个服务的resilience4j熔断配置
            circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceInstanceMethodId, contextId);
        } catch (ConfigurationNotFoundException e) {
            circuitBreaker = circuitBreakerRegistry.circuitBreaker(serviceInstanceMethodId);
        }
        //保持traceId
        Span span = tracer.currentSpan();
        ThreadPoolBulkhead finalThreadPoolBulkhead = threadPoolBulkhead;
        CircuitBreaker finalCircuitBreaker = circuitBreaker;
        Supplier<CompletionStage<Response>> completionStageSupplier = ThreadPoolBulkhead.decorateSupplier(threadPoolBulkhead,
                OpenfeignUtil.decorateSupplier(circuitBreaker, () -> {
                    try (Tracer.SpanInScope cleared = tracer.withSpanInScope(span)) {
                        log.info("call url: {} -> {}, ThreadPoolStats({}): {}, CircuitBreakStats({}): {}",
                                request.httpMethod(),
                                request.url(),
                                serviceInstanceId,
                                JSON.toJSONString(finalThreadPoolBulkhead.getMetrics()),
                                serviceInstanceMethodId,
                                JSON.toJSONString(finalCircuitBreaker.getMetrics())
                        );
                        Response execute = apacheHttpClient.execute(request, options);
                        log.info("response: {} - {}", execute.status(), execute.reason());
                        return execute;
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                })
        );
        ServiceInstance serviceInstance = getServiceInstance(request);
        try {
            serviceInstanceMetrics.recordServiceInstanceCall(serviceInstance);
            Response response = Try.ofSupplier(completionStageSupplier).get().toCompletableFuture().join();
            serviceInstanceMetrics.recordServiceInstanceCalled(serviceInstance, true);
            return response;
        } catch (BulkheadFullException e) {
            //线程池限流异常
            serviceInstanceMetrics.recordServiceInstanceCalled(serviceInstance, false);
            return Response.builder()
                    .request(request)
                    .status(SpecialHttpStatus.BULKHEAD_FULL.getValue())
                    .reason(e.getLocalizedMessage())
                    .requestTemplate(request.requestTemplate()).build();
        } catch (CompletionException e) {
            serviceInstanceMetrics.recordServiceInstanceCalled(serviceInstance, false);
            //内部抛出的所有异常都被封装了一层 CompletionException，所以这里需要取出里面的 Exception
            Throwable cause = e.getCause();
            //对于断路器打开，返回对应特殊的错误码
            if (cause instanceof CallNotPermittedException) {
                return Response.builder()
                        .request(request)
                        .status(SpecialHttpStatus.CIRCUIT_BREAKER_ON.getValue())
                        .reason(cause.getLocalizedMessage())
                        .requestTemplate(request.requestTemplate()).build();
            }
            //对于 IOException，需要判断是否请求已经发送出去了
            //对于 connect time out 的异常，则可以重试，因为请求没发出去，但是例如 read time out 则不行，因为请求已经发出去了
            if (cause instanceof IOException) {
                boolean containsRead = cause.getMessage().toLowerCase().contains("read");
                if (containsRead) {
                    log.info("{}-{} exception contains read, which indicates the request has been sent", e.getMessage(), cause.getMessage());
                    //如果是 read 异常，则代表请求已经发了出去，则不能重试（除非是 GET 请求或者有 RetryableMethod 注解，这个在 DefaultErrorDecoder 判断）
                    return Response.builder()
                            .request(request)
                            .status(SpecialHttpStatus.NOT_RETRYABLE_IO_EXCEPTION.getValue())
                            .reason(cause.getLocalizedMessage())
                            .requestTemplate(request.requestTemplate()).build();
                } else {
                    return Response.builder()
                            .request(request)
                            .status(SpecialHttpStatus.RETRYABLE_IO_EXCEPTION.getValue())
                            .reason(cause.getLocalizedMessage())
                            .requestTemplate(request.requestTemplate()).build();
                }
            }
            throw e;
        }
    }

    private ServiceInstance getServiceInstance(Request request) throws MalformedURLException {
        URL url = new URL(request.url());
        DefaultServiceInstance defaultServiceInstance = new DefaultServiceInstance();
        defaultServiceInstance.setHost(url.getHost());
        defaultServiceInstance.setPort(url.getPort());
        return defaultServiceInstance;
    }

    //获取微服务实例id，格式为：FeignClient 的 contextId:host:port，例如： test1Client:10.238.45.78:8251
    private String getServiceInstanceId(String contextId, Request request) throws MalformedURLException {
        //解析 URL
        URL url = new URL(request.url());
        //拼接微服务实例id
        return contextId + ":" + url.getHost() + ":" + url.getPort();
    }

    //获取微服务实例方法id，格式为：FeignClient 的 contextId:host:port:methodName，例如：test1Client:10.238.45.78:8251:
    private String getServiceInstanceMethodId(String contextId, Request request) throws MalformedURLException {
        URL url = new URL(request.url());
        //通过微服务名称 + 实例 + 方法的方式，获取唯一id
        String methodName = request.requestTemplate().methodMetadata().method().toGenericString();
        return contextId + ":" + url.getHost() + ":" + url.getPort() + ":" + methodName;
    }
}
