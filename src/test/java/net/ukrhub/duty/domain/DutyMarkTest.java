/*
 * Copyright 2026 olden.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
