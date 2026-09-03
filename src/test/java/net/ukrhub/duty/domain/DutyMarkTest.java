package net.ukrhub.duty.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DutyMarkTest {

    @Test
    void sessionMarkRoundTripsThroughFileCode() {
        assertThat(DutyMark.fromChar('S')).isEqualTo(DutyMark.SESSION);
        assertThat(DutyMark.SESSION.code()).isEqualTo('S');
        assertThat(DutyMark.SESSION.displayLetter()).isEqualTo("С");
        assertThat(DutyMark.SESSION.cssClass()).isEqualTo("mark-session");
    }

    @Test
    void unknownCodeFallsBackToOff() {
        assertThat(DutyMark.fromChar('?')).isEqualTo(DutyMark.OFF);
    }
}
