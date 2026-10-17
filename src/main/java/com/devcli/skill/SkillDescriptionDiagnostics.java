package com.devcli.skill;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/** 词面重叠仅提示描述歧义，不判断语义冲突，也不参与权限或选择。 */
final class SkillDescriptionDiagnostics {
    private static final Pattern WORDS = Pattern.compile("\\p{IsHan}+|[\\p{L}\\p{N}]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "and", "or", "to", "for", "of", "in", "on", "with", "this", "that",
            "use", "when", "user", "asks", "skill", "is", "are", "be", "it");

    private SkillDescriptionDiagnostics() { }

    static void inspect(List<Skill> skills, BiConsumer<Skill, Skill> similar) {
        List<Set<String>> tokens = skills.stream().map(skill -> tokens(skill.description())).toList();
        for (int i = 0; i < skills.size(); i++) {
            for (int j = i + 1; j < skills.size(); j++) {
                Set<String> left = tokens.get(i);
                Set<String> right = tokens.get(j);
                if (Math.min(left.size(), right.size()) < 4) continue;
                long common = left.stream().filter(right::contains).count();
                if ((double) common / (left.size() + right.size() - common) >= 0.75) {
                    similar.accept(skills.get(i), skills.get(j));
                }
            }
        }
    }

    private static Set<String> tokens(String description) {
        Set<String> tokens = new HashSet<>();
        var matcher = WORDS.matcher(description.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            if (Character.UnicodeScript.of(word.codePointAt(0)) == Character.UnicodeScript.HAN) {
                int[] characters = word.codePoints().toArray();
                for (int i = 1; i < characters.length; i++) {
                    tokens.add(new String(characters, i - 1, 2));
                }
            } else if (!STOP_WORDS.contains(word)) {
                tokens.add(word);
            }
        }
        return tokens;
    }
}
