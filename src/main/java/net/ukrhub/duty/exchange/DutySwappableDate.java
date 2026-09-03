package net.ukrhub.duty.exchange;

import net.ukrhub.duty.domain.DutyMark;

import java.time.LocalDate;

/**
 * Одна дата, яку конкретний черговий інженер міг би віддати в обмін —
 * обчислюється щоразу наново з поточного графіка ({@link DutyExchangeService#datesFor}),
 * ніде не зберігається. {@code locked} — уже задіяна в іншій активній
 * пропозиції (своїй чи чужій) — у конструкторі пропозиції така дата
 * показується, але недоступна для вибору.
 */
public record DutySwappableDate(LocalDate date, DutyMark type, boolean locked) {
}
