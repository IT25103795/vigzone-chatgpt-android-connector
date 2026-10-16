package ai.vigzone.chatgptconnector;

import android.app.Activity;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.browser.customtabs.*;
import androidx.browser.trusted.*;
import com.google.androidbrowserhelper.trusted.*;
import com.google.androidbrowserhelper.trusted.splashscreens.SplashScreenStrategy;

/** Keeps the existing ABH Activity lifecycle; adds an origin-validated message session. */
public final class PostMessageTwaLauncher extends TwaLauncher {
    private final Activity activity;
    private final Uri origin;
    private final Handler main=new Handler(Looper.getMainLooper());
    private CustomTabsServiceConnection connection;
    private CustomTabsSession session;
    private boolean validated,navigated,destroyed;
    public PostMessageTwaLauncher(Activity activity,Uri origin){super(activity);this.activity=activity;this.origin=origin;}
    @Override public void launch(TrustedWebActivityIntentBuilder builder,CustomTabsCallback quality,
            SplashScreenStrategy splash,Runnable completed,FallbackStrategy fallback){
        String provider=getProviderPackage();
        if(provider==null){super.launch(builder,quality,splash,completed,fallback);return;}
        CustomTabsCallback callback=new CustomTabsCallback(){
            private void channel(){if(validated&&navigated&&session!=null&&!destroyed)session.requestPostMessageChannel(origin,origin,new Bundle());}
            @Override public void onNavigationEvent(int event,Bundle extras){
                quality.onNavigationEvent(event,extras);
                if(event==NAVIGATION_STARTED)navigated=false;
                if(event==NAVIGATION_FINISHED){navigated=true;channel();}
            }
            @Override public void onRelationshipValidationResult(int relation,Uri requested,boolean valid,Bundle extras){
                quality.onRelationshipValidationResult(relation,requested,valid,extras);
                if(relation==CustomTabsService.RELATION_USE_AS_ORIGIN&&origin.equals(requested)){validated=valid;channel();}
            }
            @Override public void onMessageChannelReady(Bundle extras){
                quality.onMessageChannelReady(extras);
                if(validated)try{send(new org.json.JSONObject().put("connector","vigzone-chatgpt").put("version",1));}catch(org.json.JSONException ignored){}
            }
            @Override public void onPostMessage(String message,Bundle extras){
                if(!validated||destroyed)return;
                ChatGptConnector.get(activity).dispatch(activity,origin.buildUpon().path("/chat").build(),message,PostMessageTwaLauncher.this::send);
            }
        };
        connection=new CustomTabsServiceConnection(){
            @Override public void onCustomTabsServiceConnected(ComponentName name,CustomTabsClient client){
                if(destroyed)return;client.warmup(0);session=client.newSession(callback,96375);
                if(session==null){fallback.launch(activity,builder,provider,completed);return;}
                session.validateRelationship(CustomTabsService.RELATION_USE_AS_ORIGIN,origin,null);
                new SharedPreferencesTokenStore(activity).store(Token.create(provider,activity.getPackageManager()));
                Runnable open=()->{
                    if(destroyed||activity.isFinishing())return;
                    TrustedWebActivityIntent intent=builder.build(session);FocusActivity.addToIntent(intent.getIntent(),activity);intent.launchTrustedWebActivity(activity);
                    if(completed!=null)completed.run();
                };
                if(splash!=null)splash.configureTwaBuilder(builder,session,open);else open.run();
            }
            @Override public void onServiceDisconnected(ComponentName name){session=null;validated=false;}
        };
        if(!CustomTabsClient.bindCustomTabsService(activity,provider,connection)){connection=null;super.launch(builder,quality,splash,completed,fallback);}
    }
    private void send(org.json.JSONObject message){main.post(()->{if(!destroyed&&validated&&session!=null)session.postMessage(message.toString(),null);});}
    @Override public void destroy(){destroyed=true;if(connection!=null){try{activity.unbindService(connection);}catch(IllegalArgumentException ignored){}connection=null;}super.destroy();}
}
