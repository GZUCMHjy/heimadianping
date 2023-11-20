package com.hmdp;

import javax.crypto.*;
import javax.crypto.spec.DHParameterSpec;
import javax.crypto.spec.DHPrivateKeySpec;
import javax.crypto.spec.DHPublicKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.security.*;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

public class DHExample {
    public static void main(String[] args) throws NoSuchAlgorithmException, InvalidAlgorithmParameterException, InvalidKeyException, InvalidKeySpecException, NoSuchPaddingException, BadPaddingException, IllegalBlockSizeException {
        // 1. 初始化密钥对生成器
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("DH");
        keyPairGenerator.initialize(1024); // 设置密钥长度

        // 2. 生成密钥对
        KeyPair keyPair = keyPairGenerator.generateKeyPair();
        PublicKey publicKey = keyPair.getPublic();
        PrivateKey privateKey = keyPair.getPrivate();

        // 3. 将公钥编码成字节数组，发送给其他通信方
        byte[] publicKeyBytes = publicKey.getEncoded();

        // 4. 从字节数组恢复公钥对象
        KeyFactory keyFactory = KeyFactory.getInstance("DH");
        X509EncodedKeySpec x509KeySpec = new X509EncodedKeySpec(publicKeyBytes);
        PublicKey otherPublicKey = keyFactory.generatePublic(x509KeySpec);

        // 5. 创建密钥协商对象
        KeyAgreement keyAgreement = KeyAgreement.getInstance("DH");
        keyAgreement.init(privateKey); // 使用自己的私钥初始化

        // 6. 执行密钥协商
        keyAgreement.doPhase(otherPublicKey, true);

        // 7. 生成共享密钥
        byte[] sharedSecret = keyAgreement.generateSecret();

        // 8. 使用哈希函数从共享密钥派生AES密钥
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
        byte[] derivedKey = Arrays.copyOf(sha256.digest(sharedSecret), 16); // 128位AES密钥

        // 打印共享密钥
        System.out.println("Shared Secret (DH): " + bytesToHex(sharedSecret));
        System.out.println("Derived Key (AES): " + bytesToHex(derivedKey));

        // 9. 加密和解密示例
        String plaintext = "Hello, World!";
        System.out.println("Plaintext: " + plaintext);

        // 加密
        Cipher cipher = Cipher.getInstance("AES");
        SecretKeySpec secretKeySpec = new SecretKeySpec(derivedKey, "AES");
        cipher.init(Cipher.ENCRYPT_MODE, secretKeySpec);
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes());
        System.out.println("Ciphertext: " + bytesToHex(ciphertext));

        // 解密
        cipher.init(Cipher.DECRYPT_MODE, secretKeySpec);
        byte[] decryptedBytes = cipher.doFinal(ciphertext);
        String decryptedText = new String(decryptedBytes);
        System.out.println("Decrypted Text: " + decryptedText);
    }

    // 辅助方法：将字节数组转换为十六进制字符串
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }
}