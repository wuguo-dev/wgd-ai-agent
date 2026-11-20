package com.example.wjyaiagent.common.request;

import com.example.wjyaiagent.community.service.CommunityException;

import java.util.Locale;
import java.util.Set;

public final class RequestValidation {
    private RequestValidation() {}

    public static void pagination(int page, int size) {
        if (page < 1 || size < 1 || size > 50) throw CommunityException.invalid("分页参数超出范围");
    }

    public static boolean oldest(String sort) {
        validateChoice(sort, Set.of("NEWEST", "OLDEST"));
        return "OLDEST".equals(sort);
    }

    public static void validateChoice(String value, Set<String> choices) {
        if (value != null && !choices.contains(value)) throw CommunityException.invalid("筛选参数不正确");
    }

    public static String search(String value) {
        if (value.length() > 200) throw CommunityException.invalid("搜索参数最多200字符");
        return "%"
                + value.strip()
                        .toLowerCase(Locale.ROOT)
                        .replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_")
                + "%";
    }
}
