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
