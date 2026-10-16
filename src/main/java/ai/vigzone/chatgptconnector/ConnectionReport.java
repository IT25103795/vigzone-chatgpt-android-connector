package ai.vigzone.chatgptconnector;

import android.content.Context;

/** Local transport diagnostics only. Never record RPC payloads, accounts or tokens. */
final class ConnectionReport {
    static final String STORE="vigzone_connection_report";
    static void record(Context context,String key,String value) {
        context.getSharedPreferences(STORE,Context.MODE_PRIVATE).edit().putString(key,value).apply();
    }
    static void start(Context context,String provider) {
        context.getSharedPreferences(STORE,Context.MODE_PRIVATE).edit().clear().apply();
        record(context,"Browser",provider==null?"No compatible browser":provider);
        if(provider!=null)try {
            record(context,"Browser version",context.getPackageManager().getPackageInfo(provider,0).versionName);
        }catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
        record(context,"Trust check","Waiting");
        record(context,"Message channel","Waiting");
    }
}
