package com.github.jojotech.spring.cloud.webmvc.feign;

import com.github.jojotech.spring.cloud.webmvc.misc.SpecialHttpStatus;
import feign.Response;
import feign.RetryableException;
import feign.codec.ErrorDecoder;
import lombok.extern.log4j.Log4j2;

import static feign.FeignException.errorStatus;

@Log4j2
public class DefaultErrorDecoder implements ErrorDecoder {
    @Override
    public Exception decode(String methodKey, Response response) {
        boolean queryRequest = OpenfeignUtil.isRetryableRequest(response.request());
        boolean shouldThrowRetryable = queryRequest
                || response.status() == SpecialHttpStatus.CIRCUIT_BREAKER_ON.getValue()
                || response.status() == SpecialHttpStatus.BULKHEAD_FULL.getValue()
                || response.status() == SpecialHttpStatus.RETRYABLE_IO_EXCEPTION.getValue();
        log.info("{} response: {}-{}, should retry: {}", methodKey, response.status(), response.reason(), shouldThrowRetryable);
        //对于查询请求以及可以重试的响应码的异常，进行重试，即抛出可重试异常 RetryableException
        if (shouldThrowRetryable) {
            throw new RetryableException(response.status(), response.reason(), response.request().httpMethod(), null, response.request());
        } else {
            throw errorStatus(methodKey, response);
        }
    }
}
