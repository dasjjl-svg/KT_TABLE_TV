package net.quber.afactorymode;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

final class MaintenanceLegacyHomePolicy {
    static final class Entry {
        final String packageName, activityName;
        final boolean home, exactFilter;

        Entry(String packageName, String activityName, boolean home, boolean exactFilter) {
            this.packageName = packageName; this.activityName = activityName;
            this.home = home; this.exactFilter = exactFilter;
        }
    }

    static int removableCount(String bootstrapPackage, String activityName, List<Entry> entries) {
        if (bootstrapPackage == null || bootstrapPackage.length() == 0 || activityName == null || activityName.length() == 0
                || entries == null || entries.size() > 256) throw new IllegalArgumentException("legacy_home_arguments");
        int count = 0;
        for (Entry entry : entries) {
            if (entry == null || entry.packageName == null || entry.packageName.length() == 0
                    || entry.activityName == null || entry.activityName.length() == 0) {
                throw new IllegalArgumentException("legacy_home_entry");
            }
            boolean ours = bootstrapPackage.equals(entry.packageName);
            boolean exact = ours && activityName.equals(entry.activityName) && entry.home && entry.exactFilter;
            if (entry.home && !exact) throw new IllegalStateException("prior_home_preference");
            if (ours && !exact) throw new IllegalStateException("legacy_home_other_preference");
            if (exact && ++count > 16) throw new IllegalStateException("legacy_home_count");
        }
        return count;
    }

    static void requireSubset(List<String> saved, List<String> current) {
        if (saved == null || current == null || saved.size() < 1 || saved.size() > 16
                || current.size() < 1 || current.size() > 16) throw new IllegalArgumentException("legacy_home_evidence_changed");
        Map<String, Integer> remaining = new HashMap<String, Integer>();
        for (String item : saved) {
            if (item == null || item.length() == 0 || item.length() > 8192) throw new IllegalArgumentException("legacy_home_evidence_changed");
            Integer count = remaining.get(item);
            remaining.put(item, count == null ? 1 : count + 1);
        }
        for (String item : current) {
            Integer count = remaining.get(item);
            if (count == null || count < 1) throw new IllegalStateException("legacy_home_evidence_changed");
            remaining.put(item, count - 1);
        }
    }
}
