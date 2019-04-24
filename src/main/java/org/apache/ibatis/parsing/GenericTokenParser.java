/**
 * Copyright 2009-2019 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.parsing;

/**
 * @author Clinton Begin
 */
public class GenericTokenParser {

    /**
     * 开始的TOKEN字符串
     */
    private final String openToken;
    /**
     * 结束的TOKEN字符串
     */
    private final String closeToken;
    private final TokenHandler handler;

    public GenericTokenParser(String openToken, String closeToken, TokenHandler handler) {
        this.openToken = openToken;
        this.closeToken = closeToken;
        this.handler = handler;
    }

    public String parse(String text) {
        // 如果为空或者空字符串直接返回
        if (text == null || text.isEmpty()) {
            return "";
        }
        // search open token
        // 找到openToken的位置
        int start = text.indexOf(openToken);
        // 不存在直接返回原字符串
        if (start == -1) {
            return text;
        }
        // 获取字符数组
        char[] src = text.toCharArray();
        // 定义偏移量
        int offset = 0;
        // 最终解析结果
        final StringBuilder builder = new StringBuilder();
        // 表达式
        StringBuilder expression = null;
        // 找到了openToken就开始循环
        while (start > -1) {
            // 如果判断start全面是不是含有反斜杠，如果有反斜杠那么参数就会被屏蔽，说明参数不正常
            if (start > 0 && src[start - 1] == '\\') {
                // this open token is escaped. remove the backslash and continue.
                // builder添加src到openToken之前的反斜杠再加上openToken(也就是去掉反斜杠)
                builder.append(src, offset, start - offset - 1).append(openToken);
                // 重新赋值偏移量为openToken的位置+它自身的长度
                offset = start + openToken.length();
            } else {
                // found open token. let's search close token.
                // 如果表达式为null 那么久new一个StringBuilder
                if (expression == null) {
                    expression = new StringBuilder();
                } else {
                    // 不为空那么就置空
                    expression.setLength(0);
                }
                // builder添加openToken之前的字符串
                builder.append(src, offset, start - offset);
                // 重新赋值偏移量为openToken的位置+它自身的长度
                offset = start + openToken.length();
                // 源字符串偏移量offset开始寻找closeToken的位置
                int end = text.indexOf(closeToken, offset);
                while (end > -1) {
                    // 如果closeToken前一样还是有反斜杠的存在
                    if (end > offset && src[end - 1] == '\\') {
                        // this close token is escaped. remove the backslash and continue.
                        // 表达式为 openToken与closeToken之间的字符串并且加上closeToken
                        expression.append(src, offset, end - offset - 1).append(closeToken);
                        offset = end + closeToken.length();
                        // 重新获取offset的位置下closeToken的位置
                        end = text.indexOf(closeToken, offset);
                    } else {
                        // 正常情况 表达式会取得 openToken与closeToken之间的字符串
                        expression.append(src, offset, end - offset);
                        // 重新定义offset为end的index+自身长度
                        offset = end + closeToken.length();
                        // 跳出循环
                        break;
                    }
                }
                if (end == -1) {
                    // close token was not found.
                    // 如果没找到closeToken那么把openToken位置之后的字符串拼接过来
                    builder.append(src, start, src.length - start);
                    // 偏移量为源字符串的长度
                    offset = src.length;
                } else {
                    // 正常情况下解析表达式对应的值
                    builder.append(handler.handleToken(expression.toString()));
                    // 偏移量为end的index+自身长度
                    offset = end + closeToken.length();
                }
            }
            // 结束一次循环时，根据offset获取openToken的index
            start = text.indexOf(openToken, offset);
        }
        // 解析到最后offset一定要等于src的长度否则把剩余的字符串拼接上
        if (offset < src.length) {
            builder.append(src, offset, src.length - offset);
        }
        return builder.toString();
    }

}
