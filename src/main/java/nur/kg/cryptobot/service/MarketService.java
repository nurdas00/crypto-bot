package nur.kg.cryptobot.service;

import lombok.RequiredArgsConstructor;
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
import java.time.Duration;
import java.util.UUID;

@Log4j2
@Service
@RequiredArgsConstructor
public class MarketService {

    private final MarketClient client;

    public Mono<Void> processMarket(Flux<TickerDto> ticks, Duration period) {
        return ticks
                .doOnNext(t -> log.info("BOT: received {}", t))
                .onBackpressureLatest()
                .sample(period)
                .map(this::toOrderRequest)
                .concatMap(client::processOrder)
                .then();
    }

    private OrderRequest toOrderRequest(TickerDto t) {
        UUID orderId = UUID.randomUUID();
        return new OrderRequest(orderId.toString(), t.symbol(),
                Side.BUY, OrderType.MARKET,
                BigDecimal.valueOf(0.01), t.last(),
                "TEST", t.exchange());
    }
}
