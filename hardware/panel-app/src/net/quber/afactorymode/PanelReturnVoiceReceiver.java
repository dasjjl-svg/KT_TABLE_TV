package net.quber.afactorymode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

final class PanelReturnVoiceReceiver extends BroadcastReceiver {
    private static final String[] ACTIONS = {
        "kt.action.voicecommand.asr",
        "kt.action.kws.asr",
        "kt.action.kws.asr.partial",
        "kt.action.remocon.voice.result_otv",
        "kt.action.stt.response"
    };
    private static final String[] TEXT_KEYS = {
        "kwsText", "result", "text", "asrText", "speech", "query", "voiceText"
    };

    static IntentFilter intentFilter() {
        IntentFilter filter = new IntentFilter();
        for (String action : ACTIONS) filter.addAction(action);
        return filter;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null || PanelReturnLauncher.isForeground()) return;
        Bundle extras = intent.getExtras();
        if (extras == null || extras.isEmpty()) return;
        for (String key : TEXT_KEYS) {
            Object value = extras.get(key);
            if (value instanceof CharSequence && ReturnTriggerPolicy.matchesVoiceCommand(value.toString())) {
                PanelReturnLauncher.bringToFront(context, "voice_broadcast");
                return;
            }
        }
    }
}
