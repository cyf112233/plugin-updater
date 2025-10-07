package byd.cxkcxkckx.pluginupdate.updater;

/**
 * 字符串相似度计算工具
 * 使用 Levenshtein 距离和 Jaro-Winkler 距离的组合来计算相似度
 */
public class StringSimilarity {

    /**
     * 计算两个字符串的相似度（0.0 到 1.0，1.0 表示完全匹配）
     * 综合考虑：
     * 1. 忽略大小写
     * 2. Jaro-Winkler 距离（对前缀匹配更友好）
     * 3. 包含关系加分
     * 
     * @param s1 字符串1
     * @param s2 字符串2
     * @return 相似度分数 0.0-1.0
     */
    public static double similarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        
        String str1 = s1.toLowerCase().trim();
        String str2 = s2.toLowerCase().trim();
        
        if (str1.isEmpty() || str2.isEmpty()) return 0.0;
        if (str1.equals(str2)) return 1.0;
        
        // 基础分数：Jaro-Winkler 距离
        double baseScore = jaroWinkler(str1, str2);
        
        // 包含关系加分
        double containsBonus = 0.0;
        if (str1.contains(str2) || str2.contains(str1)) {
            containsBonus = 0.1;
        }
        
        // 完全匹配某个单词加分
        String[] words1 = str1.split("[\\s\\-_]+");
        String[] words2 = str2.split("[\\s\\-_]+");
        double wordMatchBonus = 0.0;
        for (String w1 : words1) {
            for (String w2 : words2) {
                if (w1.equals(w2) && w1.length() > 2) {
                    wordMatchBonus = Math.max(wordMatchBonus, 0.15);
                }
            }
        }
        
        return Math.min(1.0, baseScore + containsBonus + wordMatchBonus);
    }

    /**
     * Jaro-Winkler 距离计算
     * 对前缀匹配给予更高权重，适合插件名称匹配
     */
    private static double jaroWinkler(String s1, String s2) {
        double jaro = jaro(s1, s2);
        
        // 计算公共前缀长度（最多4个字符）
        int prefix = 0;
        int minLen = Math.min(Math.min(s1.length(), s2.length()), 4);
        for (int i = 0; i < minLen; i++) {
            if (s1.charAt(i) == s2.charAt(i)) {
                prefix++;
            } else {
                break;
            }
        }
        
        // Jaro-Winkler 公式：jw = jaro + (prefix * 0.1 * (1 - jaro))
        return jaro + (prefix * 0.1 * (1.0 - jaro));
    }

    /**
     * Jaro 距离计算
     */
    private static double jaro(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;
        
        int len1 = s1.length();
        int len2 = s2.length();
        
        // 匹配窗口：max(len1, len2) / 2 - 1
        int matchWindow = Math.max(len1, len2) / 2 - 1;
        if (matchWindow < 1) matchWindow = 1;
        
        boolean[] s1Matches = new boolean[len1];
        boolean[] s2Matches = new boolean[len2];
        
        int matches = 0;
        int transpositions = 0;
        
        // 查找匹配字符
        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchWindow);
            int end = Math.min(i + matchWindow + 1, len2);
            
            for (int j = start; j < end; j++) {
                if (s2Matches[j] || s1.charAt(i) != s2.charAt(j)) continue;
                s1Matches[i] = true;
                s2Matches[j] = true;
                matches++;
                break;
            }
        }
        
        if (matches == 0) return 0.0;
        
        // 计算换位
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!s1Matches[i]) continue;
            while (!s2Matches[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }
        
        // Jaro 公式
        return ((double) matches / len1 + 
                (double) matches / len2 + 
                (double) (matches - transpositions / 2.0) / matches) / 3.0;
    }

    /**
     * 从多个候选项中选择最佳匹配
     * 
     * @param target 目标字符串（要匹配的插件名）
     * @param candidates 候选项数组
     * @return 最佳匹配的索引，如果没有足够好的匹配返回 -1
     */
    public static int bestMatch(String target, String[] candidates) {
        if (target == null || candidates == null || candidates.length == 0) {
            return -1;
        }
        
        int bestIndex = -1;
        double bestScore = 0.0;
        
        for (int i = 0; i < candidates.length; i++) {
            double score = similarity(target, candidates[i]);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = i;
            }
        }
        
        // 只有相似度超过 0.6 才认为是有效匹配
        return bestScore >= 0.6 ? bestIndex : -1;
    }
}
