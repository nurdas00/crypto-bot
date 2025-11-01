package nur.kg.cryptobot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.PropertySource;

import java.util.List;

@PropertySource("classpath:application.yaml")
@ConfigurationProperties(prefix = "exchange")
public record ExchangeProperties(List<ExchangeEndpoint> urls) {
}