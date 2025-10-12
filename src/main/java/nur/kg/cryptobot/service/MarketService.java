package nur.kg.cryptobot.service;

import io.micrometer.core.instrument.*;
import lombok.extern.log4j.Log4j2;
import nur.kg.cryptobot.client.MarketClient;
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

    private final Counter ticksReceived;
    private final Counter ordersSubmitted;
    private final Counter ordersFailed;
    private final Timer orderProcessingTimer;
    private final DistributionSummary priceSummary;
    private final AtomicInteger inflight = new AtomicInteger(0);

    public MarketService(MarketClient client, MeterRegistry registry) {
        this.client = client;

        this.ticksReceived = Counter.builder("market.ticks.received")
                .description("Total incoming ticks received")
                .register(registry);

        this.ordersSubmitted = Counter.builder("market.orders.submitted")
                .description("Total orders submitted to MarketClient")
                .register(registry);

        this.ordersFailed = Counter.builder("market.orders.failed")
                .description("Total failed orders")
                .register(registry);

        this.orderProcessingTimer = Timer.builder("market.order.processing.duration")
                .description("Time spent processing an order (including client call)")
                .publishPercentileHistogram()
                .register(registry);

        this.priceSummary = DistributionSummary.builder("market.tick.price")
                .description("Distribution of tick prices")
                .baseUnit("USD")
                .register(registry);

        Gauge.builder("market.orders.inflight", inflight, AtomicInteger::get)
                .description("Number of orders currently in flight")
                .register(registry);
    }

    public Mono<Void> processMarket(Flux<TickerDto> ticks) {
        return ticks
                .doOnNext(t -> ticksReceived.increment())
                .concatMap(this::processSingle)
                .then()
                .doOnSubscribe(s -> log.info("Started market processing"))
                .doOnError(e -> log.error("Market processing error", e))
                .doOnTerminate(() -> log.info("Market processing terminated"));
    }

    private Mono<Void> processSingle(TickerDto dto) {
        if (dto.last() != null) {
            priceSummary.record(dto.last().doubleValue());
        }

        OrderRequest order = toOrderRequest(dto);

        return Mono.defer(() -> {
            ordersSubmitted.increment();
            inflight.incrementAndGet();
            long start = System.nanoTime();

            return client.processOrder(order)
                    .doOnSuccess(v -> {
                        long elapsed = System.nanoTime() - start;
                        orderProcessingTimer.record(elapsed, TimeUnit.NANOSECONDS);
                        log.info("Order {} processed success for {} @{}}", order.id(), order.symbol(), order.limitPrice());
                    })
                    .doOnError(e -> {
                        ordersFailed.increment();
                        long elapsed = System.nanoTime() - start;
                        orderProcessingTimer.record(elapsed, TimeUnit.NANOSECONDS);
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
