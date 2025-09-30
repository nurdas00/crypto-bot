package nur.kg.cryptobot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "exchange")
public record ExchangeProperties(String url) {
}