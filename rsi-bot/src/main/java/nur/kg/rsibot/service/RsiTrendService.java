package nur.kg.rsibot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import nur.kg.cryptobot.client.MarketClient;
import nur.kg.cryptobot.market.MarketService;
import nur.kg.cryptobot.metrics.MetricsService;
import nur.kg.domain.dto.TickerDto;
import nur.kg.domain.enums.OrderType;
import nur.kg.domain.enums.Position;
import nur.kg.domain.enums.Side;
import nur.kg.domain.enums.TradeAction;
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
public class RsiTrendService implements MarketService {

    private final BotProperties botProperties;
    private final MetricsService metricsService;
    private final MarketClient client;

    private final AtomicInteger inflight = new AtomicInteger();
    private final Map<nur.kg.domain.enums.Symbol, RsiState> stateMap = new ConcurrentHashMap<>();

    private static final BigDecimal DEFAULT_QTY = new BigDecimal("0.001");
    private static final int PRICE_SCALE = 2;

    private static final BigDecimal PRICE_OFFSET = new BigDecimal("0.001");

    private static final BigDecimal SL_PCT = new BigDecimal("0.01");
    private static final BigDecimal TP_PCT = new BigDecimal("0.02");

    @Override
    public Mono<Void> processMarket(Flux<TickerDto> ticks) {
        return ticks
                .doOnNext(t -> metricsService.getTicksReceivedCounter(t.symbol()).increment())
                .concatMap(this::processSingle)
                .then()
                .doOnSubscribe(s -> log.info("Started market processing: trend-based (RS up/down, LIMIT orders)"))
                .doOnError(e -> log.error("Market processing error", e))
                .doOnTerminate(() -> log.info("Market processing terminated"));
    }

    private Mono<Void> processSingle(TickerDto dto) {
        if (dto == null || dto.last() == null) return Mono.empty();

        metricsService.getPriceSummary(dto.symbol()).record(dto.last().doubleValue());
        RsiState st = stateMap.computeIfAbsent(dto.symbol(), s -> new RsiState(14));

        st.update(dto.last());
        if (!st.ready()) return Mono.empty();

        TradeAction action = pickAction(st);
        if (action == null) return Mono.empty();

        return handleAction(dto, st, action);
    }

    private TradeAction pickAction(RsiState st) {
        if (st.isUptrend() && st.pos != Position.LONG) return TradeAction.OPEN_LONG;
        if (st.isDowntrend() && st.pos != Position.SHORT) return TradeAction.OPEN_SHORT;
        return null;
    }

    private Mono<Void> handleAction(TickerDto dto, RsiState st, TradeAction action) {
        switch (action) {
            case OPEN_LONG -> {
                OrderRequest open = toLimitOrder(dto, Side.BUY, "trend_up_rs=" + st.rs());
                return submit(dto, open).doOnSuccess(v -> st.pos = Position.LONG);
            }
            case OPEN_SHORT -> {
                OrderRequest open = toLimitOrder(dto, Side.SELL, "trend_down_rs=" + st.rs());
                return submit(dto, open).doOnSuccess(v -> st.pos = Position.SHORT);
            }
            default -> {
                return Mono.empty();
            }
        }
    }

    private Mono<Void> submit(TickerDto dto, OrderRequest order) {
        return Mono.defer(() -> {
            metricsService.getOrdersSubmittedCounter(dto.symbol()).increment();
            inflight.incrementAndGet();
            long start = System.nanoTime();
            log.info("Submitting order {} {} {} qty={} limit={} tp={} sl={}",
                    order.id(), order.symbol(), order.side(), order.qty(), order.limitPrice(), order.tp(), order.sl());

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
                    .then()
                    .doFinally(sig -> inflight.decrementAndGet());
        });
    }

    private OrderRequest toLimitOrder(TickerDto dto, Side side, String reason) {
        BigDecimal last = dto.last() == null ? BigDecimal.ZERO : dto.last();

        BigDecimal rawLimitPrice = side == Side.BUY
                ? last.multiply(BigDecimal.ONE.subtract(PRICE_OFFSET))
                : last.multiply(BigDecimal.ONE.add(PRICE_OFFSET));

        BigDecimal limitPrice = rawLimitPrice.setScale(PRICE_SCALE, RoundingMode.DOWN);

        BigDecimal tpPrice;
        BigDecimal slPrice;

        if (side == Side.BUY) {
            tpPrice = limitPrice
                    .multiply(BigDecimal.ONE.add(TP_PCT))
                    .setScale(PRICE_SCALE, RoundingMode.DOWN);

            slPrice = limitPrice
                    .multiply(BigDecimal.ONE.subtract(SL_PCT))
                    .setScale(PRICE_SCALE, RoundingMode.DOWN);
        } else {
            BigDecimal base = last.max(limitPrice);

            tpPrice = base
                    .multiply(BigDecimal.ONE.subtract(TP_PCT))
                    .setScale(PRICE_SCALE, RoundingMode.DOWN);

            slPrice = base
                    .multiply(BigDecimal.ONE.add(SL_PCT))
                    .setScale(PRICE_SCALE, RoundingMode.UP);
        }

        return OrderRequest.builder()
                .id(UUID.randomUUID().toString())
                .symbol(dto.symbol())
                .side(side)
                .type(OrderType.LIMIT)
                .qty(DEFAULT_QTY)
                .limitPrice(limitPrice)
                .tp(tpPrice)
                .sl(slPrice)
                .reason(reason + "_@limit_" + limitPrice + "_tp_" + tpPrice + "_sl_" + slPrice)
                .exchange(dto.exchange())
                .botId(botProperties.id())
                .build();
    }
}
