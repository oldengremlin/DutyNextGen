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
package net.ukrhub.duty.template;

import net.ukrhub.duty.domain.RotationTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Читання й запис текстового формату шаблону ротації — той самий підхід,
 * що й {@code DutyScheduleFormat}: секції {@code [ Name ]} / {@code [ Pattern ] },
 * людинозрозуміло, git-diff придатно. На відміну від графіка, тут рядок
 * відповідає слоту (не дню) — так само, як його бачить і редагує
 * адміністратор: один рядок {@code [ Pattern ] } = один черговий, символ
 * = день (D/W/-).
 */
public final class RotationTemplateFormat {

    private RotationTemplateFormat() {
    }

    public static RotationTemplate parse(int id, String content) {
        String name = "";
        List<String> rows = new ArrayList<>();

        String section = "";
        for (String rawLine : content.split("\n", -1)) {
            String line = rawLine.stripTrailing();
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (line.matches("^\\[\\s*\\S+\\s*\\].*")) {
                section = line.replaceAll("^\\[\\s*(\\S+)\\s*\\].*$", "$1").toLowerCase();
                continue;
            }
            switch (section) {
                case "name" -> name = line.strip();
                case "pattern" -> rows.add(line.strip());
                default -> {
                    // невідома/порожня секція — ігноруємо
                }
            }
        }

        return new RotationTemplate(id, name, rows);
    }

    public static String serialize(RotationTemplate template) {
        StringBuilder sb = new StringBuilder();
        sb.append("[ Name ]\n");
        sb.append(template.name()).append('\n');
        sb.append("\n[ Pattern ]\n");
        for (String row : template.rows()) {
            sb.append(row).append('\n');
        }
        return sb.toString();
    }
}
