package nur.kg.cryptobot.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Getter
@Configuration
public class AppProperties {

    @Value("${exchange.url}")
    private String exchangeUrl;
}
