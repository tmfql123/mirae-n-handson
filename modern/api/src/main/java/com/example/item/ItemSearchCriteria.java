package com.example.item;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.util.MultiValueMap;

/**
 * 검색 조건. 레거시 {@code search.php} buildSearchQuery 의 입력 해석(BR-03 ~ BR-14)을 그대로 옮겼다.
 * 레거시 동작이 이상해 보여도 고치지 않는다 — 예: 난이도 {@code 3a} 는 PHP {@code (int)} 처럼 3 이 된다(BR-08).
 */
public record ItemSearchCriteria(
    String keyword,
    String unit,
    Integer level,
    String tag,
    String orderBy,
    int page,
    int offset
) {

    public static final int PAGE_SIZE = 20;

    private static final Pattern PHP_NUMBER_PREFIX = Pattern.compile(
        "[ \\t\\n\\r\\u000B\\f]*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");
    private static final Pattern INTEGER_NUMBER = Pattern.compile("[+-]?[0-9]+");
    private static final Pattern PAGE_NUMBER = Pattern.compile("[0-9]+\\n?");

    public static ItemSearchCriteria from(MultiValueMap<String, String> params) {
        String keyword = phpTrim(parameter(params, "q", ""));
        keyword = truncateCodePoints(keyword, 100);
        if (keyword.isEmpty()) {
            keyword = null;
        }

        String unit = phpTrim(parameter(params, "unit", ""));
        if (unit.isEmpty()) {
            unit = null;
        }

        String levelRaw = phpTrim(parameter(params, "level", ""));
        Integer level = levelRaw.isEmpty() ? null : phpIntval(levelRaw);

        String tag = phpTrim(parameter(params, "tag", ""));
        if (tag.isEmpty()) {
            tag = null;
        } else {
            tag = truncateCodePoints(tag, 50);
        }

        String sort = asciiLowerCase(phpTrim(parameter(params, "sort", "")));
        String dir = asciiLowerCase(phpTrim(parameter(params, "dir", "")));
        if (!dir.equals("asc") && !dir.equals("desc")) {
            dir = "";
        }
        String orderBy = orderBy(sort, dir);

        int page = page(parameter(params, "page", "1"));
        return new ItemSearchCriteria(keyword, unit, level, tag, orderBy, page, (page - 1) * PAGE_SIZE);
    }

    private static String parameter(MultiValueMap<String, String> params, String name, String defaultValue) {
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(name + "[") && key.endsWith("]")) {
                return firstOrEmpty(entry.getValue());
            }
        }

        List<String> values = params.get(name);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        String value = values.get(values.size() - 1);
        return value == null ? "" : value;
    }

    private static String firstOrEmpty(List<String> values) {
        if (values == null || values.isEmpty() || values.getFirst() == null) {
            return "";
        }
        return values.getFirst();
    }

    private static String phpTrim(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isPhpTrimCharacter(value.charAt(start))) {
            start++;
        }
        while (end > start && isPhpTrimCharacter(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isPhpTrimCharacter(char value) {
        return value == ' ' || value == '\t' || value == '\n' || value == '\r'
            || value == '\0' || value == '\u000B';
    }

    private static String truncateCodePoints(String value, int maximumLength) {
        int codePoints = value.codePointCount(0, value.length());
        if (codePoints <= maximumLength) {
            return value;
        }
        return value.substring(0, value.offsetByCodePoints(0, maximumLength));
    }

    private static int phpIntval(String value) {
        Matcher matcher = PHP_NUMBER_PREFIX.matcher(value);
        if (!matcher.lookingAt()) {
            return 0;
        }

        String number = matcher.group(1);
        if (INTEGER_NUMBER.matcher(number).matches()) {
            return saturatingInteger(number);
        }

        double parsed = Double.parseDouble(number);
        if (parsed >= Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (parsed <= Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) parsed;
    }

    private static int saturatingInteger(String value) {
        BigInteger parsed = new BigInteger(value);
        if (parsed.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
            return Integer.MAX_VALUE;
        }
        if (parsed.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) < 0) {
            return Integer.MIN_VALUE;
        }
        return parsed.intValue();
    }

    private static String asciiLowerCase(String value) {
        StringBuilder lowered = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            lowered.append(character >= 'A' && character <= 'Z' ? (char) (character + ('a' - 'A')) : character);
        }
        return lowered.toString();
    }

    private static String orderBy(String sort, String dir) {
        return switch (sort) {
            case "id" -> dir.equals("desc") ? "id DESC" : "id ASC";
            case "title" -> dir.equals("desc") ? "title DESC, id ASC" : "title ASC, id ASC";
            case "unit" -> dir.equals("desc")
                ? "unit_code DESC, level DESC, id ASC"
                : "unit_code ASC, level DESC, id ASC";
            case "level" -> dir.equals("asc") ? "level ASC, id ASC" : "level DESC, id ASC";
            case "created" -> dir.equals("asc") ? "created_at ASC, id ASC" : "created_at DESC, id ASC";
            default -> "level DESC, id ASC";
        };
    }

    private static int page(String rawPage) {
        if (!PAGE_NUMBER.matcher(rawPage).matches()) {
            return 1;
        }
        String digits = rawPage.endsWith("\n") ? rawPage.substring(0, rawPage.length() - 1) : rawPage;
        int parsed = saturatingInteger(digits);
        if (parsed < 1) {
            return 1;
        }
        return Math.min(parsed, 999);
    }
}
