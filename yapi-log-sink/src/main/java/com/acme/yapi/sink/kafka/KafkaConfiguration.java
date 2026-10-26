package com.acme.yapi.sink.kafka;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import com.acme.yapi.sink.config.SinkProperties;

@Configuration(proxyBeanMethods = false)
public class KafkaConfiguration {

    /**
     * A failed batch is retried forever at a fixed interval: offsets are committed only after a successful write,
     * and a target outage must not skip records. Poison messages never reach here; they go to the DLQ.
     */
    @Bean
    CommonErrorHandler sinkErrorHandler(SinkProperties properties) {
        return new DefaultErrorHandler(new FixedBackOff(properties.retryInterval().toMillis(),
                FixedBackOff.UNLIMITED_ATTEMPTS));
    }
}
