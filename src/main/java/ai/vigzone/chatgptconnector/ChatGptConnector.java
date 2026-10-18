package ai.vigzone.chatgptconnector;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAPublicKeySpec;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Open-source, on-device OAuth/Responses client. No hosted credential proxy. */
public final class ChatGptConnector {
    public interface Reply { void send(JSONObject value); }
    public interface CompletionListener { void completed(String accountId,JSONObject request,JSONObject result); }
    private volatile CompletionListener completionListener;
    public void setCompletionListener(CompletionListener listener){completionListener=listener;}
    interface Connections { HttpURLConnection open(URL target) throws IOException; }
    private static Connections connections=target->(HttpURLConnection)target.openConnection();
    private static ChatGptConnector instance;
    public static synchronized ChatGptConnector get(Context context){
        if(instance==null)instance=new ChatGptConnector(context.getApplicationContext());return instance;
    }
    private final Context context;
    private final SecureStore store;
    private final ExecutorService work=Executors.newFixedThreadPool(3);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean generating=new AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicLong loginGeneration=new java.util.concurrent.atomic.AtomicLong();
    private volatile HttpURLConnection active;
    private volatile boolean cancelled;
    private volatile String activeRequest="",activeScope="",loginScope="",loginError="";
    private volatile ServerSocket loginListener;
    private static final int CHAT_JOB=96376;
    private volatile Reply observer;
    private volatile String observerId="";
    private JSONObject generation;
    private long checkpointAt;
    private ChatGptConnector(Context context){this.context=context;store=new SecureStore(context);}
    static byte[] decode(String value){return Base64.decode(value,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    static String encode(byte[] value){return Base64.encodeToString(value,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    private static String random(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return encode(bytes);}
    private static String form(JSONObject values) throws Exception {
        StringBuilder body=new StringBuilder();java.util.Iterator<String> keys=values.keys();
        while(keys.hasNext()){String key=keys.next();if(body.length()>0)body.append('&');body.append(URLEncoder.encode(key,"UTF-8")).append('=').append(URLEncoder.encode(values.getString(key),"UTF-8"));}return body.toString();
    }
    private static HttpURLConnection connect(String url,String method,String bearer,String body,boolean encoded) throws Exception {
        URL target=new URL(url);
        if(!"https".equals(target.getProtocol())||!("auth.openai.com".equals(target.getHost())||"api.openai.com".equals(target.getHost())))throw new SecurityException();
        HttpURLConnection connection=connections.open(target);connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(url.endsWith("/responses")?300000:60000);connection.setRequestMethod(method);
        if(bearer!=null)connection.setRequestProperty("Authorization","Bearer "+bearer);
        if(body!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type",encoded?"application/x-www-form-urlencoded":"application/json");try(OutputStream out=connection.getOutputStream()){out.write(body.getBytes(StandardCharsets.UTF_8));}}
        return connection;
    }
    private static String read(InputStream input) throws Exception {
        if(input==null)return "";ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
        try(input){while((count=input.read(buffer))!=-1){if(data.size()+count>2*1024*1024)throw new IOException("Response exceeded the supported size.");data.write(buffer,0,count);}}return data.toString("UTF-8");
    }
    private static JSONObject json(HttpURLConnection connection) throws Exception {
        try{
            int code=connection.getResponseCode();
            if(code<200||code>=300){
                if(code==401||code==403)throw new SecurityException("ChatGPT access was not authorized. Reconnect an eligible account and approve plan usage.");
                if(code==429)throw new IOException("Your ChatGPT allowance is currently unavailable or exhausted. Check ChatGPT usage and try later.");
                throw new IOException("OpenAI could not complete this request (HTTP "+code+"). Please retry.");
            }
            return new JSONObject(read(connection.getInputStream()));
        }finally{connection.disconnect();}
    }
    private JSONObject record(String scope) throws Exception {return store.read("account:"+SecureStore.key(scope));}
    private synchronized JSONObject token(String scope) throws Exception {
        JSONObject item=record(scope);if(item==null)throw new SecurityException("Connect your ChatGPT account on this phone first.");
        if(item.optLong("expires_at")>System.currentTimeMillis()+60000)return item;
        String refresh=item.optString("refresh_token");if(refresh.isEmpty())throw new SecurityException("Your ChatGPT session expired. Connect again.");
        JSONObject response=json(connect("https://auth.openai.com/api/accounts/oauth/token","POST",null,form(new JSONObject()
                .put("grant_type","refresh_token").put("client_id",item.getString("client_id")).put("refresh_token",refresh).put("resource","https://api.openai.com/v1")),true));
        String scopes=response.optString("scope",item.optString("scope"));
        if(!Protocol.hasScope(scopes,"chatgpt.tokens.use.direct"))throw new SecurityException("Authorize ChatGPT plan usage before sending messages.");
        item.put("access_token",response.getString("access_token")).put("refresh_token",response.optString("refresh_token",refresh))
                .put("scope",scopes).put("expires_at",System.currentTimeMillis()+response.getLong("expires_in")*1000);
        store.write("account:"+SecureStore.key(scope),item);return item;
    }
    private synchronized JSONObject status(String scope) throws Exception {
        JSONObject item=record(scope),result=new JSONObject().put("ok",true).put("connected",item!=null).put("pending",scope.equals(loginScope)&&loginListener!=null);
        ConnectionReport.record(context,"Saved sign-in",item==null?"Not found for active Vigzone account":"Available on this phone");
        result.put("capabilities",new JSONObject().put("webSearch",true).put("vigzoneMemory",true).put("durableReplies",true).put("durableSelection",true));
        if(scope.equals(loginScope)&&!loginError.isEmpty())result.put("error",loginError);
        if(item!=null)result.put("identity",item.getString("sub")+":"+item.getString("client_id")).put("email",item.optString("email")).put("selectedModel",item.optString("active_model"));
        return result;
    }
    private synchronized JSONObject select(String scope,JSONObject request) throws Exception {
        JSONObject item=record(scope);String model=request.optString("model");
        if(item==null||!Protocol.equal(item.optString("sub")+":"+item.optString("client_id"),request.optString("expectedAccount")))throw new SecurityException("Confirm your connected ChatGPT account before choosing a model.");
        if(!model.matches("[A-Za-z0-9._:/-]{1,120}"))throw new IllegalArgumentException("Choose a model reported by your ChatGPT account.");
        item.put("active_model",model);store.write("account:"+SecureStore.key(scope),item);return status(scope);
    }
    private synchronized void beginLogin(Activity activity,String scope,Uri returnUrl) throws Exception {
        SecureStore.key(scope);if(loginListener!=null)throw new IllegalStateException("Complete or cancel the sign-in already open in your browser.");
        ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));listener.setSoTimeout(5*60*1000);loginListener=listener;loginScope=scope;loginError="";
        long generation=loginGeneration.incrementAndGet();
        String state=random(),nonce=random(),verifier=random(),redirect="http://127.0.0.1:"+listener.getLocalPort()+"/auth/callback";
        JSONObject saved=record(scope);String client=saved==null?"dynamic_agent_client":saved.getString("client_id");
        Uri.Builder authorization=Uri.parse("https://auth.openai.com/api/accounts/authorize").buildUpon()
                .appendQueryParameter("client_id",client).appendQueryParameter("ext_agent_host_id",store.host()).appendQueryParameter("response_type","code")
                .appendQueryParameter("redirect_uri",redirect).appendQueryParameter("scope","openid profile email offline_access resource.invoke chatgpt.tokens.use.direct")
                .appendQueryParameter("resource","https://api.openai.com/v1").appendQueryParameter("state",state).appendQueryParameter("nonce",nonce)
                .appendQueryParameter("code_challenge_method","S256").appendQueryParameter("code_challenge",encode(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))));
        if(saved==null)authorization.appendQueryParameter("agent_name_hint","Vigzone Android Connector");
        else if(!saved.optString("id_token").isEmpty())authorization.appendQueryParameter("id_token_hint",saved.getString("id_token"));
        work.execute(()->{
            try(listener){
                long deadline=System.currentTimeMillis()+5*60*1000;
                while(System.currentTimeMillis()<deadline){
                    try(Socket socket=listener.accept()){
                        socket.setSoTimeout(10000);BufferedReader input=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));String first=input.readLine();
                        if(first==null||first.length()>8192)continue;String[] parts=first.split(" ");
                        if(parts.length!=3||!"GET".equals(parts[0]))continue;
                        Uri callback=Uri.parse("http://127.0.0.1"+parts[1]);
                        if(!"/auth/callback".equals(callback.getPath())||!Protocol.equal(state,callback.getQueryParameter("state"))){respond(socket,"Sign-in could not be verified.",returnUrl);continue;}
                        try{
                            if(callback.getQueryParameter("error")!=null)throw new SecurityException("ChatGPT sign-in was cancelled or plan usage was not approved.");
                            String issued=callback.getQueryParameter("client_id");if(issued==null&&saved!=null)issued=client;
                            if(issued==null||!issued.startsWith("oaiapp_")||(saved!=null&&!Protocol.equal(client,issued)))throw new SecurityException("OpenAI did not return the registered client identity.");
                            String code=callback.getQueryParameter("code");if(code==null||code.length()>8192)throw new SecurityException("OpenAI did not return a valid sign-in code.");
                            JSONObject response=json(connect("https://auth.openai.com/api/accounts/oauth/token","POST",null,form(new JSONObject()
                                    .put("grant_type","authorization_code").put("client_id",issued).put("code",code).put("code_verifier",verifier).put("redirect_uri",redirect).put("resource","https://api.openai.com/v1")),true));
                            JSONObject claims=verify(response.getString("id_token"),issued,nonce);
                            if(saved!=null&&!Protocol.equal(saved.getString("sub"),claims.getString("sub")))throw new SecurityException("A different ChatGPT account signed in. Disconnect the previous account before switching.");
                            if(!Protocol.hasScope(response.optString("scope"),"chatgpt.tokens.use.direct"))throw new SecurityException("This account did not grant ChatGPT plan usage. Eligible Plus or Pro access is required.");
                            response.put("client_id",issued).put("sub",claims.getString("sub")).put("email",claims.optString("email"))
                                    .put("expires_at",System.currentTimeMillis()+response.getLong("expires_in")*1000);
                            if(saved!=null&&!saved.optString("active_model").isEmpty())response.put("active_model",saved.getString("active_model"));
                            synchronized(ChatGptConnector.this){if(generation!=loginGeneration.get())throw new SecurityException("This sign-in was cancelled. Start a new connection if needed.");store.write("account:"+SecureStore.key(scope),response);}
                            respond(socket,"ChatGPT is connected. Return to Vigzone to choose a model.",returnUrl);
                        }catch(Exception error){loginError=safe(error);respond(socket,loginError,returnUrl);}break;
                    }
                }
            }catch(Exception error){loginError="Sign-in expired or was interrupted. Return to Vigzone and try again.";}
            finally{loginListener=null;}
        });
        main.post(()->{try{activity.startActivity(new Intent(Intent.ACTION_VIEW,authorization.build()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}catch(RuntimeException error){try{listener.close();}catch(IOException ignored){}}});
    }
    private static void respond(Socket socket,String message,Uri returnUrl) throws Exception {
        String safeText=message.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
        String html="<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>Vigzone ChatGPT connection</title><style>body{font:18px system-ui;padding:32px;background:#140c1c;color:#f5eef9}a{color:#c98cff}</style><h1>Vigzone Android Connector</h1><p>"+safeText+"</p><a href='"+returnUrl.toString().replace("&","&amp;").replace("'","%27")+"'>Return to Vigzone</a>";
        byte[] bytes=html.getBytes(StandardCharsets.UTF_8);OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'\r\nConnection: close\r\nContent-Length: "+bytes.length+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));out.write(bytes);out.flush();
    }
    private static JSONObject verify(String jwt,String client,String nonce) throws Exception {
        String[] parts=jwt.split("\\.");if(parts.length!=3)throw new SecurityException("OpenAI identity could not be verified.");
        JSONObject header=new JSONObject(new String(decode(parts[0]),StandardCharsets.UTF_8));
        if(!"RS256".equals(header.optString("alg")))throw new SecurityException("Unsupported OpenAI identity signature.");
        JSONArray keys=json(connect("https://auth.openai.com/.well-known/jwks.json","GET",null,null,false)).getJSONArray("keys");
        JSONObject key=null;for(int i=0;i<keys.length();i++){JSONObject candidate=keys.getJSONObject(i);if(header.getString("kid").equals(candidate.optString("kid"))&&"RSA".equals(candidate.optString("kty"))){key=candidate;break;}}
        if(key==null)throw new SecurityException("OpenAI signing key could not be verified.");
        PublicKey publicKey=KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(new BigInteger(1,decode(key.getString("n"))),new BigInteger(1,decode(key.getString("e")))));
        Signature signature=Signature.getInstance("SHA256withRSA");signature.initVerify(publicKey);signature.update((parts[0]+"."+parts[1]).getBytes(StandardCharsets.US_ASCII));
        if(!signature.verify(decode(parts[2])))throw new SecurityException("OpenAI identity signature was invalid.");
        JSONObject claims=new JSONObject(new String(decode(parts[1]),StandardCharsets.UTF_8));Protocol.identity(claims,client,nonce,System.currentTimeMillis()/1000);return claims;
    }
    private static String safe(Exception error){return error instanceof SecurityException||error instanceof IllegalArgumentException||error instanceof IllegalStateException||error instanceof IOException?String.valueOf(error.getMessage()):"The ChatGPT connection could not complete. Please reconnect or retry.";}
    private android.app.job.JobScheduler jobs(){return (android.app.job.JobScheduler)context.getSystemService(Context.JOB_SCHEDULER_SERVICE);}
    private synchronized JSONObject generation() throws Exception {
        if(generation==null)generation=store.read("generation");return generation;
    }
    private synchronized JSONObject snapshot(String scope) throws Exception {
        JSONObject completed=store.read("reply:"+SecureStore.key(scope));if(completed!=null)return completed;
        JSONObject job=generation();if(job==null||!scope.equals(job.optString("scope")))return null;
        if("running".equals(job.optString("state"))&&!generating.get()){
            finishInterrupted("Android stopped the previous generation. The saved partial reply is kept below; retry to continue.");
            return store.read("reply:"+SecureStore.key(scope));
        }
        JSONObject request=job.getJSONObject("request");
        return new JSONObject().put("state",job.optString("state")).put("content",job.optString("content"))
                .put("requestId",request.getString("requestId")).put("conversationId",request.optString("conversationId"))
                .put("model",request.optString("model"));
    }
    private synchronized void enqueue(String scope,String id,JSONObject request,Reply reply) throws Exception {
        if(!request.optString("requestId").matches("[A-Za-z0-9_-]{1,120}")||request.optString("conversationId").isEmpty())throw new IllegalArgumentException("Choose a chat before sending a response request.");
        Protocol.input(request);
        JSONObject job=generation();
        if(job!=null||generating.get())throw new IllegalStateException("A ChatGPT reply is still running. Return to its chat to see progress.");
        if(store.read("reply:"+SecureStore.key(scope))!=null)throw new IllegalStateException("Recover the previous reply before sending another request.");
        JSONObject account=record(scope);
        if(account==null||!Protocol.equal(account.optString("sub")+":"+account.optString("client_id"),request.optString("expectedAccount")))
            throw new SecurityException("Confirm your connected ChatGPT account before sending.");
        generation=new JSONObject().put("scope",scope).put("request",request).put("state","queued").put("content","");
        store.write("generation",generation);observer=reply;observerId=id;checkpointAt=0;
        android.content.ComponentName service=new android.content.ComponentName(context,ChatJobService.class);
        android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putString("requestId",request.getString("requestId"));
        android.app.job.JobInfo.Builder builder=new android.app.job.JobInfo.Builder(CHAT_JOB,service)
                .setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY).setExtras(extras);
        if(android.os.Build.VERSION.SDK_INT>=31)builder.setExpedited(true);else builder.setOverrideDeadline(0);
        int accepted=jobs().schedule(builder.build());
        if(accepted!=android.app.job.JobScheduler.RESULT_SUCCESS&&android.os.Build.VERSION.SDK_INT>=31)
            accepted=jobs().schedule(new android.app.job.JobInfo.Builder(CHAT_JOB,service).setRequiredNetworkType(android.app.job.JobInfo.NETWORK_TYPE_ANY).setExtras(extras).setOverrideDeadline(0).build());
        if(accepted!=android.app.job.JobScheduler.RESULT_SUCCESS){generation=null;store.write("generation",null);throw new IOException("Android could not schedule this response. Please retry.");}
        reply.send(snapshot(scope).put("id",id).put("event","snapshot"));
    }
    void runScheduled(Runnable finished){
        work.execute(()->{
            try{
                JSONObject job; synchronized(this){job=generation();}
                if(job==null)return;
                if(!"queued".equals(job.optString("state"))){finishInterrupted("Android interrupted this response. Your saved partial reply is retained; retry to continue.");return;}
                String scope=job.getString("scope");JSONObject request=job.getJSONObject("request");
                JSONObject result=chat(scope,observerId,request,value->{Reply target=observer;if(target!=null)target.send(value);});
                CompletionListener listener=completionListener;
                if(listener!=null)try{listener.completed(scope,request,result);}catch(RuntimeException ignored){/* Notification delivery cannot discard a saved reply. */}
                Reply target=observer;if(target!=null)target.send(result.put("id",observerId));
                synchronized(this){generation=null;store.write("generation",null);}
            }catch(Exception error){try{finishInterrupted(safe(error));}catch(Exception ignored){}}
            finally{main.post(finished);}
        });
    }
    private synchronized void progress(String content) throws Exception {
        if(generation==null)return;boolean first=generation.optString("content").isEmpty()&&!content.isEmpty();generation.put("state","running").put("content",content);
        long now=android.os.SystemClock.elapsedRealtime();
        if(first||now-checkpointAt>=2000){store.write("generation",generation);checkpointAt=now;}
    }
    private synchronized void finishInterrupted(String error) throws Exception {
        JSONObject job=generation();if(job==null)return;
        JSONObject request=job.getJSONObject("request");String scope=job.getString("scope");
        if(record(scope)!=null&&store.read("reply:"+SecureStore.key(scope))==null){
            JSONObject result=new JSONObject().put("ok",false).put("state","interrupted").put("error",error)
                    .put("content",job.optString("content")).put("requestId",request.getString("requestId"))
                    .put("conversationId",request.optString("conversationId")).put("model",request.optString("model"));
            store.write("reply:"+SecureStore.key(scope),result);
            Reply target=observer;if(target!=null)target.send(new JSONObject(result.toString()).put("id",observerId));
        }
        generation=null;store.write("generation",null);
    }
    void interruptScheduled(){
        cancelled=true;if(active!=null)active.disconnect();
        try{finishInterrupted("Android paused this response before completion. The saved partial reply is retained; retry to continue.");}catch(Exception ignored){}
    }
    synchronized void interruptScheduled(String requestId){
        try{JSONObject job=generation();if(job!=null&&requestId.equals(job.getJSONObject("request").optString("requestId")))interruptScheduled();}catch(Exception ignored){}
    }
    private synchronized void disconnect(String scope) throws Exception {
        if(scope.equals(loginScope)){loginGeneration.incrementAndGet();if(loginListener!=null)loginListener.close();}
        if(scope.equals(activeScope)){cancelled=true;if(active!=null)active.disconnect();}
        JSONObject job=generation();if(job!=null&&scope.equals(job.optString("scope"))){jobs().cancel(CHAT_JOB);generation=null;store.write("generation",null);}
        store.write("account:"+SecureStore.key(scope),null);store.write("reply:"+SecureStore.key(scope),null);
    }
    public void dispatch(Activity activity,Uri returnUrl,String raw,Reply reply){
        if(raw==null||raw.length()>512000)return;
        try{
            JSONObject request=new JSONObject(raw);String id=request.getString("id"),method=request.getString("method");JSONObject parameters=request.optJSONObject("params");
            if(parameters==null)parameters=new JSONObject();String scope=parameters.getString("accountId");SecureStore.key(scope);
            JSONObject args=parameters;
            if("cancel".equals(method)){
                synchronized(this){JSONObject job=generation();if(job!=null&&scope.equals(job.optString("scope"))&&args.optString("requestId").equals(job.getJSONObject("request").optString("requestId"))){jobs().cancel(CHAT_JOB);interruptScheduled();}}
                reply.send(new JSONObject().put("id",id).put("ok",true));return;
            }
            work.execute(()->{try{
                JSONObject result;
                switch(method){
                    case "status":result=status(scope);break;
                    case "select":result=select(scope,args);break;
                    case "login":beginLogin(activity,scope,returnUrl);result=new JSONObject().put("ok",true).put("pending",true);break;
                    case "disconnect":disconnect(scope);result=new JSONObject().put("ok",true);break;
                    case "models":{
                        JSONObject account=token(scope);JSONArray available=json(connect("https://api.openai.com/v1/models","GET",account.getString("access_token"),null,false)).optJSONArray("models"),models=new JSONArray();
                        if(available!=null)for(int i=0;i<available.length();i++){JSONObject item=available.getJSONObject(i);if("list".equals(item.optString("visibility"))&&!item.optString("slug").isEmpty())models.put(new JSONObject().put("id",item.getString("slug")).put("name",item.optString("display_name",item.getString("slug"))));}
                        result=new JSONObject().put("ok",true).put("models",models);break;
                    }
                    case "recover":{JSONObject cached=snapshot(scope);result=new JSONObject().put("ok",true).put("reply",cached==null?JSONObject.NULL:cached);break;}
                    case "watch":{
                        JSONObject cached=snapshot(scope);
                        if(cached==null||!args.optString("requestId").equals(cached.optString("requestId")))throw new IOException("This response is no longer running. Any saved partial reply remains in your chat; retry to continue.");
                        result=new JSONObject(cached.toString());
                        if("running".equals(result.optString("state"))||"queued".equals(result.optString("state")))result.put("event","snapshot");
                        break;
                    }
                    case "ack":{String key="reply:"+SecureStore.key(scope);JSONObject cached=store.read(key);if(cached!=null&&args.optString("requestId").equals(cached.optString("requestId")))store.write(key,null);result=new JSONObject().put("ok",true);break;}
                    case "chat":enqueue(scope,id,args,reply);return;
                    default:throw new IllegalArgumentException("Unsupported connector request.");
                }
                reply.send(result.put("id",id));
            }catch(Exception error){try{reply.send(new JSONObject().put("id",id).put("ok",false).put("error",safe(error)));}catch(JSONException ignored){}}});
        }catch(Exception ignored){/* Ignore malformed/unscoped messages without exposing credentials. */}
    }
    private JSONObject chat(String scope,String id,JSONObject request,Reply reply) throws Exception {
        if(!generating.compareAndSet(false,true))throw new IllegalStateException("A ChatGPT response is already in progress.");
        try{
            if(store.read("reply:"+SecureStore.key(scope))!=null)throw new IllegalStateException("Recover the previous completed ChatGPT reply before sending another request.");
            synchronized(this){
                if(generation==null||!scope.equals(generation.optString("scope"))||!request.optString("requestId").equals(generation.getJSONObject("request").optString("requestId")))throw new IOException("Request cancelled.");
                cancelled=false;activeRequest=request.getString("requestId");activeScope=scope;progress("");
            }
            JSONObject account=token(scope);String identity=account.getString("sub")+":"+account.getString("client_id");
            if(!Protocol.equal(identity,request.optString("expectedAccount")))throw new SecurityException("Confirm your connected ChatGPT account again before sending.");
            String model=request.getString("model");if(!model.matches("[A-Za-z0-9._:/-]{1,120}"))throw new IllegalArgumentException("Choose a model reported by your ChatGPT account.");
            JSONObject payload=Protocol.payload(request,model);
            activeRequest=request.getString("requestId");activeScope=scope;
            active=connect("https://api.openai.com/v1/responses","POST",account.getString("access_token"),payload.toString(),false);
            boolean searchUnavailable=false;
            if(active.getResponseCode()!=200&&payload.has("tools")){
                int code=active.getResponseCode();
                if(code==400||code==403||code==422){
                    String detail=read(active.getErrorStream()).toLowerCase(java.util.Locale.ROOT);
                    if(detail.contains("web_search")||detail.contains("web search")||detail.contains("tool_choice")||detail.contains("tools")){
                        active.disconnect();if(cancelled)throw new IOException("Request cancelled.");
                        payload.remove("tools");payload.remove("tool_choice");
                        payload.put("instructions",payload.getString("instructions")+" Built-in web search is unavailable for this request. Use only supplied Vigzone live sources for fresh facts and cite their actual URLs. If no live evidence is supplied, explicitly say current facts could not be verified. Never claim a successful search.");
                        active=connect("https://api.openai.com/v1/responses","POST",account.getString("access_token"),payload.toString(),false);
                        searchUnavailable=true;
                    }
                }
            }
            if(active.getResponseCode()!=200){json(active);throw new IOException("ChatGPT did not start a response.");}
            ResponseEvents events=new ResponseEvents();StringBuilder data=new StringBuilder();boolean searchAnnounced=false;
            try(BufferedReader input=new BufferedReader(new InputStreamReader(active.getInputStream(),StandardCharsets.UTF_8))){
                String line;while((line=input.readLine())!=null){
                    if(cancelled)throw new IOException("Request cancelled.");
                    if(line.startsWith("data:")){if(data.length()>0)data.append('\n');data.append(line.substring(5).trim());if(data.length()>2*1024*1024)throw new IOException("ChatGPT event exceeded the supported size.");continue;}
                    if(!line.isEmpty()||data.length()==0)continue;
                    if("[DONE]".contentEquals(data)){data.setLength(0);break;}
                    JSONObject event=new JSONObject(data.toString());data.setLength(0);events.accept(event);
                    if("response.output_text.delta".equals(event.optString("type"))){String delta=event.optString("delta");progress(events.text.toString());reply.send(new JSONObject().put("id",id).put("event","delta").put("offset",events.text.length()-delta.length()).put("delta",delta));}
                    if(events.searched&&!searchAnnounced){searchAnnounced=true;reply.send(new JSONObject().put("id",id).put("event","status").put("status","searching"));}
                    if(events.complete)break;
                }
            }
            if(cancelled)throw new IOException("Request cancelled.");
            JSONObject result=events.result().put("contextSources",Protocol.publicSources(request.optJSONArray("sources"))).put("webSearchUnavailable",searchUnavailable).put("ok",true).put("model",model).put("requestId",activeRequest).put("conversationId",request.optString("conversationId"));
            synchronized(this){JSONObject stillConnected=record(scope);if(stillConnected==null||!identity.equals(stillConnected.optString("sub")+":"+stillConnected.optString("client_id")))throw new SecurityException("This ChatGPT account was disconnected before the reply finished.");store.write("reply:"+SecureStore.key(scope),result);}return result;
        }finally{if(active!=null)active.disconnect();active=null;activeRequest="";activeScope="";generating.set(false);}
    }
}
