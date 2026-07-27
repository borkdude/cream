package com.example;

import org.apache.commons.codec.digest.DigestUtils;

public class Hello {
    public static void main(String[] args) {
        String input = args.length > 0 ? args[0] : "hello world";
        System.out.println(input + " -> " + DigestUtils.sha256Hex(input));
    }
}
