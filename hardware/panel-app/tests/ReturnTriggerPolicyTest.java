package net.quber.afactorymode;

public final class ReturnTriggerPolicyTest {
    private static int checks;

    public static void main(String[] args) {
        digitKeyMapping();
        digitSequenceDetection();
        voiceCommandMatching();
        voicePackageAllowlist();
        returnRequestValidation();
        accessibilitySettingsList();
        System.out.println("ReturnTriggerPolicyTest passed: " + checks + " checks");
    }

    private static void digitKeyMapping() {
        for (int i = 0; i <= 9; i++) {
            check(ReturnTriggerPolicy.digitFromKeyCode(ReturnTriggerPolicy.KEYCODE_0 + i) == ('0' + i), "row_digit_" + i);
            check(ReturnTriggerPolicy.digitFromKeyCode(ReturnTriggerPolicy.KEYCODE_NUMPAD_0 + i) == ('0' + i), "numpad_digit_" + i);
        }
        check(ReturnTriggerPolicy.digitFromKeyCode(3) == -1, "home_key_not_digit");
        check(ReturnTriggerPolicy.digitFromKeyCode(4) == -1, "back_key_not_digit");
        check(ReturnTriggerPolicy.digitFromKeyCode(23) == -1, "dpad_center_not_digit");
    }

    private static void digitSequenceDetection() {
        ReturnTriggerPolicy.DigitDetector detector = new ReturnTriggerPolicy.DigitDetector();
        check(!detector.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1000L, false), "first_9");
        check(!detector.onKeyEvent(1, ReturnTriggerPolicy.KEYCODE_9, 0, 1050L, false), "action_up_ignored");
        check(!detector.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 1, 1100L, false), "repeat_ignored");
        check(!detector.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1400L, false), "second_9");
        check(!detector.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_NUMPAD_9, 0, 1800L, false), "third_numpad_9");
        check(detector.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 2200L, false), "fourth_9_triggers");

        // Cooldown blocks immediate re-trigger
        check(!detector.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 2500L, false), "cooldown_1");
        check(!detector.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 2600L, false), "cooldown_2");
        check(!detector.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 2700L, false), "cooldown_3");
        check(!detector.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 2800L, false), "cooldown_4");

        // After cooldown, normal 9999 works again
        check(!detector.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 6000L, false), "post_cooldown_1");
        check(!detector.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 6300L, false), "post_cooldown_2");
        check(!detector.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 6600L, false), "post_cooldown_3");
        check(detector.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 6900L, false), "post_cooldown_4_triggers");

        // Other digit or non-digit resets sequence
        ReturnTriggerPolicy.DigitDetector resetter = new ReturnTriggerPolicy.DigitDetector();
        check(!resetter.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1000L, false), "r1");
        check(!resetter.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1200L, false), "r2");
        check(!resetter.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1400L, false), "r3");
        check(!resetter.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_0, 0, 1500L, false), "other_digit_resets");
        check(!resetter.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1600L, false), "r_after_0");

        check(!resetter.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1700L, false), "r_key_2");
        check(!resetter.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1800L, false), "r_key_3");
        check(!resetter.onKeyEvent(0, 23, 0, 1900L, false), "non_digit_resets");
        check(!resetter.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 2000L, false), "r_after_non_digit");

        // Foreground suppresses and clears buffer
        ReturnTriggerPolicy.DigitDetector fg = new ReturnTriggerPolicy.DigitDetector();
        check(!fg.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1000L, false), "fg1");
        check(!fg.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1200L, false), "fg2");
        check(!fg.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1400L, false), "fg3");
        check(!fg.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1500L, true), "fg_suppresses");
        check(!fg.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1600L, false), "fg_cleared_buffer");

        // Gap timeout resets; sliding window preserves fresh 4-key burst after stale leading 9
        ReturnTriggerPolicy.DigitDetector timing = new ReturnTriggerPolicy.DigitDetector();
        check(!timing.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1000L, false), "t_stale");
        check(!timing.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 3400L, false), "t_b1");
        check(!timing.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 4400L, false), "t_b2");
        check(!timing.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 5200L, false), "t_b3_drops_stale");
        check(timing.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 5600L, false), "t_b4_triggers_sliding");

        ReturnTriggerPolicy.DigitDetector gap = new ReturnTriggerPolicy.DigitDetector();
        check(!gap.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1000L, false), "g1");
        check(!gap.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 1500L, false), "g2");
        check(!gap.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 2000L, false), "g3");
        check(!gap.onKeyEvent(0, ReturnTriggerPolicy.KEYCODE_9, 0, 4600L, false), "gap_exceeded_resets");
    }

    private static void voiceCommandMatching() {
        String[] accepted = {
            "홈패널로 복귀",
            "홈패널로복귀",
            "홈 패널로 복귀",
            "홈 패널 로 복귀.",
            "\"홈패널로 복귀\"",
            "기가지니 홈패널로 복귀",
            "기가지니, 홈패널로 복귀해줘",
            "기가지니야 홈패널로 복귀해주세요",
            "지니야 홈 패널로 복귀해",
            "헤이 구글 홈패널로 복귀 실행",
            "홈패널로 복귀 이동!"
        };
        for (String phrase : accepted) {
            check(ReturnTriggerPolicy.matchesVoiceCommand(phrase), "voice_accept_" + phrase);
        }

        String[] rejected = {
            null,
            "",
            "   ",
            "홈 화면으로 이동",
            "채널 9999",
            "홈패널",
            "복귀",
            "홈패널로 복귀하는 방법 알려줘",
            "홈패널로 복귀라고 말해 보세요",
            "기가지니 오늘 날씨 알려줘",
            "홈패널로 복귀\u0000",
            "홈패널로 복귀 " + "가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다라마바사아자차카타파하"
        };
        for (String phrase : rejected) {
            check(!ReturnTriggerPolicy.matchesVoiceCommand(phrase), "voice_reject_" + phrase);
        }
    }

    private static void voicePackageAllowlist() {
        check(ReturnTriggerPolicy.allowedVoiceSourcePackage("com.kt.gigagenie.launcher"), "kt_launcher");
        check(ReturnTriggerPolicy.allowedVoiceSourcePackage("com.kt.gigagenie.voicerecognition"), "kt_voice");
        check(ReturnTriggerPolicy.allowedVoiceSourcePackage("com.kt.gigagenie.mc"), "kt_mc");
        check(ReturnTriggerPolicy.allowedVoiceSourcePackage("com.kt.gigagenie.ollehtv"), "kt_ollehtv");
        check(!ReturnTriggerPolicy.allowedVoiceSourcePackage("dev.homepanel.community"), "reject_self");
        check(!ReturnTriggerPolicy.allowedVoiceSourcePackage("net.quber.afactorymode"), "reject_bootstrap");
        check(!ReturnTriggerPolicy.allowedVoiceSourcePackage("com.android.settings"), "reject_settings");
        check(!ReturnTriggerPolicy.allowedVoiceSourcePackage(null), "reject_null_pkg");
    }

    private static void returnRequestValidation() {
        String id = "0123456789abcdef0123456789abcdef";
        check(ReturnTriggerPolicy.validReturnRequest(1, "return_home", id, "web", 1800000000L), "valid_web");
        check(ReturnTriggerPolicy.validReturnRequest(1, "return_home", id, "ha", 1800000000L), "valid_ha");
        check(ReturnTriggerPolicy.validReturnRequest(1, "return_home", id, "voice", 1800000000L), "valid_voice");
        check(ReturnTriggerPolicy.validReturnRequest(1, "return_home", id, "cli", 1800000000L), "valid_cli");
        check(!ReturnTriggerPolicy.validReturnRequest(2, "return_home", id, "web", 1800000000L), "bad_schema");
        check(!ReturnTriggerPolicy.validReturnRequest(1, "update", id, "web", 1800000000L), "bad_action");
        check(!ReturnTriggerPolicy.validReturnRequest(1, "return_home", "short", "web", 1800000000L), "bad_id");
        check(!ReturnTriggerPolicy.validReturnRequest(1, "return_home", id, "unknown", 1800000000L), "bad_source");
        check(!ReturnTriggerPolicy.validReturnRequest(1, "return_home", id, "web", 0L), "bad_expiry");
    }

    private static void accessibilitySettingsList() {
        String own = ReturnTriggerPolicy.RETURN_SERVICE_COMPONENT;
        String other = "com.example.other/com.example.OtherService";
        check(own.equals(ReturnTriggerPolicy.addAccessibilityService(null, own)), "add_empty");
        check(own.equals(ReturnTriggerPolicy.addAccessibilityService("", own)), "add_blank");
        check((other + ":" + own).equals(ReturnTriggerPolicy.addAccessibilityService(other, own)), "add_preserve_other");
        check((other + ":" + own).equals(ReturnTriggerPolicy.addAccessibilityService(other + ":" + own, own)), "add_idempotent");
        check(ReturnTriggerPolicy.containsAccessibilityService(other + ":" + own, own), "contains_true");
        check(!ReturnTriggerPolicy.containsAccessibilityService(other, own), "contains_false");
        check(other.equals(ReturnTriggerPolicy.removeAccessibilityService(other + ":" + own, own)), "remove_keeps_other");
        check("".equals(ReturnTriggerPolicy.removeAccessibilityService(own, own)), "remove_only_own");
        check(other.equals(ReturnTriggerPolicy.removeAccessibilityService(other, own)), "remove_absent_noop");
        try {
            ReturnTriggerPolicy.addAccessibilityService("", "bad;component");
            throw new AssertionError("invalid_component_accepted");
        } catch (IllegalArgumentException expected) {
            check("accessibility_component".equals(expected.getMessage()), "invalid_component_rejected");
        }
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
}
