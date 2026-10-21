package com.tvboxhost;

import java.security.AlgorithmParameters;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.spec.AlgorithmParameterSpec;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherSpi;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.ShortBufferException;

/**
 * Android 的加密填充名和 JCE 不一样：
 *   Android: AES/CBC/PKCS7Padding      JCE: AES/CBC/PKCS5Padding
 * 对 AES 来说两者等价（块大小 16 就会自动用 PKCS#7）。spider 里一堆站点用安卓的写法，
 * JVM 上直接 NoSuchAlgorithmException，整站废。这里注册一个 provider 做别名映射。
 */
public final class AndroidCrypto {
    private static boolean installed;

    private AndroidCrypto() {}

    public static synchronized void install() {
        if (installed) return;
        try {
            Security.addProvider(new AliasProvider());
            installed = true;
        } catch (Throwable t) {
            System.err.println("[crypto] 注册 Android 填充别名失败: " + t);
        }
    }

    static final class AliasProvider extends Provider {
        AliasProvider() {
            super("AndroidCryptoAlias", 1.0, "Android PKCS7Padding aliases for JCE");
            put("Cipher.AES/CBC/PKCS7Padding", AesCbc.class.getName());
            put("Cipher.AES/ECB/PKCS7Padding", AesEcb.class.getName());
            put("Cipher.AES/CTR/PKCS7Padding", AesCtr.class.getName());
            put("Cipher.AES/GCM/PKCS7Padding", AesGcm.class.getName());
            put("Cipher.DES/CBC/PKCS7Padding", DesCbc.class.getName());
            put("Cipher.DESede/CBC/PKCS7Padding", DesEdeCbc.class.getName());
        }
    }

    abstract static class Pkcs7Base extends CipherSpi {
        private final String delegate;
        private Cipher inner;

        Pkcs7Base(String delegate) { this.delegate = delegate; }

        private Cipher inner() throws NoSuchAlgorithmException, NoSuchPaddingException {
            if (inner == null) inner = Cipher.getInstance(delegate);
            return inner;
        }

        @Override protected void engineSetMode(String mode) { }
        @Override protected void engineSetPadding(String padding) { }
        @Override protected int engineGetBlockSize() { return 16; }
        @Override protected int engineGetOutputSize(int inputLen) {
            try { return inner().getOutputSize(inputLen); } catch (Exception e) { return inputLen + 32; }
        }
        @Override protected byte[] engineGetIV() {
            try { return inner().getIV(); } catch (Exception e) { return null; }
        }
        @Override protected AlgorithmParameters engineGetParameters() {
            try { return inner().getParameters(); } catch (Exception e) { return null; }
        }
        @Override protected void engineInit(int opmode, Key key, SecureRandom random)
                throws InvalidKeyException {
            try { inner().init(opmode, key, random); }
            catch (InvalidKeyException e) { throw e; }
            catch (Exception e) { throw new InvalidKeyException(e); }
        }
        @Override protected void engineInit(int opmode, Key key, AlgorithmParameterSpec params, SecureRandom random)
                throws InvalidKeyException, InvalidAlgorithmParameterException {
            try { inner().init(opmode, key, params, random); }
            catch (InvalidKeyException | InvalidAlgorithmParameterException e) { throw e; }
            catch (Exception e) { throw new InvalidAlgorithmParameterException(e); }
        }
        @Override protected void engineInit(int opmode, Key key, AlgorithmParameters params, SecureRandom random)
                throws InvalidKeyException, InvalidAlgorithmParameterException {
            try { inner().init(opmode, key, params, random); }
            catch (InvalidKeyException | InvalidAlgorithmParameterException e) { throw e; }
            catch (Exception e) { throw new InvalidAlgorithmParameterException(e); }
        }
        @Override protected byte[] engineUpdate(byte[] input, int offset, int len) {
            try { return inner().update(input, offset, len); } catch (Exception e) { return new byte[0]; }
        }
        @Override protected int engineUpdate(byte[] input, int offset, int len, byte[] output, int outOffset)
                throws ShortBufferException {
            try { return inner().update(input, offset, len, output, outOffset); }
            catch (ShortBufferException e) { throw e; }
            catch (Exception e) { throw new ShortBufferException(e.toString()); }
        }
        @Override protected byte[] engineDoFinal(byte[] input, int offset, int len)
                throws IllegalBlockSizeException, BadPaddingException {
            try { return inner().doFinal(input, offset, len); }
            catch (IllegalBlockSizeException | BadPaddingException e) { throw e; }
            catch (Exception e) { throw new IllegalBlockSizeException(e.toString()); }
        }
        @Override protected int engineDoFinal(byte[] input, int offset, int len, byte[] output, int outOffset)
                throws ShortBufferException, IllegalBlockSizeException, BadPaddingException {
            try { return inner().doFinal(input, offset, len, output, outOffset); }
            catch (ShortBufferException | IllegalBlockSizeException | BadPaddingException e) { throw e; }
            catch (Exception e) { throw new IllegalBlockSizeException(e.toString()); }
        }
    }

    public static final class AesCbc extends Pkcs7Base { public AesCbc() { super("AES/CBC/PKCS5Padding"); } }
    public static final class AesEcb extends Pkcs7Base { public AesEcb() { super("AES/ECB/PKCS5Padding"); } }
    public static final class AesCtr extends Pkcs7Base { public AesCtr() { super("AES/CTR/NoPadding"); } }
    public static final class AesGcm extends Pkcs7Base { public AesGcm() { super("AES/GCM/NoPadding"); } }
    public static final class DesCbc extends Pkcs7Base { public DesCbc() { super("DES/CBC/PKCS5Padding"); } }
    public static final class DesEdeCbc extends Pkcs7Base { public DesEdeCbc() { super("DESede/CBC/PKCS5Padding"); } }
}
