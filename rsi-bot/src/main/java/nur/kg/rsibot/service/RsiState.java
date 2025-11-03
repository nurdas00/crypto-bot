package nur.kg.rsibot.service;

import nur.kg.domain.enums.Position;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

public class RsiState {
    final int period;
    boolean seeded = false;
    BigDecimal prevPrice = null;
    BigDecimal avgGain = BigDecimal.ZERO;
    BigDecimal avgLoss = BigDecimal.ZERO;

    double prevRsi = 50.0;
    double rsi = 50.0;

    // === Trend state derived from RS (avgGain / avgLoss)
    public enum Trend { UP, DOWN, FLAT }
    private Trend trend = Trend.FLAT;
    private BigDecimal rs = BigDecimal.ONE; // RS = avgGain / avgLoss

    public Position pos = Position.NONE;
    public Instant lastUpdate = Instant.EPOCH;

    // Hysteresis thresholds to reduce noise
    private static final BigDecimal RS_UP = new BigDecimal("1.05");   // > +5% more gains than losses
    private static final BigDecimal RS_DOWN = new BigDecimal("0.95"); // > +5% more losses than gains

    int seedCount = 0;

    public RsiState(int period) {
        this.period = period;
    }

    public void update(BigDecimal price) {
        if (price == null) return;

        if (prevPrice == null) {
            prevPrice = price;
            return;
        }

        BigDecimal change = price.subtract(prevPrice);
        BigDecimal gain = change.signum() > 0 ? change : BigDecimal.ZERO;
        BigDecimal loss = change.signum() < 0 ? change.abs() : BigDecimal.ZERO;

        if (!seeded) {
            avgGain = avgGain.add(gain);
            avgLoss = avgLoss.add(loss);
            seedCount++;
            if (seedCount >= period) {
                avgGain = avgGain.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
                avgLoss = avgLoss.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
                seeded = true;
                computeRsiAndTrend();
            }
        } else {
            avgGain = avgGain.multiply(BigDecimal.valueOf(period - 1))
                    .add(gain)
                    .divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);

            avgLoss = avgLoss.multiply(BigDecimal.valueOf(period - 1))
                    .add(loss)
                    .divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);

            computeRsiAndTrend();
        }

        prevPrice = price;
        lastUpdate = Instant.now();
    }

    private void computeRsiAndTrend() {
        prevRsi = rsi;

        if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
            // no losses -> RS is infinite, RSI = 100, strong uptrend
            rsi = 100.0;
            rs = new BigDecimal("999999999"); // sentinel large
            trend = Trend.UP;
            return;
        }

        rs = avgGain.divide(avgLoss, 10, RoundingMode.HALF_UP);

        BigDecimal oneHundred = new BigDecimal("100");
        BigDecimal denom = BigDecimal.ONE.add(rs);
        BigDecimal value = oneHundred.subtract(oneHundred.divide(denom, 10, RoundingMode.HALF_UP));
        rsi = value.doubleValue();

        // Hysteresis-based trend classification
        int cmpUp = rs.compareTo(RS_UP);
        int cmpDown = rs.compareTo(RS_DOWN);
        if (cmpUp > 0) {
            trend = Trend.UP;
        } else if (cmpDown < 0) {
            trend = Trend.DOWN;
        } else {
            trend = Trend.FLAT;
        }
    }

    public boolean ready() {
        return seeded;
    }

    // === Expose trend info for the strategy ===
    public Trend trend() {
        return trend;
    }

    public boolean isUptrend() {
        return trend == Trend.UP;
    }

    public boolean isDowntrend() {
        return trend == Trend.DOWN;
    }

    public BigDecimal rs() {
        return rs;
    }

    public double rsi() {
        return rsi;
    }
}
