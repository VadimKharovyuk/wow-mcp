package com.example.wowmcp.db;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DbServiceTest {

    @Test
    void intArrayLiteralBuildsPostgresArray() {
        // ready_spells передаётся текстовым литералом и кастуется в int4[] на стороне БД
        assertThat(DbService.intArrayLiteral(List.of(1, 2, 3))).isEqualTo("{1,2,3}");
        assertThat(DbService.intArrayLiteral(List.of(42))).isEqualTo("{42}");
    }

    @Test
    void intArrayLiteralIsNullForEmptyList() {
        assertThat(DbService.intArrayLiteral(List.of())).isNull();
        assertThat(DbService.intArrayLiteral(null)).isNull();
    }
}
