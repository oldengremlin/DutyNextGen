package net.ukrhub.duty.schedule;

import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.domain.RotationTemplate;
import net.ukrhub.duty.template.RotationTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.YearMonth;

/**
 * Фоновий аналог старого cron + {@code monthly-duty}-демона застарілого
 * проєкту: раз на добу перевіряє, чи є графік на місяць, наступний за
 * поточним реальним, і за відсутності — генерує його на основі поточного
 * місяця. Це лише прямий (не ланцюжковий) крок "сьогодні → наступний
 * місяць" — ланцюжок на кілька місяців уперед (жовтень → листопад → ...)
 * робить лише адміністратор вручну кнопкою «Згенерувати наступний місяць»
 * ({@link net.ukrhub.duty.web.ScheduleGenerationController}), яка ж
 * слугує і резервом на випадок, якщо ця перевірка колись не спрацює.
 *
 * <p>На відміну від ручної кнопки — ніколи нічого не питає (безголова,
 * діалог показати нікому): продовжує рівно той самий шаблон, що й
 * минулого разу ({@link DutySchedule#tid()} поточного місяця), а не
 * шукає серед усіх шаблонів під поточну кількість чергових. Якщо
 * {@code tid} відсутній (місяць згенеровано/відредаговано до появи
 * шаблонів ротації) чи кількість чергових розійшлася з {@code
 * slots()} цього шаблону — свідомо не намагається вгадати заміну,
 * просто пропускає з поясненням у лог: {@code
 * nextgen/docs/rotation-templates.md}, розділ «Зміна кількості
 * чергових».
 */
@Component
public class ScheduleGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduleGenerationScheduler.class);

    private final DutyScheduleRepository repository;
    private final RotationTemplateRepository templateRepository;

    public ScheduleGenerationScheduler(DutyScheduleRepository repository, RotationTemplateRepository templateRepository) {
        this.repository = repository;
        this.templateRepository = templateRepository;
    }

    /** Раз на добу о 03:00 — щоб не заважати можливому ручному редагуванню вдень. */
    @Scheduled(cron = "0 0 3 * * *")
    public void generateNextRealMonthIfMissing() {
        YearMonth currentReal = YearMonth.now();
        YearMonth target = currentReal.plusMonths(1);

        if (repository.exists(target)) {
            return;
        }
        DutySchedule current = repository.find(currentReal).orElse(null);
        if (current == null) {
            log.warn("Немає графіка за поточний місяць {} — автогенерацію {} пропущено", currentReal, target);
            return;
        }

        if (current.tid() == null) {
            log.warn("У графіка {} нема [ Tid ] (шаблон не застосовувався чи місяць старіший за цю функцію) — "
                    + "автогенерацію {} пропущено, потрібне ручне «Згенерувати» з вибором шаблону", currentReal, target);
            return;
        }
        RotationTemplate template = templateRepository.find(current.tid()).orElse(null);
        if (template == null) {
            log.warn("Шаблон №{} з [ Tid ] графіка {} більше не існує — автогенерацію {} пропущено",
                    current.tid(), currentReal, target);
            return;
        }
        long rotatingCount = current.engineers().stream().filter(e -> !e.onlyWorkdays()).count();
        if (rotatingCount != template.slots()) {
            log.warn("Кількість чергових у графіку {} ({}) розійшлася з шаблоном «{}» ({} слотів) — "
                            + "автогенерацію {} пропущено, потрібне ручне «Згенерувати» з вибором шаблону",
                    currentReal, rotatingCount, template.name(), template.slots(), target);
            return;
        }

        try {
            DutySchedule generated = DutyScheduleGenerator.generateNext(current, template);
            repository.save(generated, "Автоматично згенеровано графік " + target + " за шаблоном «"
                            + template.name() + "» (фонова задача)",
                    "duty-nextgen", "duty-nextgen@duty.local");
            log.info("Автоматично згенеровано графік на {} за шаблоном «{}»", target, template.name());
        } catch (ScheduleGenerationException e) {
            log.warn("Не вдалося автоматично згенерувати графік на {}: {}", target, e.getMessage());
        }
    }
}
