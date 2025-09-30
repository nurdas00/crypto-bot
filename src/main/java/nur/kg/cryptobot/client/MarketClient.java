package nur.kg.cryptobot.client;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import nur.kg.cryptobot.config.AppProperties;
import nur.kg.domain.request.OrderRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Log4j2
@Service
@RequiredArgsConstructor
public class MarketClient {

    private WebClient webClient;
    private final AppProperties variable;

    @PostConstruct
    public void init() {
        webClient = WebClient.builder().baseUrl(variable.getExchangeUrl()).build();
    }
    public Mono<Void> processOrder(OrderRequest orderRequest) {
        log.info("Sending order...");
        return webClient.post()
                .uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(orderRequest)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(3))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(200)).maxBackoff(Duration.ofSeconds(2)))
                .then();
    }
}