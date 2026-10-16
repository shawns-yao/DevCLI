package com.devcli.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/** 写步骤独占波次；只读步骤保留已有资源冲突分波和有界并发。 */
public final class SingleWriterScheduling {
    private SingleWriterScheduling() { }

    public static <T> List<List<T>> split(List<List<T>> resourceWaves, Function<T, String> type) {
        List<List<T>> result = new ArrayList<>();
        for (List<T> wave : resourceWaves) {
            List<T> readers = new ArrayList<>();
            for (T item : wave) {
                String declared = type.apply(item);
                boolean readOnly = declared != null && switch (declared.toUpperCase(Locale.ROOT)) {
                    case "FILE_READ", "ANALYSIS", "PLANNING" -> true;
                    default -> false;
                };
                if (readOnly) {
                    readers.add(item);
                } else {
                    flush(result, readers);
                    result.add(List.of(item));
                }
            }
            flush(result, readers);
        }
        return List.copyOf(result);
    }

    private static <T> void flush(List<List<T>> result, List<T> readers) {
        if (!readers.isEmpty()) {
            result.add(List.copyOf(readers));
            readers.clear();
        }
    }
}
