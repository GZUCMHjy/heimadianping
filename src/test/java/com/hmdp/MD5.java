package com.hmdp;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class MD5 {
    private static final String FIXED_STRING = "666666";
    private static final int NUM_THREADS = 4;
    private static volatile boolean collisionFound = false;
    private static volatile String targetString;

    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();

        Thread[] threads = new Thread[NUM_THREADS];
        for (int i = 0; i < NUM_THREADS; i++) {
            threads[i] = new CollisionSearchThread();
            threads[i].start();
        }

        // 等待所有线程完成
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        long endTime = System.currentTimeMillis();
        double executionTime = (endTime - startTime) / 1000.0;

        System.out.println("固定字符串: " + FIXED_STRING);
        System.out.println("目标字符串: " + targetString);
        System.out.println("执行时间: " + executionTime + " 秒");
    }

    private static String calculateMD5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] messageDigest = md.digest(input.getBytes());

            StringBuilder sb = new StringBuilder();
            for (byte b : messageDigest) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    private static class CollisionSearchThread extends Thread {
        @Override
        public void run() {
            while (!collisionFound) {
                String randomString = String.valueOf(System.currentTimeMillis());
                String md5Hash = calculateMD5Hash(randomString);

                if (md5Hash.equals(calculateMD5Hash(FIXED_STRING))) {
                    targetString = randomString;
                    collisionFound = true;
                    break;
                }
            }
        }
    }
}