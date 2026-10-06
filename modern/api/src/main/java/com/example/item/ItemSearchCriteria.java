package com.example.item;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Repository 가 ORDER BY 에 그대로 붙이는 조각이라, 이 목록 밖의 값은 생성 단계에서 거부한다. */
    private static final Set<String> ORDER_BY_WHITELIST = Set.of(
        "id ASC", "id DESC",
        "title ASC, id ASC", "title DESC, id ASC",
        "unit_code ASC, level DESC, id ASC", "unit_code DESC, level DESC, id ASC",
        "level ASC, id ASC", "level DESC, id ASC",
        "created_at ASC, id ASC", "created_at DESC, id ASC");

    private static final Pattern PHP_NUMBER_PREFIX = Pattern.compile(
        "[ \\t\\n\\r\\u000B\\f]*([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)");
    private static final Pattern INTEGER_NUMBER = Pattern.compile("[+-]?[0-9]+");
    private static final Pattern PAGE_NUMBER = Pattern.compile("[0-9]+\\n?");

    public ItemSearchCriteria {
        if (orderBy == null || !ORDER_BY_WHITELIST.contains(orderBy)) {
            throw new IllegalArgumentException("허용되지 않은 정렬 조건입니다: " + orderBy);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset 은 0 이상이어야 합니다: " + offset);
        }
    }

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

    /**
     * PHP {@code $_GET} 처럼 평문 키와 배열 키({@code name[...]}) 중 나중에 나온 형태를 쓴다.
     * 평문이 이기면 마지막 값, 배열이 이기면 첫 값({@code reset()})이다.
     * 키 순서는 각 키가 처음 나온 순서라서, 두 형태가 번갈아 섞인 요청까지 PHP 와 같지는 않다.
     */
    private static String parameter(MultiValueMap<String, String> params, String name, String defaultValue) {
        List<String> arrayValues = null;
        List<String> plainValues = null;
        boolean plainLast = false;
        for (Map.Entry<String, List<String>> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key.equals(name)) {
                plainValues = entry.getValue();
                arrayValues = null;
                plainLast = true;
            } else if (key.startsWith(name + "[") && key.endsWith("]")) {
                if (arrayValues == null) {
                    arrayValues = key.equals(name + "[]")
                        ? entry.getValue()
                        : lastOnly(entry.getValue());
                }
                plainLast = false;
            }
        }

        if (arrayValues != null && !plainLast) {
            return firstOrEmpty(arrayValues);
        }
        if (plainValues == null || plainValues.isEmpty()) {
            return defaultValue;
        }
        String value = plainValues.get(plainValues.size() - 1);
        return value == null ? "" : value;
    }

    /** {@code name[k]} 처럼 인덱스가 있는 키는 PHP 에서 같은 칸을 덮어쓰므로 마지막 값만 남는다. */
    private static List<String> lastOnly(List<String> values) {
        if (values == null || values.isEmpty()) {
            return values;
        }
        String last = values.getLast();
        return List.of(last == null ? "" : last);
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
