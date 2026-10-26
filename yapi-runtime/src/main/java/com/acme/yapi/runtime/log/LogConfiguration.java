package com.acme.yapi.runtime.log;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;

import com.acme.yapi.runtime.config.YapiProperties;

@Configuration(proxyBeanMethods = false)
public class LogConfiguration {

    @Bean
    LogPublisher logPublisher(YapiProperties properties, KafkaTemplate<String, byte[]> kafka) {
        return properties.consoleLog() ? new ConsoleLogPublisher()
                : new KafkaLogPublisher(kafka, properties.kafka().topic());
    }
}
