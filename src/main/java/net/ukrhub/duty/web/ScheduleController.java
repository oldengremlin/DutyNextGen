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
package net.ukrhub.duty.web;

import net.ukrhub.duty.auth.Role;
import net.ukrhub.duty.auth.RoleCheck;
import net.ukrhub.duty.domain.DutySchedule;
import net.ukrhub.duty.schedule.DutyScheduleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Веб-перегляд графіка на місяць — порт {@code index.pl} застарілого
 * проєкту. Редагування — {@link ScheduleEditController}.
 */
@Controller
public class ScheduleController {

    private final DutyScheduleRepository repository;
    private final String appVersion;

    /**
     * @param appVersion версія з {@code pom.xml} через Maven-фільтрацію
     *        {@code application.yml} — показується у футері сторінки
     */
    public ScheduleController(DutyScheduleRepository repository,
                               @Value("${app.version}") String appVersion) {
        this.repository = repository;
        this.appVersion = appVersion;
    }

    /** Корінь застосунку веде на поточний місяць. */
    @GetMapping("/")
    public String home() {
        return "redirect:/schedule/" + MonthPath.format(YearMonth.now());
    }

    /**
     * Сторінка перегляду. Відсутній графік — не помилка, а порожня сторінка з
     * навігацією: місяць може бути ще не згенерований, і користувач має мати
     * змогу перейти на сусідній.
     */
    @GetMapping("/schedule/{ym}")
    public String schedule(@PathVariable String ym, Model model, Authentication authentication) {
        YearMonth month = MonthPath.parse(ym);

        model.addAttribute("appVersion", appVersion);
        model.addAttribute("month", month);
        model.addAttribute("monthLabel", UkrainianCalendar.monthName(month.getMonth()) + " " + month.getYear());
        model.addAttribute("prevYm", MonthPath.format(month.minusMonths(1)));
        model.addAttribute("nextYm", MonthPath.format(month.plusMonths(1)));

        boolean isCurrentMonth = YearMonth.now().equals(month);
        model.addAttribute("todayDay", isCurrentMonth ? LocalDate.now().getDayOfMonth() : -1);

        DutySchedule schedule = repository.find(month).orElse(null);
        model.addAttribute("schedule", schedule);

        boolean isAdmin = RoleCheck.has(authentication, Role.ADMIN);
        model.addAttribute("canEdit", RoleCheck.has(authentication, Role.EDITOR) || isAdmin);
        model.addAttribute("isAdmin", isAdmin);
        model.addAttribute("canGenerateNext",
                isAdmin && schedule != null && !repository.exists(month.plusMonths(1)));
        model.addAttribute("canDelete",
                isAdmin && schedule != null && month.isAfter(YearMonth.now()));

        return "schedule";
    }
}
