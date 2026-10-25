import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class PasswordUtils {
    private static final int ITERATIONS = 600000;
    private static final int KEY_LENGTH = 256;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";    

    public static String hashPassword(String password) throws NoSuchAlgorithmException, InvalidKeySpecException {
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[16];
        random.nextBytes(salt);

        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
        byte[] hash = factory.generateSecret(spec).getEncoded();

        String saltBase64 = Base64.getEncoder().encodeToString(salt);
        String hashBase64 = Base64.getEncoder().encodeToString(hash);
        
        return saltBase64 + "$" + hashBase64;
    }

    public static String generatePassword() {
        String pass = UUID.randomUUID().toString().substring(26);
        return pass;
    }

    public static boolean checkPass(String userPass, String salt, String hashDB) throws NoSuchAlgorithmException, InvalidKeySpecException {
        byte[] saltBytes = Base64.getDecoder().decode(salt);
        PBEKeySpec spec = new PBEKeySpec(userPass.toCharArray(), saltBytes, ITERATIONS, KEY_LENGTH);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(ALGORITHM);
        
        byte[] hash = factory.generateSecret(spec).getEncoded();
        String hashUser = Base64.getEncoder().encodeToString(hash);
        return hashDB.equals(hashUser);
    }
}
 