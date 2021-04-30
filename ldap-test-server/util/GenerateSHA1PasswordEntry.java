import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import sun.misc.BASE64Decoder;
import sun.misc.BASE64Encoder;

public class GenerateSHA1PasswordEntry {

  private static final String ALGORITHM = "SHA";
  private static final String ENCALGORITHM = "{SSHA}";
  private static final int SHA1LENGTH = 20;

  private static String encode(String password,
      boolean twice) throws Exception {
    // use a random 8 byte salt
    byte[] salt = new byte[8];
    SecureRandom rnd = new SecureRandom();
    rnd.nextBytes(salt);

    return encode(password, salt, twice);
  }

  private static String encode(String password, byte[] salt,
      boolean twice) throws Exception {
    // Calculate hash value
    MessageDigest md = MessageDigest.getInstance(ALGORITHM);
    md.update(password.getBytes());
    md.update(salt);
    byte[] hashedBytes = md.digest();

    // Add hash bytes followed by salt at the end.
    // Hash value itself includes the salt (as expected) plus the encoded value has
    // the salt separately at the end so that decoder can first extract the salt.
    byte[] hashAndSalt = new byte[hashedBytes.length + salt.length];
    System.arraycopy(hashedBytes, 0, hashAndSalt, 0, hashedBytes.length);
    System.arraycopy(salt, 0, hashAndSalt, hashedBytes.length, salt.length);

    // Print out value in Base64 encoding
    BASE64Encoder base64encoder = new BASE64Encoder();
    String encodedPassword = ENCALGORITHM + base64encoder.encode(hashAndSalt);
    if (twice) {
      encodedPassword = base64encoder.encode(encodedPassword.getBytes("UTF-8"));
    }
    return encodedPassword;
  }

  private static void check(String password,
      String encodedPassword) throws Exception {
    BASE64Decoder base64decoder = new BASE64Decoder();
    if (encodedPassword.charAt(0) != '{') {
      // assume double encoded
      encodedPassword = new String(
          base64decoder.decodeBuffer(encodedPassword), "UTF-8");
    }
    if (!encodedPassword.startsWith(ENCALGORITHM)) {
      System.out.println("Only supports salted SHA1 but got: " + encodedPassword);
      System.exit(1);
    }

    byte[] encodedBytes = base64decoder.decodeBuffer(
        encodedPassword.substring(ENCALGORITHM.length()));
    byte[] salt = Arrays.copyOfRange(encodedBytes, SHA1LENGTH, encodedBytes.length);

    String expected = encode(password, salt, false);
    if (encodedPassword.equals(expected)) {
      System.out.println("Success");
    } else {
      System.out.println("FAILED comparison.\nProvided: " + encodedPassword
          + "\nExpected: " + expected);
      System.exit(1);
    }
  }

  private static void showHelp() {
    System.out.println("java -cp ... GenerateSHA1PasswordEntry [arguments]");
    System.out.println("Generate SHA1 hash entry (with random salt) for userPassword");
    System.out.println("attribute in LDIF (Apache Directory Server compliant).");
    System.out.println("Arguments are optional as below:");
    System.out.println();
    System.out.println("-2\t\t\tBase64 encode the result twice.");
    System.out.println("[-c|--check] <enc>\tCheck password against the given value.");
    System.out.println("\t\t\tThe provided value must be SHA1 encoded with salt.");
    System.out.println("[-h|--help]\t\tThis message.");
  }

  public static void main(String[] args) throws Exception {
    boolean doCheck = false;
    boolean twice = false;

    if (args.length >= 2) {
      if (args.length == 2
          && (args[0].equals("-c") || args[0].equals("--check"))) {
        doCheck = true;
      } else {
        showHelp();
        System.exit(1);
      }
    }
    if (args.length == 1) {
      if (args[0].equals("-h") || args[0].equals("--help")) {
        showHelp();
        System.exit(0);
      } else if (args[0].equals("-2")) {
        twice = true;
      } else {
        showHelp();
        System.exit(1);
      }
    }

    java.io.Console console = System.console();
    char[] password1 = console.readPassword("Password: ");
    char[] password2 = console.readPassword("Retype Password: ");

    if (!Arrays.equals(password1, password2)) {
      System.err.println("Passwords do not match!");
      System.exit(1);
    }

    String password = new String(password1);

    if (doCheck) {
      check(password, args[1]);
    } else {
      System.out.println(encode(password, twice));
    }
  }
}
