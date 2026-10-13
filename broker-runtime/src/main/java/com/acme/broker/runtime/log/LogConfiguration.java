package com.acme.broker.runtime.log;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import com.acme.broker.runtime.config.BrokerProperties;

@Configuration(proxyBeanMethods = false)
public class LogConfiguration {

    @Bean
    LogPublisher logPublisher(BrokerProperties properties, KafkaTemplate<String, byte[]> kafka) {
        return properties.consoleLog() ? new ConsoleLogPublisher()
                : new KafkaLogPublisher(kafka, properties.kafka().topic());
    }
}
