package nur.kg.cryptobot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ExchangeProperties.class)
public class CorePropertiesConfig { }