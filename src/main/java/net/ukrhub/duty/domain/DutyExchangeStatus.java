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

/**
 * Стан пропозиції обміну чергуваннями ({@link DutyExchangeProposal}).
 *
 * <p>{@code PENDING} → колега приймає ({@code ACCEPTED}) чи відхиляє
 * ({@code DECLINED}). {@code ACCEPTED} → адміністратор затверджує
 * ({@code APPROVED}, зміни йдуть у графік) чи відхиляє ({@code REJECTED}).
 * {@code STALE_CANCELLED} — автоматичне анулювання: місяць, якого
 * стосується пропозиція, зник (перегенерований/видалений) раніше, ніж
 * до неї дійшла черга.
 */
public enum DutyExchangeStatus {
    PENDING("Очікує колегу"),
    ACCEPTED("Очікує адміністратора"),
    DECLINED("Відхилено колегою"),
    APPROVED("Затверджено"),
    REJECTED("Відхилено адміністратором"),
    STALE_CANCELLED("Анульовано автоматично (графік змінився)");

    private final String displayName;

    DutyExchangeStatus(String displayName) {
        this.displayName = displayName;
    }

    /** Ще може змінити стан — бере участь у перевірці зайнятості дат і в анулюванні. */
    public boolean active() {
        return this == PENDING || this == ACCEPTED;
    }

    /** Назва стану для показу в таблиці пропозицій. */
    public String displayName() {
        return displayName;
    }
}
