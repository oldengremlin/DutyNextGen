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

import net.ukrhub.duty.domain.DutyExchangeProposal;
import net.ukrhub.duty.domain.DutyExchangeStatus;
import net.ukrhub.duty.domain.DutyExchangeStep;
import net.ukrhub.duty.domain.DutyMark;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Читання й запис текстового формату пропозиції обміну — той самий
 * підхід, що й {@code RotationTemplateFormat}: секції {@code [ Name ] },
 * людинозрозуміло, git-diff придатно.
 */
public final class DutyExchangeFormat {

    private DutyExchangeFormat() {
    }

    public static DutyExchangeProposal parse(int id, String content) {
        String initiatorName = "";
        String initiatorUsername = "";
        String counterpartName = "";
        DutyExchangeStatus status = DutyExchangeStatus.PENDING;
        LocalDateTime createdAt = null;
        List<DutyExchangeStep> steps = new ArrayList<>();

        String section = "";
        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.stripTrailing();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (line.matches("^\\[\\s*\\S+\\s*\\].*")) {
                section = line.replaceAll("^\\[\\s*(\\S+)\\s*\\].*$", "$1").toLowerCase();
                String inlineValue = line.replaceAll("^\\[\\s*\\S+\\s*\\]\\s*", "").strip();
                switch (section) {
                    case "initiator" -> initiatorName = inlineValue;
                    case "initiatorusername" -> initiatorUsername = inlineValue;
                    case "counterpart" -> counterpartName = inlineValue;
                    case "status" -> status = DutyExchangeStatus.valueOf(inlineValue);
                    case "createdat" -> createdAt = LocalDateTime.parse(inlineValue);
                    default -> {
                        // "steps" — значення на наступних рядках, не в заголовку секції
                    }
                }
                continue;
            }
            if (section.equals("steps")) {
                steps.add(parseStep(line.strip()));
            }
        }

        return new DutyExchangeProposal(id, initiatorName, initiatorUsername, counterpartName, steps, status, createdAt);
    }

    private static DutyExchangeStep parseStep(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Хибний рядок кроку обміну: " + line);
        }
        DutyMark type = DutyMark.valueOf(parts[0]);
        return new DutyExchangeStep(type, LocalDate.parse(parts[1]), LocalDate.parse(parts[2]));
    }

    public static String serialize(DutyExchangeProposal proposal) {
        StringBuilder sb = new StringBuilder();
        sb.append("[ Initiator ] ").append(proposal.initiatorName()).append('\n');
        sb.append("[ InitiatorUsername ] ").append(proposal.initiatorUsername()).append('\n');
        sb.append("[ Counterpart ] ").append(proposal.counterpartName()).append('\n');
        sb.append("[ Status ] ").append(proposal.status().name()).append('\n');
        sb.append("[ CreatedAt ] ").append(proposal.createdAt()).append('\n');
        sb.append("\n[ Steps ]\n");
        for (DutyExchangeStep step : proposal.steps()) {
            sb.append(step.type().name()).append(' ')
                    .append(step.initiatorDate()).append(' ')
                    .append(step.counterpartDate()).append('\n');
        }
        return sb.toString();
    }
}
