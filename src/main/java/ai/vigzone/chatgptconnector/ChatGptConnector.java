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
    private static ChatGptConnector instance;
    public static synchronized ChatGptConnector get(Context context){
        if(instance==null)instance=new ChatGptConnector(context.getApplicationContext());return instance;
    }
    private final Context context;
    private final SecureStore store;
    private final ExecutorService work=Executors.newFixedThreadPool(3);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean generating=new AtomicBoolean();
    private volatile HttpURLConnection active;
    private volatile String activeRequest="",activeScope="",loginScope="",loginError="";
    private volatile ServerSocket loginListener;
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
        HttpURLConnection connection=(HttpURLConnection)target.openConnection();connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(60000);connection.setRequestMethod(method);
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
    private JSONObject status(String scope) throws Exception {
        JSONObject item=record(scope),result=new JSONObject().put("ok",true).put("connected",item!=null).put("pending",scope.equals(loginScope)&&loginListener!=null);
        if(scope.equals(loginScope)&&!loginError.isEmpty())result.put("error",loginError);
        if(item!=null)result.put("identity",item.getString("sub")+":"+item.getString("client_id")).put("email",item.optString("email"));
        return result;
    }
    private void beginLogin(Activity activity,String scope,Uri returnUrl) throws Exception {
        SecureStore.key(scope);if(loginListener!=null)throw new IllegalStateException("Complete or cancel the sign-in already open in your browser.");
        ServerSocket listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"));listener.setSoTimeout(5*60*1000);loginListener=listener;loginScope=scope;loginError="";
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
                            store.write("account:"+SecureStore.key(scope),response);respond(socket,"ChatGPT is connected. Return to Vigzone to choose a model.",returnUrl);
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
    private static String safe(Exception error){return error instanceof SecurityException||error instanceof IllegalArgumentException||error instanceof IOException?String.valueOf(error.getMessage()):"The ChatGPT connection could not complete. Please reconnect or retry.";}
    public void dispatch(Activity activity,Uri returnUrl,String raw,Reply reply){
        if(raw==null||raw.length()>200000)return;
        try{
            JSONObject request=new JSONObject(raw);String id=request.getString("id"),method=request.getString("method");JSONObject parameters=request.optJSONObject("params");
            if(parameters==null)parameters=new JSONObject();String scope=parameters.getString("accountId");SecureStore.key(scope);
            JSONObject args=parameters;
            if("cancel".equals(method)){if(scope.equals(activeScope)&&args.optString("requestId").equals(activeRequest)&&active!=null)active.disconnect();reply.send(new JSONObject().put("id",id).put("ok",true));return;}
            work.execute(()->{try{
                JSONObject result;
                switch(method){
                    case "status":result=status(scope);break;
                    case "login":beginLogin(activity,scope,returnUrl);result=new JSONObject().put("ok",true).put("pending",true);break;
                    case "disconnect":if(scope.equals(loginScope)&&loginListener!=null)loginListener.close();store.write("account:"+SecureStore.key(scope),null);store.write("reply:"+SecureStore.key(scope),null);result=new JSONObject().put("ok",true);break;
                    case "models":{
                        JSONObject account=token(scope);JSONArray available=json(connect("https://api.openai.com/v1/models","GET",account.getString("access_token"),null,false)).optJSONArray("models"),models=new JSONArray();
                        if(available!=null)for(int i=0;i<available.length();i++){JSONObject item=available.getJSONObject(i);if("list".equals(item.optString("visibility"))&&!item.optString("slug").isEmpty())models.put(new JSONObject().put("id",item.getString("slug")).put("name",item.optString("display_name",item.getString("slug"))));}
                        result=new JSONObject().put("ok",true).put("models",models);break;
                    }
                    case "recover":{JSONObject cached=store.read("reply:"+SecureStore.key(scope));result=new JSONObject().put("ok",true).put("reply",cached==null?JSONObject.NULL:cached);break;}
                    case "ack":{String key="reply:"+SecureStore.key(scope);JSONObject cached=store.read(key);if(cached!=null&&args.optString("requestId").equals(cached.optString("requestId")))store.write(key,null);result=new JSONObject().put("ok",true);break;}
                    case "chat":result=chat(scope,id,args,reply);break;
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
            JSONObject account=token(scope);String identity=account.getString("sub")+":"+account.getString("client_id");
            if(!Protocol.equal(identity,request.optString("expectedAccount")))throw new SecurityException("Confirm your connected ChatGPT account again before sending.");
            String model=request.getString("model");if(!model.matches("[A-Za-z0-9._:/-]{1,120}"))throw new IllegalArgumentException("Choose a model reported by your ChatGPT account.");
            JSONObject payload=new JSONObject().put("model",model).put("input",Protocol.input(request)).put("store",false).put("stream",true);
            activeRequest=request.getString("requestId");activeScope=scope;
            active=connect("https://api.openai.com/v1/responses","POST",account.getString("access_token"),payload.toString(),false);
            if(active.getResponseCode()!=200){json(active);throw new IOException("ChatGPT did not start a response.");}
            StringBuilder text=new StringBuilder();boolean complete=false;StringBuilder data=new StringBuilder();
            try(BufferedReader input=new BufferedReader(new InputStreamReader(active.getInputStream(),StandardCharsets.UTF_8))){
                String line;while((line=input.readLine())!=null){
                    if(line.startsWith("data:")){data.append(line.substring(5).trim());if(data.length()>2*1024*1024)throw new IOException("ChatGPT event exceeded the supported size.");continue;}
                    if(!line.isEmpty()||data.length()==0)continue;
                    if("[DONE]".contentEquals(data)){data.setLength(0);break;}
                    JSONObject event=new JSONObject(data.toString());data.setLength(0);String type=event.optString("type");
                    if("response.output_text.delta".equals(type)){
                        String delta=event.optString("delta");text.append(delta);if(text.length()>2*1024*1024)throw new IOException("ChatGPT reply exceeded the supported size.");
                        reply.send(new JSONObject().put("id",id).put("event","delta").put("delta",delta));
                    }else if("response.completed".equals(type)){complete=true;break;}
                    else if("response.failed".equals(type)||"response.incomplete".equals(type)||"error".equals(type))throw new IOException("ChatGPT could not finish this response. Check your subscription allowance and retry.");
                }
            }
            if(!complete||text.toString().trim().isEmpty())throw new IOException("ChatGPT's response was interrupted before completion. Please retry.");
            JSONObject result=new JSONObject().put("ok",true).put("content",text.toString()).put("model",model).put("requestId",activeRequest).put("conversationId",request.optString("conversationId"));
            store.write("reply:"+SecureStore.key(scope),result);return result;
        }finally{if(active!=null)active.disconnect();active=null;activeRequest="";activeScope="";generating.set(false);}
    }
}
