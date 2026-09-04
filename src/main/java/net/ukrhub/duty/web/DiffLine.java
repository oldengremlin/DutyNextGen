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

import java.util.ArrayList;
import java.util.List;

/** Один рядок unified diff з CSS-класом для кольорового відображення. */
public record DiffLine(String text, String cssClass) {

    public static List<DiffLine> parse(String diff) {
        List<DiffLine> lines = new ArrayList<>();
        if (diff == null || diff.isBlank()) {
            return lines;
        }
        for (String line : diff.split("\n")) {
            lines.add(new DiffLine(line, classify(line)));
        }
        return lines;
    }

    private static String classify(String line) {
        if (line.startsWith("+++") || line.startsWith("---")) {
            return "diff-file";
        }
        if (line.startsWith("+")) {
            return "diff-add";
        }
        if (line.startsWith("-")) {
            return "diff-del";
        }
        if (line.startsWith("@@")) {
            return "diff-hunk";
        }
        if (line.startsWith("diff --git") || line.startsWith("index ")) {
            return "diff-meta";
        }
        return "diff-context";
    }
}
