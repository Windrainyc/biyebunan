package cn.zhixiang.platform.knowledge.service;

import java.util.*;
import java.util.regex.Pattern;

/** 无付费依赖的中英文关键词提取，仅用于召回候选，不宣称语义理解。 */
public final class QuestionKeywords {
    private QuestionKeywords() {}

    /**
     * 将问题转为最多24个安全检索片段，英文按词，中文按相邻双字。
     *
     * @param question 问题
     * @return 去重词列表
     */
    public static List<String> terms(String question) {
        var result = new LinkedHashSet<String>();
        String text = question == null ? "" : question.toLowerCase(Locale.ROOT);
        var matcher = Pattern.compile("[a-z0-9_]{2,40}|[\\p{IsHan}]{2,}").matcher(text);
        var stop =
                Set.of(
                        "如何", "怎么", "什么", "请问", "帮我", "一下", "可以", "这个", "一个", "进行", "的方", "方法",
                        "是否", "the", "and", "for", "with");
        while (matcher.find() && result.size() < 24) {
            String word = matcher.group();
            if (word.codePoints()
                    .allMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                for (int i = 0; i < word.length() - 1 && result.size() < 24; i++) {
                    String part = word.substring(i, i + 2);
                    if (!stop.contains(part)) result.add(part);
                }
            } else if (!stop.contains(word)) result.add(word);
        }
        return List.copyOf(result);
    }
}
