package nur.kg.smabot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import nur.kg.cryptobot.client.MarketClient;
import nur.kg.cryptobot.market.MarketService;
import nur.kg.cryptobot.metrics.MetricsService;
import nur.kg.domain.dto.TickerDto;
import nur.kg.domain.enums.*;
import nur.kg.domain.request.OrderRequest;
import nur.kg.smabot.config.BotProperties;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Log4j2
@Service
@RequiredArgsConstructor
public class SmaMarketService implements MarketService {

    private final BotProperties botProperties;
    private final MetricsService metricsService;
    private final MarketClient client;

    private final AtomicInteger inflight = new AtomicInteger();
    private final Map<Symbol, MarketState> stateMap = new ConcurrentHashMap<>();

    private static final int SHORT_WINDOW = 20;
    private static final int LONG_WINDOW = 100;

    @Override
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
        if (dto == null || dto.last() == null) return Mono.empty();

        metricsService.getPriceSummary(dto.symbol()).record(dto.last().doubleValue());
        MarketState state = stateMap.computeIfAbsent(dto.symbol(), s -> new MarketState(SHORT_WINDOW, LONG_WINDOW));

        state.update(dto.last());

        if (!state.ready()) return Mono.empty();

        BigDecimal shortAvg = state.shortAverage();
        BigDecimal longAvg = state.longAverage();

        TradeAction signal = getTradeAction(shortAvg, longAvg, state);
        if (signal == null) return Mono.empty();

        return handleSignal(dto, state, signal);
    }

    private static TradeAction getTradeAction(BigDecimal shortAvg, BigDecimal longAvg, MarketState state) {
        if (state.getPosition() != Position.NONE) return null;
        int cmp = shortAvg.compareTo(longAvg);
        if (cmp > 0) return TradeAction.OPEN_LONG;
        if (cmp < 0) return TradeAction.OPEN_SHORT;
        return null;
    }

    private Mono<Void> handleSignal(TickerDto dto, MarketState state, TradeAction signal) {
        switch (signal) {
            case OPEN_LONG -> {
                OrderRequest open = toOpenRequestWithBracket(dto, Side.BUY, "open_long");
                return submitOrder(dto, open)
                        .doOnSuccess(v -> state.setPosition(Position.LONG));
            }
            case OPEN_SHORT -> {
                OrderRequest open = toOpenRequestWithBracket(dto, Side.SELL, "open_short");
                return submitOrder(dto, open)
                        .doOnSuccess(v -> state.setPosition(Position.SHORT));
            }
        }
        return Mono.empty();
    }

    private Mono<Void> submitOrder(TickerDto dto, OrderRequest order) {
        return Mono.defer(() -> {
            log.info("Submitting order {} for {} {} qty={} (reason: {}) TP={} SL={} type={}",
                    order.id(), order.symbol(), order.side(), order.qty(), order.reason(),
                    order.tp(), order.sl(), order.type());

            metricsService.getOrdersSubmittedCounter(dto.symbol()).increment();
            inflight.incrementAndGet();
            long start = System.nanoTime();

            return client.processOrder(order)
                    .doOnSuccess(v -> {
                        long elapsed = System.nanoTime() - start;
                        metricsService.getOrderProcessingTimer(dto.symbol()).record(elapsed, TimeUnit.NANOSECONDS);
                        log.info("Order {} success for {} {}", order.id(), order.symbol(), order.side());
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

    private OrderRequest toOpenRequestWithBracket(TickerDto dto, Side side, String reason) {
        final Symbol symbol = dto.symbol();
        final int priceScale = 4;

        BigDecimal last = dto.last();
        if (last == null) last = BigDecimal.ZERO;

        BigDecimal qty = new BigDecimal("0.001");

        BigDecimal slDelta = new BigDecimal("0.5");
        BigDecimal tpDelta = slDelta.multiply(new BigDecimal("4"));

        BigDecimal tp, sl;
        if (side == Side.BUY) {
            tp = last.add(tpDelta);
            sl = last.subtract(slDelta);
        } else {
            tp = last.subtract(tpDelta);
            sl = last.add(slDelta);
        }

        tp = tp.setScale(priceScale, RoundingMode.HALF_UP);
        sl = sl.setScale(priceScale, RoundingMode.HALF_UP);

        return OrderRequest.builder()
                .id(UUID.randomUUID().toString())
                .symbol(symbol)
                .side(side)
                .type(OrderType.LIMIT)
                .qty(qty)
                .limitPrice(null)
                .reason(reason)
                .exchange(dto.exchange())
                .botId(botProperties.id())
                .tp(tp)
                .sl(sl)
                .build();
    }
}
