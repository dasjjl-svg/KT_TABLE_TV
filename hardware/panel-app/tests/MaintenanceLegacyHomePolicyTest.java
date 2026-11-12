package net.quber.afactorymode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class MaintenanceLegacyHomePolicyTest {
    private static final String PACKAGE = "net.quber.afactorymode";
    private static final String ACTIVITY = PACKAGE + ".AFactoryModeMainActivity";
    private static int checks;

    public static void main(String[] args) {
        MaintenanceLegacyHomePolicy.Entry own = entry(PACKAGE, ACTIVITY, true, true);
        MaintenanceLegacyHomePolicy.Entry foreignOther = entry("com.example.other", "com.example.other.View", false, false);
        expect(Collections.<MaintenanceLegacyHomePolicy.Entry>emptyList(), 0);
        expect(Arrays.asList(foreignOther), 0);
        expect(Arrays.asList(own), 1);
        expect(Arrays.asList(own, own), 2);
        expect(Arrays.asList(foreignOther, own, own), 2);
        expect(Arrays.asList(own, foreignOther, own), 2);
        expect(Collections.nCopies(16, own), 16);
        reject(Collections.nCopies(17, own), "legacy_home_count");
        for (String owner : new String[]{"com.kt.gigagenie.launcher", "dev.homepanel.community", "com.example.other", PACKAGE + ".extra"}) {
            for (boolean exact : new boolean[]{false, true}) {
                MaintenanceLegacyHomePolicy.Entry foreignHome = entry(owner, ACTIVITY, true, exact);
                reject(Arrays.asList(foreignHome), "prior_home_preference");
                reject(Arrays.asList(own, foreignHome), "prior_home_preference");
                reject(Arrays.asList(foreignHome, own), "prior_home_preference");
            }
        }
        for (MaintenanceLegacyHomePolicy.Entry wrong : Arrays.asList(
                entry(PACKAGE, ACTIVITY, true, false),
                entry(PACKAGE, ACTIVITY + "Extra", true, true),
                entry(PACKAGE, "com.example.other.View", true, true))) {
            reject(Arrays.asList(own, wrong), "prior_home_preference");
        }
        for (boolean exact : new boolean[]{false, true}) {
            for (String name : new String[]{ACTIVITY, PACKAGE + ".OtherActivity"}) {
                MaintenanceLegacyHomePolicy.Entry otherPurpose = entry(PACKAGE, name, false, exact);
                reject(Arrays.asList(own, otherPurpose), "legacy_home_other_preference");
                reject(Arrays.asList(otherPurpose, own), "legacy_home_other_preference");
            }
        }
        List<MaintenanceLegacyHomePolicy.Entry> many = new ArrayList<MaintenanceLegacyHomePolicy.Entry>(Collections.nCopies(254, foreignOther));
        many.add(own); many.add(own);
        expect(many, 2);
        many.add(foreignOther);
        reject(many, "legacy_home_arguments");
        reject(null, "legacy_home_arguments");
        reject(Arrays.asList((MaintenanceLegacyHomePolicy.Entry) null), "legacy_home_entry");
        reject(Arrays.asList(entry(null, ACTIVITY, true, true)), "legacy_home_entry");
        reject(Arrays.asList(entry(PACKAGE, null, true, true)), "legacy_home_entry");
        reject(Arrays.asList(entry("", ACTIVITY, true, true)), "legacy_home_entry");
        reject(Arrays.asList(entry(PACKAGE, "", true, true)), "legacy_home_entry");
        subset(Arrays.asList("home", "home"), Arrays.asList("home"), true);
        subset(Arrays.asList("home", "home"), Arrays.asList("home", "home"), true);
        subset(Arrays.asList("home-a", "home-b", "home-a"), Arrays.asList("home-b", "home-a"), true);
        subset(Collections.nCopies(16, "home"), Collections.nCopies(16, "home"), true);
        subset(Arrays.asList("home"), Arrays.asList("home", "home"), false);
        subset(Arrays.asList("home-a", "home-b"), Arrays.asList("home-a", "home-a"), false);
        subset(Arrays.asList("home-a", "home-a"), Arrays.asList("home-b"), false);
        subset(Arrays.asList("home-a"), Arrays.asList("home-a-extra"), false);
        subset(Arrays.asList("home-a"), Arrays.asList((String) null), false);
        subset(Arrays.asList((String) null), Arrays.asList("home"), false);
        subset(Arrays.asList(""), Arrays.asList(""), false);
        subset(Collections.<String>emptyList(), Arrays.asList("home"), false);
        subset(Arrays.asList("home"), Collections.<String>emptyList(), false);
        subset(Collections.nCopies(17, "home"), Arrays.asList("home"), false);
        subset(Arrays.asList("home"), Collections.nCopies(17, "home"), false);
        subset(null, Arrays.asList("home"), false);
        subset(Arrays.asList("home"), null, false);
        System.out.println("MaintenanceLegacyHomePolicyTest passed: " + checks + " checks");
    }

    private static MaintenanceLegacyHomePolicy.Entry entry(String owner, String name, boolean home, boolean exact) {
        return new MaintenanceLegacyHomePolicy.Entry(owner, name, home, exact);
    }

    private static void expect(List<MaintenanceLegacyHomePolicy.Entry> entries, int expected) {
        List<MaintenanceLegacyHomePolicy.Entry> before = new ArrayList<MaintenanceLegacyHomePolicy.Entry>(entries);
        checks++;
        if (MaintenanceLegacyHomePolicy.removableCount(PACKAGE, ACTIVITY, entries) != expected) throw new AssertionError("count");
        checks++;
        if (!before.equals(entries)) throw new AssertionError("input_changed");
    }

    private static void reject(List<MaintenanceLegacyHomePolicy.Entry> entries, String expected) {
        checks++;
        try {
            MaintenanceLegacyHomePolicy.removableCount(PACKAGE, ACTIVITY, entries);
            throw new AssertionError("accepted_" + expected);
        } catch (IllegalArgumentException error) {
            if (!expected.equals(error.getMessage())) throw new AssertionError(error);
        } catch (IllegalStateException error) {
            if (!expected.equals(error.getMessage())) throw new AssertionError(error);
        }
    }

    private static void subset(List<String> saved, List<String> current, boolean expected) {
        List<String> savedBefore = saved == null ? null : new ArrayList<String>(saved);
        List<String> currentBefore = current == null ? null : new ArrayList<String>(current);
        boolean accepted = false;
        try { MaintenanceLegacyHomePolicy.requireSubset(saved, current); accepted = true; }
        catch (IllegalArgumentException error) {
            if (!"legacy_home_evidence_changed".equals(error.getMessage())) throw new AssertionError(error);
        } catch (IllegalStateException error) {
            if (!"legacy_home_evidence_changed".equals(error.getMessage())) throw new AssertionError(error);
        }
        checks++;
        if (accepted != expected) throw new AssertionError("subset_result");
        checks++;
        if (saved != null && !savedBefore.equals(saved) || current != null && !currentBefore.equals(current)) {
            throw new AssertionError("subset_input_changed");
        }
    }
}
