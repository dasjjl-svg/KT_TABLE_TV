package net.quber.afactorymode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DeviceInitParser {
    static final class Result {
        final List<String> lines;
        final int imports;
        Result(List<String> lines, int imports) {
            this.lines = Collections.unmodifiableList(lines);
            this.imports = imports;
        }
    }

    private DeviceInitParser() {}

    static Result parse(String text, int importBudget) {
        List<String> result = new ArrayList<String>();
        int imports = 0;
        int services = 0;
        String selected = null;
        if (text == null || text.length() > 65536) return new Result(result, 0);
        for (String raw : text.split("\n", -1)) {
            int comment = raw.indexOf('#');
            String line = (comment < 0 ? raw : raw.substring(0, comment)).trim();
            if (line.length() == 0) continue;
            if (line.length() > 4096) { selected = null; continue; }
            String[] words = line.split("[\\t ]+");
            if ("import".equals(words[0])) {
                selected = null;
                if (words.length == 2 && imports < Math.min(10, Math.max(0, importBudget))
                        && safePath(words[1]) && words[1].startsWith("/")) {
                    result.add("import=" + words[1]);
                    imports++;
                }
            } else if ("service".equals(words[0])) {
                selected = null;
                if (services < 8 && words.length >= 3 && safeName(words[1]) && safePath(words[2])) {
                    String executable = words[2];
                    String name = executable.substring(executable.lastIndexOf('/') + 1);
                    if ("fork.bin".equals(name)) {
                        selected = words[1];
                        result.add("service=" + selected + " executable=" + executable);
                        services++;
                    }
                }
            } else if ("on".equals(words[0])) {
                selected = null;
            } else if (selected != null && "user".equals(words[0]) && words.length == 2 && safeName(words[1])) {
                result.add("service." + selected + ".user=" + words[1]);
            } else if (selected != null && "group".equals(words[0]) && words.length >= 2 && words.length <= 17) {
                StringBuilder groups = new StringBuilder();
                boolean valid = true;
                for (int i = 1; i < words.length; i++) {
                    if (!safeName(words[i])) { valid = false; break; }
                    if (groups.length() > 0) groups.append(' ');
                    groups.append(words[i]);
                }
                if (valid) result.add("service." + selected + ".group=" + groups);
            }
            if (result.size() >= 48) break;
        }
        return new Result(result, imports);
    }

    private static boolean safeName(String value) {
        return value.matches("[A-Za-z0-9_.-]{1,64}");
    }

    private static boolean safePath(String value) {
        if (value.length() > 192 || !value.matches("[A-Za-z0-9_./${}-]+")) return false;
        for (String part : value.split("/", -1)) if ("..".equals(part)) return false;
        return true;
    }
}
