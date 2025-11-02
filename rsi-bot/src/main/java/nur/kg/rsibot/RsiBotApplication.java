package nur.kg.rsibot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = {"nur.kg"})
@ConfigurationPropertiesScan(basePackages = "nur.kg.rsibot")
public class RsiBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(RsiBotApplication.class, args);
    }

}
