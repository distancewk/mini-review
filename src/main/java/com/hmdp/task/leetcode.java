package com.hmdp.task;

import java.util.*;

public class leetcode {
    public List<Integer> findAnagrams(String s, String p) {
        List<Integer> ans = new ArrayList<>();
        int n = p.length();
        int m = s.length();
        int[] pcount =new int[26];
        int[] scount =new int[26];
        for (int i = 0; i < n; i++) {
            pcount[p.charAt(i) - 'a']++;

        }
        for (int i = 0; i <m ; i++) {
            scount[s.charAt(i) - 'a']++;
            int left = i - n + 1;
            if(left<0){continue;}
            if (Arrays.equals(pcount, scount)) {
                ans.add(i);
            }
            scount[s.charAt(left) - 'a']--;

        }
        return ans;





    }

    public static void main(String[] args) {
        leetcode leetcode = new leetcode();
        System.out.println(leetcode.findAnagrams("cbaebabacd","abc"));
    }

}
