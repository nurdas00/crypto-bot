package nur.kg.cryptobot.client;

import nur.kg.domain.request.OrderRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Service
public class MarketClient {

    private final WebClient http = WebClient.builder().baseUrl("localhost:8080").build();

    public Mono<Void> processOrder(OrderRequest orderRequest) {
        return http.post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(orderRequest)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(3))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(200)).maxBackoff(Duration.ofSeconds(2)))
                .then();
    }
}