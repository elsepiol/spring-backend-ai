package dev.elsepiol.shop.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Always on, so OutboxWorker can run by hand even when the background OutboxPoller is switched off. */
@Configuration
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxConfig {
}
