package nur.kg.rsibot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import nur.kg.cryptobot.client.MarketClient;
import nur.kg.cryptobot.market.MarketService;
import nur.kg.cryptobot.metrics.MetricsService;
import nur.kg.domain.dto.TickerDto;
import nur.kg.domain.enums.*;
import nur.kg.domain.request.OrderRequest;
import nur.kg.rsibot.config.BotProperties;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Log4j2
@Service
@RequiredArgsConstructor
public class RsiReversionService implements MarketService {

    private final BotProperties botProperties;
    private final MetricsService metricsService;
    private final MarketClient client;

    private final AtomicInteger inflight = new AtomicInteger();

    // Per-symbol simple state: last buy time and "waiting to sell on next tick"
    private final Map<Symbol, RoundtripState> stateMap = new ConcurrentHashMap<>();

    // ====== Strategy parameters ======
    private static final Duration BUY_PERIOD = Duration.ofSeconds(5); // buy every 5s
    private static final int PRICE_SCALE = 4;
    private static final BigDecimal DEFAULT_QTY = new BigDecimal("0.001");

    public Mono<Void> processMarket(Flux<TickerDto> ticks) {
        return ticks
                .doOnNext(t -> metricsService.getTicksReceivedCounter(t.symbol()).increment())
                .concatMap(this::processSingle)
                .then()
                .doOnSubscribe(s -> log.info("Started market processing (buy every {}s, sell on next tick)", BUY_PERIOD.getSeconds()))
                .doOnError(e -> log.error("Market processing error", e))
                .doOnTerminate(() -> log.info("Market processing terminated"));
    }

    private Mono<Void> processSingle(TickerDto dto) {
        if (dto == null || dto.last() == null) return Mono.empty();

        metricsService.getPriceSummary(dto.symbol()).record(dto.last().doubleValue());
        RoundtripState st = stateMap.computeIfAbsent(dto.symbol(), s -> new RoundtripState());

        long now = System.nanoTime();

        // If we bought previously, sell on the very next tick
        if (st.waitingToSell) {
            OrderRequest sell = toMarketOrder(dto, Side.SELL, "sell_next_tick");
            return submit(dto, sell)
                    .doOnSuccess(v -> st.waitingToSell = false);
        }

        // Otherwise, check if it's time to buy (every 5 seconds)
        if (st.lastBuyNano == 0L || elapsedAtLeast(st.lastBuyNano, now, BUY_PERIOD)) {
            OrderRequest buy = toMarketOrder(dto, Side.BUY, "periodic_buy_5s");
            return submit(dto, buy)
                    .doOnSuccess(v -> {
                        st.lastBuyNano = now;
                        st.waitingToSell = true; // arm the immediate next-tick sell
                    });
        }

        return Mono.empty();
    }

    private boolean elapsedAtLeast(long startNano, long nowNano, Duration d) {
        long need = TimeUnit.NANOSECONDS.convert(d.toMillis(), TimeUnit.MILLISECONDS);
        return (nowNano - startNano) >= need;
    }

    private Mono<Void> submit(TickerDto dto, OrderRequest order) {
        return Mono.defer(() -> {
            metricsService.getOrdersSubmittedCounter(dto.symbol()).increment();
            inflight.incrementAndGet();
            long start = System.nanoTime();
            log.info("Submitting order {} {} {} qty={} tp={} sl={}",
                    order.id(), order.symbol(), order.side(), order.qty(), order.tp(), order.sl());

            return client.processOrder(order)
                    .doOnSuccess(v -> {
                        long elapsed = System.nanoTime() - start;
                        metricsService.getOrderProcessingTimer(dto.symbol()).record(elapsed, TimeUnit.NANOSECONDS);
                        log.info("Order {} OK {}", order.id(), order.symbol());
                    })
                    .doOnError(e -> {
                        metricsService.getOrdersFailedCounter(dto.symbol()).increment();
                        long elapsed = System.nanoTime() - start;
                        metricsService.getOrderProcessingTimer(dto.symbol()).record(elapsed, TimeUnit.NANOSECONDS);
                        log.warn("Order {} failed {}: {}", order.id(), order.symbol(), e.toString());
                    })
                    .onErrorResume(e -> Mono.empty())
                    .doFinally(sig -> inflight.decrementAndGet());
        });
    }

    /**
     * Builds a simple MARKET order with no TP/SL for this toy strategy.
     */
    private OrderRequest toMarketOrder(TickerDto dto, Side side, String reason) {
        BigDecimal last = dto.last() == null ? BigDecimal.ZERO : dto.last();
        BigDecimal qty = DEFAULT_QTY;

        // For visibility in logs, show rounded last; TP/SL intentionally null
        BigDecimal lastRounded = last.setScale(PRICE_SCALE, RoundingMode.HALF_UP);

        return OrderRequest.builder()
                .id(UUID.randomUUID().toString())
                .symbol(dto.symbol())
                .side(side)
                .type(OrderType.MARKET)
                .qty(qty)
                .limitPrice(null)
                .reason(reason + "_@price_" + lastRounded)
                .exchange(dto.exchange())
                .botId(botProperties.id())
                .tp(null)
                .sl(null)
                .build();
    }

    private static final class RoundtripState {
        volatile long lastBuyNano = 0L;
        volatile boolean waitingToSell = false;
    }
}
