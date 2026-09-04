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

import java.time.LocalDate;

/**
 * Один елементарний обмін у складі {@link DutyExchangeProposal}: ініціатор
 * віддає свою дату {@code initiatorDate}, натомість забирає дату колеги
 * {@code counterpartDate} — обидві мають бути позначені саме {@code type}
 * у поточного власника ("D на D" чи "W на W", ніколи мішані — інакше
 * непомітно зміниться навантаження одного з двох людей).
 *
 * <p>Застосування (в {@code DutyExchangeService}) міняє місцями позначки
 * ініціатора й колеги на кожній із двох дат окремо — те, що дістанеться
 * іншій людині як "хрестова" клітинка (те, що там уже стояло), має бути
 * {@link DutyMark#WORK} чи {@link DutyMark#OFF}, інакше обмін
 * заблоковано: чужу відпустку/лікарняний/сесію так підміняти не можна.
 */
public record DutyExchangeStep(DutyMark type, LocalDate initiatorDate, LocalDate counterpartDate) {

    public DutyExchangeStep {
        if (type != DutyMark.DUTY && type != DutyMark.WORK) {
            throw new IllegalArgumentException(
                    "Обмін можливий лише для чергування (D) чи робочого дня (W), отримано: " + type);
        }
    }
}
