package com.example.wowdashboard;

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
}
