package com.acme.broker.runtime.route;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.acme.broker.runtime.config.BrokerProperties;

/** Subscribes to {@code broker:routes-changed}; the message is the new config version. */
@Configuration(proxyBeanMethods = false)
public class RoutesChangedSubscription {

    private static final Logger log = LoggerFactory.getLogger(RoutesChangedSubscription.class);

    @Bean
    RedisMessageListenerContainer routesChangedListener(RedisConnectionFactory connections, RouteCache cache,
                                                        BrokerProperties properties) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connections);
        container.addMessageListener((message, pattern) -> {
            String body = new String(message.getBody(), StandardCharsets.UTF_8).trim();
            try {
                cache.onRoutesChanged(Long.parseLong(body));
            } catch (NumberFormatException e) {
                log.warn("Anlaşılmayan route sinyali: {}", body);
            } catch (RuntimeException e) {
                log.warn("Sinyal sonrası yükleme başarısız; versiyon kontrolü yeniden deneyecek", e);
            }
        }, new ChannelTopic(properties.cache().routesChangedChannel()));
        return container;
    }
}
