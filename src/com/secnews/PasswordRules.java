package com.secnews;

import java.util.Arrays;

/** 密碼規則：至少 8 碼，且包含英文字母與數字。 */
public final class PasswordRules {
    private PasswordRules() {}

    /** 不符合時回傳錯誤訊息；符合回傳 null。 */
    public static String check(char[] pw, char[] confirm) {
        if (pw.length < 8) return "密碼至少 8 碼";
        boolean letter = false, digit = false;
        for (char ch : pw) {
            if (Character.isLetter(ch)) letter = true;
            if (Character.isDigit(ch)) digit = true;
        }
        if (!letter || !digit) return "密碼需同時包含英文字母與數字";
        if (!Arrays.equals(pw, confirm)) return "兩次輸入的密碼不一致";
        return null;
    }
}
