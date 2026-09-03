package net.ukrhub.duty.domain;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/**
 * Графік чергувань на один місяць — те, що зберігається в одному файлі
 * {@code data/duty/YYYYMM}.
 *
 * <p>{@code lastDays} — позначки K останніх днів цього місяця (K = кількість
 * ротаційних слотів застосованого шаблону — {@code RotationTemplate.slots()}),
 * від найдавнішого (індекс 0) до найновішого (останній індекс). Потрібні
 * лише для того, щоб генератор наступного місяця знав, з якої фази
 * ротаційного шаблону продовжувати — це зафіксований ФАКТИЧНИЙ стан
 * ротації (чергові могли самі помінятися днями всередині місяця), а не
 * внутрішній лічильник алгоритму. Для двох чергових (K=2) це рівно те, що
 * раніше називалось {@code [ LastDay0 ] }/{@code [ LastDay1 ] } —
 * {@link #lastDay0()}/{@link #lastDay1()} лишаються як зручні читачі для
 * цього найпоширенішого випадку. Для самого поточного місяця вони
 * інформаційної ролі не відіграють.
 *
 * <p>{@code tid} — id {@code RotationTemplate}, застосованого при генерації
 * САМЕ цього місяця (null для місяців, згенерованих до появи шаблонів, чи
 * створених/відредагованих вручну без генератора). Автоматична (фонова)
 * генерація наступного місяця спирається саме на нього, щоб не вгадувати
 * шаблон, коли під поточну кількість чергових їх декілька.
 */
public record DutySchedule(
        YearMonth month,
        List<Engineer> engineers,
        List<DutyDay> days,
        List<Map<Integer, DutyMark>> lastDays,
        Integer tid
) {

    /**
     * Сумісність зі старим форматом рівно двох останніх днів (K=2) — цим
     * конструктором користується практично весь наявний код/тести, писаний
     * до появи довільних шаблонів; {@code tid} за замовчуванням {@code null}.
     */
    public DutySchedule(YearMonth month, List<Engineer> engineers, List<DutyDay> days,
                         Map<Integer, DutyMark> lastDay0, Map<Integer, DutyMark> lastDay1) {
        this(month, engineers, days, List.of(lastDay0, lastDay1), null);
    }

    public Engineer engineer(int number) {
        return engineers.stream()
                .filter(e -> e.number() == number)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Немає адміністратора №" + number));
    }

    /** Передостанній із {@link #lastDays()} — найдавніший зафіксований день. */
    public Map<Integer, DutyMark> lastDay0() {
        return lastDays.get(0);
    }

    /** Останній із {@link #lastDays()} — найновіший зафіксований день. */
    public Map<Integer, DutyMark> lastDay1() {
        return lastDays.get(lastDays.size() - 1);
    }
}
