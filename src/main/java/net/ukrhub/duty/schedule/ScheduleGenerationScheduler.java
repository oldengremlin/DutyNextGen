package net.ukrhub.duty.schedule;

import net.ukrhub.duty.domain.DutySchedule;
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
 */
@Component
public class ScheduleGenerationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduleGenerationScheduler.class);

    private final DutyScheduleRepository repository;

    public ScheduleGenerationScheduler(DutyScheduleRepository repository) {
        this.repository = repository;
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

        try {
            DutySchedule generated = DutyScheduleGenerator.generateNext(current);
            repository.save(generated, "Автоматично згенеровано графік " + target + " (фонова задача)",
                    "duty-nextgen", "duty-nextgen@duty.local");
            log.info("Автоматично згенеровано графік на {}", target);
        } catch (ScheduleGenerationException e) {
            log.warn("Не вдалося автоматично згенерувати графік на {}: {}", target, e.getMessage());
        }
    }
}
