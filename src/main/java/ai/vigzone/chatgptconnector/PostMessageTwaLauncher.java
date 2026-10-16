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
    private static final String TAG="VigzoneChannel";
    private final Activity activity;
    private final Uri origin;
    private final Handler main=new Handler(Looper.getMainLooper());
    private CustomTabsServiceConnection connection;
    private CustomTabsSession session;
    private final ChannelGate channelGate=new ChannelGate();
    private boolean destroyed, tabVisible=true;
    private final ValidationRetry validationRetry=new ValidationRetry();
    private void validateOrigin(){
        if(destroyed||!tabVisible||session==null||channelGate.isValidated()
                ||!validationRetry.begin(android.os.SystemClock.uptimeMillis()))return;
        android.util.Log.i(TAG,"Checking browser relationship");
        ConnectionReport.record(activity,"Validation attempts",Integer.toString(validationRetry.attempts()));
        session.validateRelationship(CustomTabsService.RELATION_USE_AS_ORIGIN,origin,null);
        // Retry both a failed result and a browser that never calls back. This
        // never grants trust locally, and cannot recurse from validation callbacks.
        main.postDelayed(this::validateOrigin,validationRetry.delayMillis());
    }
    public PostMessageTwaLauncher(Activity activity,Uri origin){super(activity);this.activity=activity;this.origin=origin;}
    @Override public void launch(TrustedWebActivityIntentBuilder builder,CustomTabsCallback quality,
            SplashScreenStrategy splash,Runnable completed,FallbackStrategy fallback){
        String provider=getProviderPackage();
        ConnectionReport.start(activity,provider);
        if(provider==null){super.launch(builder,quality,splash,completed,fallback);return;}
        CustomTabsCallback callback=new CustomTabsCallback(){
            private void channel(){
                if(session==null||destroyed||!channelGate.beginRequest())return;
                boolean accepted=session.requestPostMessageChannel(origin,origin,new Bundle());
                android.util.Log.i(TAG,"Channel request accepted="+accepted);
                ConnectionReport.record(activity,"Message channel",accepted?"Requested":"Browser rejected request");
                if(!accepted)channelGate.rejected();
            }
            @Override public void onNavigationEvent(int event,Bundle extras){
                android.util.Log.i(TAG,"Navigation event="+event);
                ConnectionReport.record(activity,"Navigation",Integer.toString(event));
                quality.onNavigationEvent(event,extras);
                if(event==NAVIGATION_STARTED){channelGate.navigationStarted();if(!channelGate.isValidated())validationRetry.reset();}
                if(event==NAVIGATION_FINISHED){channelGate.navigationFinished();validateOrigin();channel();}
                if(event==TAB_HIDDEN)tabVisible=false;
                if(event==TAB_SHOWN){if(!tabVisible)validationRetry.reset();tabVisible=true;channelGate.tabShown();validateOrigin();channel();}
            }
            @Override public void onRelationshipValidationResult(int relation,Uri requested,boolean valid,Bundle extras){
                android.util.Log.i(TAG,"Relationship="+relation+" valid="+valid+" expectedOrigin="+origin.equals(requested));
                if(relation==CustomTabsService.RELATION_USE_AS_ORIGIN)ConnectionReport.record(activity,"Trust check",valid?"Verified":"Not verified by browser");
                quality.onRelationshipValidationResult(relation,requested,valid,extras);
                if(relation==CustomTabsService.RELATION_USE_AS_ORIGIN&&origin.equals(requested)){channelGate.validated(valid);channel();}
            }
            @Override public void onMessageChannelReady(Bundle extras){
                android.util.Log.i(TAG,"Channel ready");
                ConnectionReport.record(activity,"Message channel","Ready");
                quality.onMessageChannelReady(extras);
                if(channelGate.isValidated())try{send(new org.json.JSONObject().put("connector","vigzone-chatgpt").put("version",1));}catch(org.json.JSONException ignored){}
            }
            @Override public void onPostMessage(String message,Bundle extras){
                if(!channelGate.isValidated()||destroyed)return;
                ConnectionReport.record(activity,"Web request","Received");
                ChatGptConnector.get(activity).dispatch(activity,origin.buildUpon().path("/chat").build(),message,PostMessageTwaLauncher.this::send);
            }
        };
        connection=new CustomTabsServiceConnection(){
            @Override public void onCustomTabsServiceConnected(ComponentName name,CustomTabsClient client){
                android.util.Log.i(TAG,"Browser service connected");
                if(destroyed)return;client.warmup(0);session=client.newSession(callback,96375);
                if(session==null){fallback.launch(activity,builder,provider,completed);return;}
                validationRetry.reset();validateOrigin();
                new SharedPreferencesTokenStore(activity).store(Token.create(provider,activity.getPackageManager()));
                Runnable open=()->{
                    if(destroyed||activity.isFinishing())return;
                    TrustedWebActivityIntent intent=builder.build(session);FocusActivity.addToIntent(intent.getIntent(),activity);intent.launchTrustedWebActivity(activity);
                    if(completed!=null)completed.run();
                };
                if(splash!=null)splash.configureTwaBuilder(builder,session,open);else open.run();
            }
            @Override public void onServiceDisconnected(ComponentName name){session=null;channelGate.disconnected();}
        };
        if(!CustomTabsClient.bindCustomTabsService(activity,provider,connection)){connection=null;super.launch(builder,quality,splash,completed,fallback);}
    }
    private void send(org.json.JSONObject message){main.post(()->{if(!destroyed&&channelGate.isValidated()&&session!=null)ConnectionReport.record(activity,"Native message result",Integer.toString(session.postMessage(message.toString(),null)));});}
    @Override public void destroy(){destroyed=true;main.removeCallbacksAndMessages(null);if(connection!=null){try{activity.unbindService(connection);}catch(IllegalArgumentException ignored){}connection=null;}super.destroy();}
}
