package com.example.wowdashboard.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PeriodsTest {

    @Test
    void hoursAreClampedAndDefaulted() {
        assertThat(Periods.hours(null)).isEqualTo(24);
        assertThat(Periods.hours(0)).isEqualTo(1);
        assertThat(Periods.hours(-5)).isEqualTo(1);
        assertThat(Periods.hours(100_000)).isEqualTo(Periods.MAX_HOURS);
        assertThat(Periods.hours(6)).isEqualTo(6);
    }

    @Test
    void bucketIsWhitelisted() {
        assertThat(Periods.bucket(24)).isEqualTo("hour");
        assertThat(Periods.bucket(48)).isEqualTo("hour");
        assertThat(Periods.bucket(168)).isEqualTo("day");
    }

    @Test
    void minutesAreClampedAndDefaulted() {
        assertThat(Periods.minutes(null)).isEqualTo(60);
        assertThat(Periods.minutes(1)).isEqualTo(5);
        assertThat(Periods.minutes(-3)).isEqualTo(5);
        assertThat(Periods.minutes(10_000_000)).isEqualTo(Periods.MAX_MINUTES);
        assertThat(Periods.minutes(180)).isEqualTo(180);
    }
}
