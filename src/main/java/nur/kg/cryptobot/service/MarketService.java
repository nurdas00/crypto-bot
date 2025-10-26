package nur.kg.cryptobot.service;

import lombok.extern.log4j.Log4j2;
import nur.kg.cryptobot.client.MarketClient;
import nur.kg.cryptobot.metrics.MetricsService;
import nur.kg.domain.dto.TickerDto;
import nur.kg.domain.enums.OrderType;
import nur.kg.domain.enums.Side;
import nur.kg.domain.enums.Symbol;
import nur.kg.domain.request.OrderRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Log4j2
@Service
public class MarketService {

    private final MetricsService metricsService;
    private final MarketClient client;
    private final AtomicInteger inflight = new AtomicInteger();

    private final Map<Symbol, MarketState> stateMap = new ConcurrentHashMap<>();

    private static final int SHORT_WINDOW = 5;
    private static final int LONG_WINDOW = 20;

    public MarketService(MetricsService metricsService, MarketClient client) {
        this.metricsService = metricsService;
        this.client = client;
    }

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
        if (dto.last() == null) return Mono.empty();
        metricsService.getPriceSummary(dto.symbol()).record(dto.last().doubleValue());

        MarketState state = stateMap.computeIfAbsent(dto.symbol(), s -> new MarketState(SHORT_WINDOW, LONG_WINDOW));
        state.update(dto.last());

        if (!state.ready()) return Mono.empty();

        BigDecimal shortAvg = state.shortAverage();
        BigDecimal longAvg = state.longAverage();
        Side signal = null;

        if (!state.positionOpen && shortAvg.compareTo(longAvg) > 0) {
            signal = Side.BUY;
            state.positionOpen = true;
        } else if (state.positionOpen && shortAvg.compareTo(longAvg) < 0) {
            signal = Side.SELL;
            state.positionOpen = false;
        }

        if (signal != null) {
            OrderRequest order = toOrderRequest(dto);
            return submitOrder(dto, order);
        }

        return Mono.empty();
    }

    private Mono<Void> submitOrder(TickerDto dto, OrderRequest order) {
        return Mono.defer(() -> {
            metricsService.getOrdersSubmittedCounter(dto.symbol()).increment();
            inflight.incrementAndGet();
            long start = System.nanoTime();

            return client.processOrder(order)
                    .doOnSuccess(v -> {
                        long elapsed = System.nanoTime() - start;
                        metricsService.getOrderProcessingTimer(dto.symbol())
                                .record(elapsed, TimeUnit.NANOSECONDS);
                        log.info("Order {} processed success for {} @{}", order.id(), order.symbol(), order.limitPrice());
                    })
                    .doOnError(e -> {
                        metricsService.getOrdersFailedCounter(dto.symbol()).increment();
                        long elapsed = System.nanoTime() - start;
                        metricsService.getOrderProcessingTimer(dto.symbol())
                                .record(elapsed, TimeUnit.NANOSECONDS);
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
                "moving_average_signal",
                t.exchange()
        );
    }

    private static class MarketState {
        private final Deque<BigDecimal> shortWindow = new ArrayDeque<>();
        private final Deque<BigDecimal> longWindow = new ArrayDeque<>();
        private final int shortSize;
        private final int longSize;
        private BigDecimal shortSum = BigDecimal.ZERO;
        private BigDecimal longSum = BigDecimal.ZERO;
        private boolean positionOpen = false;

        MarketState(int shortSize, int longSize) {
            this.shortSize = shortSize;
            this.longSize = longSize;
        }

        void update(BigDecimal price) {
            shortWindow.addLast(price);
            shortSum = shortSum.add(price);
            if (shortWindow.size() > shortSize) shortSum = shortSum.subtract(shortWindow.removeFirst());

            longWindow.addLast(price);
            longSum = longSum.add(price);
            if (longWindow.size() > longSize) longSum = longSum.subtract(longWindow.removeFirst());
        }

        boolean ready() {
            return shortWindow.size() >= shortSize && longWindow.size() >= longSize;
        }

        BigDecimal shortAverage() {
            return shortSum.divide(BigDecimal.valueOf(shortWindow.size()), RoundingMode.HALF_UP);
        }

        BigDecimal longAverage() {
            return longSum.divide(BigDecimal.valueOf(longWindow.size()), RoundingMode.HALF_UP);
        }
    }
}