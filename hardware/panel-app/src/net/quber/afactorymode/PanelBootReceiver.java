package net.quber.afactorymode;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class PanelBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction()))) return;
        if (!MaintenancePolicy.PACKAGE.equals(context.getPackageName())
                || !context.getSharedPreferences("maintenance", Context.MODE_PRIVATE).getBoolean("enabled", true)) return;
        MaintenanceService.start(context, false);
        Intent panel = new Intent(context, AFactoryModeMainActivity.class);
        panel.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try { context.startActivity(panel); } catch (RuntimeException ignored) { }
    }
}
