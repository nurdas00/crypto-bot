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
    private final Map<Symbol, RsiState> stateMap = new ConcurrentHashMap<>();

    private static final int RSI_PERIOD = 14;
    private static final int PRICE_SCALE = 4;

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
        RsiState st = stateMap.computeIfAbsent(dto.symbol(), s -> new RsiState(RSI_PERIOD));

        BigDecimal price = dto.last();
        st.update(price);
        if (!st.ready()) return Mono.empty();

        TradeAction signal = getSignal(st);
        if (signal == null) return Mono.empty();

        return handleSignal(dto, st, signal);
    }

    private TradeAction getSignal(RsiState st) {
        if (st.pos != Position.NONE) return null;
        if (st.prevRsi <= 30 && st.rsi > 30) return TradeAction.OPEN_LONG;
        if (st.prevRsi >= 70 && st.rsi < 70) return TradeAction.OPEN_SHORT;
        return null;
    }

    private Mono<Void> handleSignal(TickerDto dto, RsiState st, TradeAction signal) {
        switch (signal) {
            case OPEN_LONG -> {
                OrderRequest open = toOpenOrder(dto, Side.BUY, "rsi_long");
                return submit(dto, open).doOnSuccess(v -> st.pos = Position.LONG);
            }
            case OPEN_SHORT -> {
                OrderRequest open = toOpenOrder(dto, Side.SELL, "rsi_short");
                return submit(dto, open).doOnSuccess(v -> st.pos = Position.SHORT);
            }
        }
        return Mono.empty();
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

    private OrderRequest toOpenOrder(TickerDto dto, Side side, String reason) {
        BigDecimal last = dto.last() == null ? BigDecimal.ZERO : dto.last();

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

        tp = tp.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
        sl = sl.setScale(PRICE_SCALE, RoundingMode.HALF_UP);

        return OrderRequest.builder()
                .id(UUID.randomUUID().toString())
                .symbol(dto.symbol())
                .side(side)
                .type(OrderType.MARKET)
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
