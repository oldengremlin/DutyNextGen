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

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Пропозиція обміну чергуваннями між двома черговими інженерами — те, що
 * зберігається в одному файлі {@code data/exchanges/<id>}.
 *
 * <p>{@code initiatorName}/{@code counterpartName} — за П.І.Б.
 * ({@link Engineer#name()}), тим самим крихким зв'язком, що й прив'язка
 * користувача до інженера ({@code UserLinkService}) — номер
 * ({@link Engineer#number()}) стабільний лише в межах одного місячного
 * файлу. {@code initiatorUsername} — окремо, для авторства git-коміту,
 * яким застосована пропозиція вноситься в графік (завжди від імені
 * ініціатора, а не того, хто погодився, і не адміністратора, який
 * затвердив — {@code ScheduleEditController} комітить під
 * {@code username + "@duty.local"}, та сама конвенція тут).
 *
 * <p>Час/автор кожного переходу стану — не окремі поля тут, а сам факт
 * git-коміту цього файлу (автор = той, хто виконав дію: ініціатор при
 * створенні, колега при прийнятті/відхиленні, адміністратор при
 * затвердженні/відхиленні) — той самий підхід, що й
 * {@code /schedule/YYYYMM/history}: журнал змін це вже git-лог, дублювати
 * його полями в самому файлі не треба.
 */
public record DutyExchangeProposal(
        int id,
        String initiatorName,
        String initiatorUsername,
        String counterpartName,
        List<DutyExchangeStep> steps,
        DutyExchangeStatus status,
        LocalDateTime createdAt
) {

    public DutyExchangeProposal {
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("Пропозиція обміну без жодного кроку");
        }
    }

    /**
     * Копія з іншим станом — запис незмінний, кожен перехід стану це новий
     * об'єкт і новий git-коміт файлу пропозиції.
     */
    public DutyExchangeProposal withStatus(DutyExchangeStatus newStatus) {
        return new DutyExchangeProposal(id, initiatorName, initiatorUsername, counterpartName, steps, newStatus, createdAt);
    }

    /** Усі місяці, яких стосується хоч один крок — для перевірки при кожному переході стану. */
    public Set<YearMonth> referencedMonths() {
        Set<YearMonth> months = new LinkedHashSet<>();
        for (DutyExchangeStep step : steps) {
            months.add(YearMonth.from(step.initiatorDate()));
            months.add(YearMonth.from(step.counterpartDate()));
        }
        return months;
    }
}
