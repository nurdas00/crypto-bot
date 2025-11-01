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
        Position pos = Position.NONE;
        Instant lastUpdate = Instant.EPOCH;

        RsiState(int period) {
            this.period = period;
        }

        void update(BigDecimal price) {
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
                if (avgGain.add(avgLoss).compareTo(BigDecimal.ZERO) > 0 && ++seedCount >= period) {
                    avgGain = avgGain.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
                    avgLoss = avgLoss.divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
                    seeded = true;
                    computeRsi();
                }
            } else {
                avgGain = avgGain.multiply(BigDecimal.valueOf(period - 1))
                        .add(gain)
                        .divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
                avgLoss = avgLoss.multiply(BigDecimal.valueOf(period - 1))
                        .add(loss)
                        .divide(BigDecimal.valueOf(period), 10, RoundingMode.HALF_UP);
                computeRsi();
            }
            prevPrice = price;
            lastUpdate = Instant.now();
        }

        int seedCount = 0;

        void computeRsi() {
            prevRsi = rsi;
            if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
                rsi = 100.0;
                return;
            }
            BigDecimal rs = avgGain.divide(avgLoss, 10, RoundingMode.HALF_UP);
            BigDecimal oneHundred = new BigDecimal("100");
            BigDecimal denom = BigDecimal.ONE.add(rs);
            BigDecimal value = oneHundred.subtract(oneHundred.divide(denom, 10, RoundingMode.HALF_UP));
            rsi = value.doubleValue();
        }

        boolean ready() {
            return seeded;
        }
    }