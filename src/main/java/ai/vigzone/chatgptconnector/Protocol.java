package ai.vigzone.chatgptconnector;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONArray;
import org.json.JSONObject;

/** Platform-independent validation, also exercised by JVM tests. */
public final class Protocol {
    private Protocol(){}
    public static boolean equal(String expected,String actual){
        return expected!=null&&actual!=null&&MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),actual.getBytes(StandardCharsets.UTF_8));
    }
    static boolean hasScope(String granted,String required){return Arrays.asList(granted.trim().split("\\s+")).contains(required);}
    static void identity(JSONObject claims,String client,String nonce,long now) throws Exception {
        Object audience=claims.opt("aud");boolean matches=client.equals(audience);
        if(audience instanceof JSONArray){for(int i=0;i<((JSONArray)audience).length();i++)matches|=client.equals(((JSONArray)audience).optString(i));if(((JSONArray)audience).length()>1&&!client.equals(claims.optString("azp")))matches=false;}
        if(!"https://auth.openai.com".equals(claims.optString("iss"))||!matches||claims.optString("sub").isEmpty()
                ||claims.optLong("exp",0)<now-5||claims.optLong("iat",Long.MAX_VALUE)>now+60||!equal(nonce,claims.optString("nonce")))
            throw new SecurityException("OpenAI identity could not be verified.");
    }
    static JSONArray input(JSONObject request) throws Exception {
        String text=request.optString("text");if(text.trim().isEmpty()||text.length()>64000)throw new IllegalArgumentException("Send a text message of up to 64,000 characters.");
        JSONArray result=new JSONArray(),history=request.optJSONArray("history");
        if(history!=null)for(int i=Math.max(0,history.length()-16);i<history.length();i++){
            JSONObject message=history.getJSONObject(i);String role=message.optString("role"),content=message.optString("content");
            if((role.equals("user")||role.equals("assistant"))&&!content.trim().isEmpty())result.put(new JSONObject().put("role",role).put("content",content.substring(0,Math.min(content.length(),8000))));
        }
        return result.put(new JSONObject().put("role","user").put("content",text));
    }
}
