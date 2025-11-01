package nur.kg.cryptobot.client;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import nur.kg.cryptobot.config.ExchangeProperties;
import nur.kg.domain.request.OrderRequest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
@Service
@RequiredArgsConstructor
public class MarketClient {

    private final ExchangeProperties exchangeProperties;

    private final Map<String, WebClient> clients = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        exchangeProperties.urls().forEach(ep -> {
            WebClient wc = WebClient.builder()
                    .baseUrl(ep.url())
                    .build();
            clients.put(ep.name().toLowerCase(), wc);
        });
        log.info("Initialized MarketClient endpoints: {}", clients.keySet());
    }

    public Mono<Void> processOrder(OrderRequest orderRequest) {
        String key = orderRequest.exchange() == null ? null : orderRequest.exchange().name().toLowerCase();
        WebClient wc = selectClient(key);
        if (wc == null) {
            return Mono.error(new IllegalStateException("No WebClient configured for exchange: " + key));
        }

        return wc.post()
                .uri("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(orderRequest)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(3))
                .retryWhen(Retry.backoff(3, Duration.ofMillis(200)).maxBackoff(Duration.ofSeconds(2)))
                .then();
    }

    private WebClient selectClient(String key) {
        if (key != null) {
            WebClient wc = clients.get(key);
            if (wc != null) return wc;
        }
        // fallback: single endpoint or first configured
        return clients.size() == 1 ? clients.values().iterator().next() : null;
    }
}
