package net.ukrhub.duty.domain;

import java.util.List;

/**
 * Шаблон ротації чергувань — довільна, іменована альтернатива
 * вбудованому в {@code DutyScheduleGenerator} патерну (жорстко на 2
 * чергових). Слоти (0..slots()-1) — абстрактні позиції ротації, не
 * прив'язані до конкретного адміністратора; те, "хто зараз у слоті N",
 * — окреме питання застосування шаблону, яке цей запис не вирішує.
 *
 * <p>{@code rows} — по одному рядку на слот (не на день, як
 * {@link DutySchedule#days()}: так само, як шаблон бачить і редагує
 * адміністратор — рядок = черговий, символ = день), лише D/W/- (без
 * O/I/S: сесія/лікарняний/відпустка — подія в житті конкретної людини,
 * не властивість шаблону). Усі рядки однакової довжини — це і є період
 * шаблону; довжина не обмежена й не обов'язково мінімальна — можна
 * намалювати хоч мінімальний період, хоч одразу довший блок, як
 * зручніше.
 */
public record RotationTemplate(int id, String name, List<String> rows) {

    public int slots() {
        return rows.size();
    }

    public int period() {
        return rows.isEmpty() ? 0 : rows.get(0).length();
    }

    public DutyMark markAt(int slot, int day) {
        return DutyMark.fromChar(rows.get(slot).charAt(day));
    }

    /**
     * Той самий символ, що й {@link #markAt}, але як {@code String}, а
     * не {@code char} — для порівняння в Thymeleaf/SpEL (шаблон
     * редагування): одинарні лапки в SpEL позначають рядковий літерал,
     * тож {@code markAt(...).code() == 'D'} мовчки завжди хибне
     * (порівняння {@code char} із {@code String}) — жоден {@code
     * <option>} не стає {@code selected}, і браузер підставляє перший
     * за замовчуванням, незалежно від реального значення.
     */
    public String markCodeAt(int slot, int day) {
        return String.valueOf(markAt(slot, day).code());
    }
}
