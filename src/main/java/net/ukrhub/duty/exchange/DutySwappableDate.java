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
package net.ukrhub.duty.exchange;

import net.ukrhub.duty.domain.DutyMark;

import java.time.LocalDate;

/**
 * Одна дата, яку конкретний черговий інженер міг би віддати в обмін —
 * обчислюється щоразу наново з поточного графіка ({@link DutyExchangeService#datesFor}),
 * ніде не зберігається. {@code locked} — уже задіяна в іншій активній
 * пропозиції (своїй чи чужій) — у конструкторі пропозиції така дата
 * показується, але недоступна для вибору.
 *
 * @param date   сама дата
 * @param type   що на ній стоїть — {@link DutyMark#DUTY} чи {@link DutyMark#WORK}
 * @param locked чи вже задіяна в іншій активній пропозиції
 */
public record DutySwappableDate(LocalDate date, DutyMark type, boolean locked) {
}
