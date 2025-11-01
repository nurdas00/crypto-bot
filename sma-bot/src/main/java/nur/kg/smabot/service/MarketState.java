package nur.kg.smabot.service;

import lombok.Getter;
import lombok.Setter;
import nur.kg.domain.enums.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

public class MarketState {
    private final Deque<BigDecimal> shortWindow = new ArrayDeque<>();
    private final Deque<BigDecimal> longWindow = new ArrayDeque<>();
    private BigDecimal shortSum = BigDecimal.ZERO;
    private BigDecimal longSum = BigDecimal.ZERO;
    private final int shortSize;
    private final int longSize;
    private static final Duration COOLDOWN = Duration.ofSeconds(15);

    @Getter
    @Setter
    private Position position = Position.NONE;
    private Instant lastActionAt = Instant.EPOCH;

    MarketState(int shortSize, int longSize) {
        this.shortSize = shortSize;
        this.longSize = longSize;
    }

    void update(BigDecimal price) {
        shortWindow.add(price);
        shortSum = shortSum.add(price);
        if (shortWindow.size() > shortSize) shortSum = shortSum.subtract(shortWindow.removeFirst());

        longWindow.add(price);
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

    boolean cooldownDone() {
        return Instant.now().isAfter(lastActionAt.plus(COOLDOWN));
    }

    void markAction() {
        lastActionAt = Instant.now();
    }
}