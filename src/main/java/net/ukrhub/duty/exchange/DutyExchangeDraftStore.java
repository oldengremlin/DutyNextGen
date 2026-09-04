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
package net.ukrhub.duty.exchange;

import net.ukrhub.duty.domain.DutyExchangeStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Чернетка пропозиції обміну, яку черговий складає в конструкторі на
 * {@code /exchange} перед відправкою — суто екранний стан, у пам'яті, не
 * git-версіюється (на відміну від уже відправлених пропозицій,
 * {@link DutyExchangeRepository}): нічого цінного не втрачається, якщо
 * застосунок перезапуститься чи чернетку просто покинули незавершеною.
 *
 * <p>Один черговий може за один захід накопичити кроки з різними
 * колегами одразу ({@code DraftStep.counterpartName()} — по кожному
 * кроку окремо) — на відправку ({@code DutyExchangeController}) вони
 * групуються за колегою й ідуть окремими пропозиціями, по одній на пару.
 */
@Component
public class DutyExchangeDraftStore {

    public record DraftStep(String counterpartName, DutyExchangeStep step) {
    }

    private final Map<String, List<DraftStep>> draftsByUsername = new ConcurrentHashMap<>();

    public List<DraftStep> get(String username) {
        return draftsByUsername.getOrDefault(username, List.of());
    }

    public void add(String username, DraftStep step) {
        draftsByUsername.computeIfAbsent(username, k -> new CopyOnWriteArrayList<>()).add(step);
    }

    public void removeAt(String username, int index) {
        List<DraftStep> steps = draftsByUsername.get(username);
        if (steps != null && index >= 0 && index < steps.size()) {
            steps.remove(index);
        }
    }

    public void clear(String username) {
        draftsByUsername.remove(username);
    }
}
