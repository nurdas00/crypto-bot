package nur.kg.cryptobot.service;

import lombok.extern.log4j.Log4j2;
import nur.kg.cryptobot.client.MarketClient;
import nur.kg.cryptobot.metrics.MetricsService;
import nur.kg.domain.dto.TickerDto;
import nur.kg.domain.enums.OrderType;
import nur.kg.domain.enums.Side;
import nur.kg.domain.request.OrderRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Log4j2
@Service
public class MarketService {

    private final MarketClient client;
    private final MetricsService metricsService;

    private final AtomicInteger inflight = new AtomicInteger(0);

    public MarketService(MarketClient client, MetricsService metricsService) {
        this.client = client;
        this.metricsService = metricsService;
        metricsService.registerInflightOrdersGauge(inflight);
    }

    // TODO: write market processing strategy
    public Mono<Void> processMarket(Flux<TickerDto> ticks) {
        return ticks
                .doOnNext(t -> metricsService.getTicksReceivedCounter(t.symbol()).increment())
                .concatMap(this::processSingle)
                .then()
                .doOnSubscribe(s -> log.info("Started market processing"))
                .doOnError(e -> log.error("Market processing error", e))
                .doOnTerminate(() -> log.info("Market processing terminated"));
    }

    private Mono<Void> processSingle(TickerDto dto) {
        if (dto.last() != null) {
            metricsService.getPriceSummary(dto.symbol()).record(dto.last().doubleValue());
        }

        OrderRequest order = toOrderRequest(dto);

        return Mono.defer(() -> {
            metricsService.getOrdersSubmittedCounter(dto.symbol()).increment();
            inflight.incrementAndGet();
            long start = System.nanoTime();

            return client.processOrder(order)
                    .doOnSuccess(v -> {
                        long elapsed = System.nanoTime() - start;
                        metricsService.getOrderProcessingTimer(dto.symbol()).record(elapsed, TimeUnit.NANOSECONDS);
                        log.info("Order {} processed success for {} @{}", order.id(), order.symbol(), order.limitPrice());
                    })
                    .doOnError(e -> {
                        metricsService.getOrdersFailedCounter(dto.symbol()).increment();
                        long elapsed = System.nanoTime() - start;
                        metricsService.getOrderProcessingTimer(dto.symbol()).record(elapsed, TimeUnit.NANOSECONDS);
                        log.warn("Order {} failed for {}: {}", order.id(), order.symbol(), e.toString());
                    })
                    .onErrorResume(e -> Mono.empty())
                    .doFinally(sig -> inflight.decrementAndGet());
        });
    }

    private OrderRequest toOrderRequest(TickerDto t) {
        UUID orderId = UUID.randomUUID();
        return new OrderRequest(
                orderId.toString(),
                t.symbol(),
                Side.BUY,
                OrderType.MARKET,
                BigDecimal.valueOf(0.01),
                t.last(),
                "TEST",
                t.exchange()
        );
    }
}
