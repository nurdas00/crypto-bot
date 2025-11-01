package nur.kg.smabot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = {"nur.kg"})
@ConfigurationPropertiesScan(basePackages = "nur.kg.smabot")
public class SmaBotApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmaBotApplication.class, args);
    }

}
