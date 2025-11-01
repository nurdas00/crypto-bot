package nur.kg.cryptobot.metrics;

import io.micrometer.core.instrument.*;
import lombok.RequiredArgsConstructor;
import nur.kg.domain.enums.Symbol;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@RequiredArgsConstructor
public class MetricsService {

    private final MeterRegistry registry;

    private final Map<Symbol, Counter> ticksReceivedCounters = new ConcurrentHashMap<>();
    private final Map<Symbol, Counter> ordersSubmittedCounters = new ConcurrentHashMap<>();
    private final Map<Symbol, Counter> ordersFailedCounters = new ConcurrentHashMap<>();
    private final Map<Symbol, Timer> orderProcessingTimers = new ConcurrentHashMap<>();
    private final Map<Symbol, DistributionSummary> priceSummaries = new ConcurrentHashMap<>();

    public Counter getTicksReceivedCounter(Symbol market) {
        return ticksReceivedCounters.computeIfAbsent(market, m ->
                Counter.builder("market.ticks.received")
                        .description("Total incoming ticks received")
                        .tags("market", m.name())
                        .register(registry)
        );
    }

    public Counter getOrdersSubmittedCounter(Symbol market) {
        return ordersSubmittedCounters.computeIfAbsent(market, m ->
                Counter.builder("market.orders.submitted")
                        .description("Total orders submitted")
                        .tags("market", m.name())
                        .register(registry)
        );
    }

    public Counter getOrdersFailedCounter(Symbol market) {
        return ordersFailedCounters.computeIfAbsent(market, m ->
                Counter.builder("market.orders.failed")
                        .description("Total failed orders")
                        .tags("market", m.name())
                        .register(registry)
        );
    }

    public Timer getOrderProcessingTimer(Symbol market) {
        return orderProcessingTimers.computeIfAbsent(market, m ->
                Timer.builder("market.order.processing.duration")
                        .description("Time spent processing an order (including client call)")
                        .publishPercentileHistogram()
                        .tags("market", m.name())
                        .register(registry)
        );
    }

    public DistributionSummary getPriceSummary(Symbol market) {
        return priceSummaries.computeIfAbsent(market, m ->
                DistributionSummary.builder("market.tick.price")
                        .description("Distribution of tick prices")
                        .baseUnit("USD")
                        .tags("market", m.name())
                        .register(registry)
        );
    }

    public void registerInflightOrdersGauge(AtomicInteger inflight) {
        Gauge.builder("market.orders.inflight", inflight, AtomicInteger::get)
                .description("Number of orders currently in flight")
                .register(registry);
    }
}
