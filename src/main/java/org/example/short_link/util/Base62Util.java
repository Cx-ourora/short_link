package org.example.short_link.util;

/**
 * Base62编码工具类
 * 用于将数字转换为Base62字符串，生成短链编码
 */
public class Base62Util {

    /**
     * Base62字符集：0-9, A-Z, a-z
     * 总共62个字符，避免了容易混淆的字符
     */
    private static final String BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = 62;

    /**
     * 生成指定长度的Base62字符串，严格控制长度
     *
     * @param num         要编码的数字
     * @param exactLength 精确长度，超过则截取，不足则补0
     * @return 精确长度的Base62字符串
     */
    public static String encodeWithExactLength(long num, int exactLength) {
        if (exactLength <= 0) {
            throw new IllegalArgumentException("长度必须大于0");
        }

        String encoded = encode(num);

        if (encoded.length() > exactLength) {
            // 超过长度，截取前面部分
            return encoded.substring(0, exactLength);
        } else if (encoded.length() < exactLength) {
            // 不足长度，前面补0
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < exactLength - encoded.length(); i++) {
                sb.append('0');
            }
            sb.append(encoded);
            return sb.toString();
        } else {
            // 长度正好
            return encoded;
        }
    }

    /**
     * 将长整型数字编码为Base62字符串
     *
     * @param num 要编码的数字
     * @return Base62编码后的字符串
     */
    public static String encode(long num) {
        if (num == 0) {
            return "0";
        }

        StringBuilder sb = new StringBuilder();
        while (num > 0) {
            sb.append(BASE62_CHARS.charAt((int) (num % BASE)));
            num /= BASE;
        }

        // 反转字符串，因为我们是从低位开始构建的
        return sb.reverse().toString();
    }

    /**
     * 将Base62字符串解码为长整型数字
     *
     * @param str Base62编码的字符串
     * @return 解码后的数字
     * @throws IllegalArgumentException 如果字符串包含非法字符
     */
    public static long decode(String str) {
        if (str == null || str.isEmpty()) {
            throw new IllegalArgumentException("输入字符串不能为空");
        }

        long result = 0;
        long power = 1;

        // 从右到左处理字符串
        for (int i = str.length() - 1; i >= 0; i--) {
            char c = str.charAt(i);
            int index = BASE62_CHARS.indexOf(c);

            if (index == -1) {
                throw new IllegalArgumentException("包含非法字符: " + c);
            }

            result += index * power;
            power *= BASE;
        }

        return result;
    }

    /**
     * 生成指定长度的Base62字符串
     *
     * @param num       要编码的数字
     * @param minLength 最小长度，不足时前面补0
     * @return 指定长度的Base62字符串
     */
    public static String encodeWithMinLength(long num, int minLength) {
        String encoded = encode(num);

        if (encoded.length() >= minLength) {
            return encoded;
        }

        // 前面补0到指定长度
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < minLength - encoded.length(); i++) {
            sb.append('0');
        }
        sb.append(encoded);

        return sb.toString();
    }

    /**
     * 验证字符串是否为有效的Base62编码
     *
     * @param str 要验证的字符串
     * @return 是否为有效的Base62编码
     */
    public static boolean isValidBase62(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }

        for (char c : str.toCharArray()) {
            if (BASE62_CHARS.indexOf(c) == -1) {
                return false;
            }
        }

        return true;
    }

    /**
     * 获取Base62字符集
     *
     * @return Base62字符集字符串
     */
    public static String getBase62Chars() {
        return BASE62_CHARS;
    }

    /**
     * 获取Base62进制数
     *
     * @return 62
     */
    public static int getBase() {
        return BASE;
    }

    /**
     * 计算指定长度的Base62字符串能表示的最大数值
     *
     * @param length 字符串长度
     * @return 最大数值
     */
    public static long getMaxValue(int length) {
        if (length <= 0) {
            return 0;
        }

        long max = 0;
        long power = 1;

        for (int i = 0; i < length; i++) {
            max += (BASE - 1) * power;
            power *= BASE;
        }

        return max;
    }

    /**
     * 生成随机的Base62字符串
     *
     * @param length 字符串长度
     * @return 随机的Base62字符串
     */
    public static String generateRandom(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("长度必须大于0");
        }

        StringBuilder sb = new StringBuilder();
        java.util.Random random = new java.util.Random();

        for (int i = 0; i < length; i++) {
            int index = random.nextInt(BASE);
            sb.append(BASE62_CHARS.charAt(index));
        }

        return sb.toString();
    }
}