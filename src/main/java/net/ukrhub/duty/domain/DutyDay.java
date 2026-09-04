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

import java.time.DayOfWeek;
import java.util.Map;

/**
 * Один день місяця з позначками кожного адміністратора.
 *
 * @param day     число місяця (1..31)
 * @param dow     день тижня
 * @param holiday державне свято/особливий день — у файлі позначається `*`
 *                одразу після скорочення дня тижня (напр. {@code 1  Th*}),
 *                успадковано з застарілого формату (index.pl: колір "re")
 * @param marks   позначка по кожному адміністратору, ключ — {@link Engineer#number()}
 */
public record DutyDay(int day, DayOfWeek dow, boolean holiday, Map<Integer, DutyMark> marks) {

    /**
     * Звичайний день (не свято) — скорочення для більшості викликів і всіх
     * даних, читаних зі старого формату без маркера {@code *}.
     */
    public DutyDay(int day, DayOfWeek dow, Map<Integer, DutyMark> marks) {
        this(day, dow, false, marks);
    }

    /** Субота чи неділя — календарно, без огляду на позначки й свята. */
    public boolean isWeekend() {
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    /**
     * Позначка адміністратора на цей день; відсутня — {@link DutyMark#OFF}
     * (адміністратора могли додати в ростер пізніше, ніж заповнили дні).
     */
    public DutyMark markFor(int engineerNumber) {
        return marks.getOrDefault(engineerNumber, DutyMark.OFF);
    }
}
