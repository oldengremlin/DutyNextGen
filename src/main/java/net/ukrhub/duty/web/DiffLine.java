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
