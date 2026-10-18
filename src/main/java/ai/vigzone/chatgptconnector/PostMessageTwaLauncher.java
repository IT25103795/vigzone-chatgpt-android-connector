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
    public interface Host {
        void visibility(boolean visible);
        org.json.JSONObject request(Activity activity,org.json.JSONObject parameters) throws Exception;
    }
    private final Host host;
    private static final String TAG="VigzoneChannel";
    private final Activity activity;
    private final Uri origin;
    private final Handler main=new Handler(Looper.getMainLooper());
    private CustomTabsServiceConnection connection;
    private CustomTabsSession session;
    private final ChannelGate channelGate=new ChannelGate();
    private boolean destroyed, tabVisible=true, opened;
    private final ValidationRetry handshakeRetry=new ValidationRetry();
    private boolean webAcknowledged;
    private String handshakeNonce="";
    private Runnable requestChannel;
    private final Runnable recoverChannel=this::recover;
    private void recover(){
        if(destroyed||!tabVisible||webAcknowledged||session==null||!channelGate.isValidated())return;
        if(!handshakeRetry.begin(android.os.SystemClock.uptimeMillis())){
            ConnectionReport.record(activity,"Recovery state","Retry limit reached");return;
        }
        // Only a delayed recovery may replace a silent channel. Relationship
        // callbacks cannot trigger recursive channel requests.
        channelGate.recoverAfterNavigationDelay();
        ConnectionReport.record(activity,"Recovery state","Retrying channel");
        if(requestChannel!=null)requestChannel.run();
    }
    private void scheduleRecovery(){
        main.removeCallbacks(recoverChannel);
        if(!webAcknowledged&&tabVisible&&!destroyed){
            ConnectionReport.record(activity,"Recovery state","Scheduled");
            main.postDelayed(recoverChannel,Math.max(2000L,handshakeRetry.delayMillis()));
        }
    }
    private void newDocument(){
        webAcknowledged=false;handshakeNonce="";handshakeRetry.reset();
        main.removeCallbacks(recoverChannel);
        ConnectionReport.record(activity,"Page acknowledgment","Waiting");
    }
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
    public PostMessageTwaLauncher(Activity activity,Uri origin){this(activity,origin,null);}
    public PostMessageTwaLauncher(Activity activity,Uri origin,Host host){super(activity);this.activity=activity;this.origin=origin;this.host=host;}
    @Override public void launch(TrustedWebActivityIntentBuilder builder,CustomTabsCallback quality,
            SplashScreenStrategy splash,Runnable completed,FallbackStrategy fallback){
        String provider=getProviderPackage();
        ConnectionReport.start(activity,provider);
        if(provider==null){super.launch(builder,quality,splash,completed,fallback);return;}
        CustomTabsCallback callback=new CustomTabsCallback(){
            { requestChannel=this::channel; }
            private void channel(){
                if(session==null||destroyed||!channelGate.beginRequest())return;
                handshakeNonce=java.util.UUID.randomUUID().toString();
                boolean accepted=session.requestPostMessageChannel(origin,origin,new Bundle());
                android.util.Log.i(TAG,"Channel request accepted="+accepted);
                ConnectionReport.record(activity,"Message channel",accepted?"Requested":"Browser rejected request");
                if(!accepted)channelGate.rejected();
                ConnectionReport.record(activity,"Channel retries",Integer.toString(handshakeRetry.attempts()));
                scheduleRecovery();
            }
            @Override public void onNavigationEvent(int event,Bundle extras){
                android.util.Log.i(TAG,"Navigation event="+event);
                ConnectionReport.record(activity,"Navigation",Integer.toString(event));
                quality.onNavigationEvent(event,extras);
                if(event==NAVIGATION_STARTED){
                    android.content.SharedPreferences report=activity.getSharedPreferences(ConnectionReport.STORE,Activity.MODE_PRIVATE);
                    ConnectionReport.record(activity,"Before navigation",report.getString("Message channel","Waiting")+" / page "+report.getString("Page acknowledgment","Waiting")+" / retries "+report.getString("Channel retries","0"));
                    newDocument();channelGate.navigationStarted();
                    if(!channelGate.isValidated())validationRetry.reset();
                    // Do not wait forever for a completion callback. External
                    // intents and stalled optional resources may never emit one.
                    scheduleRecovery();
                }
                if(event==NAVIGATION_FINISHED){
                    channelGate.navigationFinished();validateOrigin();channel();
                    // An early channel may belong to the previous document.
                    // Probe it first: a live current page can acknowledge again
                    // without replacing a port carrying an in-progress request.
                    webAcknowledged=false;handshakeRetry.reset();hello();scheduleRecovery();
                }
                if(event==NAVIGATION_ABORTED||event==NAVIGATION_FAILED){channelGate.navigationFinished();scheduleRecovery();}
                if(event==TAB_HIDDEN){tabVisible=false;if(host!=null)host.visibility(false);main.removeCallbacks(recoverChannel);ConnectionReport.record(activity,"Recovery state","Paused while tab hidden");}
                if(event==TAB_SHOWN){if(host!=null)host.visibility(true);if(!tabVisible)validationRetry.reset();tabVisible=true;channelGate.tabShown();validateOrigin();channel();webAcknowledged=false;handshakeNonce=java.util.UUID.randomUUID().toString();handshakeRetry.reset();hello();scheduleRecovery();}
            }
            @Override public void onRelationshipValidationResult(int relation,Uri requested,boolean valid,Bundle extras){
                android.util.Log.i(TAG,"Relationship="+relation+" valid="+valid+" expectedOrigin="+origin.equals(requested));
                if(relation==CustomTabsService.RELATION_USE_AS_ORIGIN)ConnectionReport.record(activity,"Trust check",valid?"Verified":"Not verified by browser");
                quality.onRelationshipValidationResult(relation,requested,valid,extras);
                if(relation==CustomTabsService.RELATION_USE_AS_ORIGIN&&origin.equals(requested)){
                    channelGate.validated(valid);channel();
                    if(valid&&!webAcknowledged)scheduleRecovery();
                }
            }
            @Override public void onMessageChannelReady(Bundle extras){
                android.util.Log.i(TAG,"Channel ready");
                ConnectionReport.record(activity,"Message channel","Ready");
                quality.onMessageChannelReady(extras);
                hello();
            }
            @Override public void onPostMessage(String message,Bundle extras){
                if(!channelGate.isValidated()||destroyed)return;
                if(message==null||message.length()>512000)return;
                try{
                    org.json.JSONObject control=new org.json.JSONObject(message);
                    if(host!=null&&"app".equals(control.optString("method"))){
                        String id=control.getString("id");
                        try{send(host.request(activity,control.getJSONObject("params")).put("id",id));}
                        catch(Exception failed){send(new org.json.JSONObject().put("id",id).put("ok",false).put("error","The app action could not complete. Please retry."));}
                        return;
                    }
                    if("vigzone-chatgpt".equals(control.optString("connector"))&&"probe".equals(control.optString("event"))){webAcknowledged=false;handshakeNonce=java.util.UUID.randomUUID().toString();handshakeRetry.reset();hello();scheduleRecovery();return;}
                    if("vigzone-chatgpt".equals(control.optString("connector"))&&"ready".equals(control.optString("event"))){
                        if(!handshakeNonce.isEmpty()&&handshakeNonce.equals(control.optString("nonce"))){
                            webAcknowledged=true;main.removeCallbacks(recoverChannel);
                            ConnectionReport.record(activity,"Page acknowledgment","Received");
                            ConnectionReport.record(activity,"Recovery state","Page connected");
                        }
                        return;
                    }
                }catch(org.json.JSONException ignored){}
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
                if(opened){channelGate.tabShown();webAcknowledged=false;handshakeRetry.reset();scheduleRecovery();return;}
                new SharedPreferencesTokenStore(activity).store(Token.create(provider,activity.getPackageManager()));
                Runnable open=()->{
                    if(destroyed||activity.isFinishing())return;
                    TrustedWebActivityIntent intent=builder.build(session);FocusActivity.addToIntent(intent.getIntent(),activity);intent.launchTrustedWebActivity(activity);
                    opened=true;
                    if(completed!=null)completed.run();
                };
                if(splash!=null)splash.configureTwaBuilder(builder,session,open);else open.run();
            }
            @Override public void onServiceDisconnected(ComponentName name){session=null;channelGate.disconnected();ConnectionReport.record(activity,"Recovery state","Browser service disconnected");}
            @Override public void onBindingDied(ComponentName name){
                session=null;channelGate.disconnected();
                if(destroyed)return;
                try{activity.unbindService(this);}catch(IllegalArgumentException ignored){}
                main.postDelayed(()->{if(!destroyed)CustomTabsClient.bindCustomTabsService(activity,provider,this);},2000);
            }
        };
        if(!CustomTabsClient.bindCustomTabsService(activity,provider,connection)){connection=null;super.launch(builder,quality,splash,completed,fallback);}
    }
    private void hello(){
        if(channelGate.isValidated()&&!handshakeNonce.isEmpty())try{
            send(new org.json.JSONObject().put("connector","vigzone-chatgpt").put("version",1).put("lifecycle",host==null?0:1).put("nonce",handshakeNonce));
        }catch(org.json.JSONException ignored){}
    }
    private void send(org.json.JSONObject message){main.post(()->{if(!destroyed&&channelGate.isValidated()&&session!=null){int result=session.postMessage(message.toString(),null);ConnectionReport.record(activity,"Native message result",Integer.toString(result));if(result!=CustomTabsService.RESULT_SUCCESS){webAcknowledged=false;scheduleRecovery();}}});}
    @Override public void destroy(){destroyed=true;if(host!=null)host.visibility(false);ConnectionReport.record(activity,"Recovery state","Launcher destroyed");main.removeCallbacksAndMessages(null);if(connection!=null){try{activity.unbindService(connection);}catch(IllegalArgumentException ignored){}connection=null;}super.destroy();}
}
