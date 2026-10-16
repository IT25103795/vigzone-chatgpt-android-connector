package ai.vigzone.chatgptconnector;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Tokens never leave app-private, Keystore-encrypted storage. */
final class SecureStore {
    private final SharedPreferences preferences;
    private static final String ALIAS="vigzone-chatgpt-connector-v1";
    SecureStore(Context context){preferences=context.getSharedPreferences(ALIAS,Context.MODE_PRIVATE);}
    synchronized String host(){
        String value=preferences.getString("host",null);
        if(value==null){value="urn:uuid:"+UUID.randomUUID();if(!preferences.edit().putString("host",value).commit())throw new IllegalStateException("Could not persist this device identity.");}
        return value;
    }
    static String key(String scope) throws Exception {
        if(scope==null||scope.trim().isEmpty()||scope.length()>256)throw new SecurityException("Sign in to your app first.");
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8)),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
    }
    private SecretKey secret() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(ALIAS)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();
        }
        return (SecretKey)store.getKey(ALIAS,null);
    }
    synchronized JSONObject read(String name) throws Exception {
        String value=preferences.getString(name,null);if(value==null)return null;
        byte[] packed=Base64.decode(value,Base64.NO_WRAP);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,secret(),new GCMParameterSpec(128,java.util.Arrays.copyOfRange(packed,0,12)));
        cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        return new JSONObject(new String(cipher.doFinal(java.util.Arrays.copyOfRange(packed,12,packed.length)),StandardCharsets.UTF_8));
    }
    synchronized void write(String name,JSONObject value) throws Exception {
        if(value==null){preferences.edit().remove(name).commit();return;}
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secret());cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
        byte[] encrypted=cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        byte[] packed=new byte[12+encrypted.length];System.arraycopy(cipher.getIV(),0,packed,0,12);System.arraycopy(encrypted,0,packed,12,encrypted.length);
        if(!preferences.edit().putString(name,Base64.encodeToString(packed,Base64.NO_WRAP)).commit())throw new IllegalStateException("Could not save the connection securely.");
    }
}
