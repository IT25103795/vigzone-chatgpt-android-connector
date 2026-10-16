package ai.vigzone.chatgptconnector;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** User-opened, copyable diagnostics; no account information or network access. */
public final class ConnectionStatusActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String version="unknown";
        try{version=getPackageManager().getPackageInfo(getPackageName(),0).versionName;}
        catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
        StringBuilder report=new StringBuilder("Vigzone Android connection\nApp: ").append(version)
                .append("\nAndroid: ").append(android.os.Build.VERSION.RELEASE)
                .append("\nDevice: ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL);
        android.content.SharedPreferences prefs=getSharedPreferences(ConnectionReport.STORE,MODE_PRIVATE);
        for(String key:new String[]{"Browser","Browser version","Trust check","Validation attempts","Navigation","Message channel","Native message result","Web request"})
            report.append('\n').append(key).append(": ").append(prefs.getString(key,"Not recorded"));
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        int pad=(int)(24*getResources().getDisplayMetrics().density);content.setPadding(pad,pad,pad,pad);
        content.setBackgroundColor(0xff17131d);
        TextView text=new TextView(this);text.setText(report.toString());text.setTextSize(17);text.setTextColor(0xfff5eef9);
        text.setTextIsSelectable(true);content.addView(text);
        TextView privacy=new TextView(this);privacy.setText("\nThis report stays on your phone. It contains no chats, account details or sign-in credentials.\n");
        privacy.setTextColor(0xffc8b8d2);content.addView(privacy);
        Button copy=new Button(this);copy.setText("Copy connection report");
        copy.setOnClickListener(view->{
            ((ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Vigzone connection",report.toString()));
            copy.setText("Report copied");
        });content.addView(copy);
        Button back=new Button(this);back.setText("Return to Vigzone");back.setOnClickListener(view->finish());content.addView(back);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(0xff17131d);scroll.addView(content);setContentView(scroll);
    }
}
