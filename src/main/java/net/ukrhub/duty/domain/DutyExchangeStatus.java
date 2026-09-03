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

    public String displayName() {
        return displayName;
    }
}
