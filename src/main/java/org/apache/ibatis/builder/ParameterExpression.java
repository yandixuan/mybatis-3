/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder;

import java.util.HashMap;

/**
 * Inline parameter expression parser. Supported grammar (simplified):
 *
 * <pre>
 * inline-parameter = (propertyName | expression) oldJdbcType attributes
 * propertyName = /expression language's property navigation path/
 * expression = '(' /expression language's expression/ ')'
 * oldJdbcType = ':' /any valid jdbc type/
 * attributes = (',' attribute)*
 * attribute = name '=' value
 * </pre>
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class ParameterExpression extends HashMap<String, String> {

  private static final long serialVersionUID = -2417552199605158680L;

  public ParameterExpression(String expression) {
    parse(expression);
  }

  private void parse(String expression) {
    // 返回不是空格字符的位置
    int p = skipWS(expression, 0);
    // 如果字符是'('
    if (expression.charAt(p) == '(') {
      expression(expression, p + 1);
      // 否则就是属性，开始解析属性
    } else {
      property(expression, p);
    }
  }

  private void expression(String expression, int left) {
    int match = 1;
    int right = left + 1;
    while (match > 0) {
      if (expression.charAt(right) == ')') {
        match--;
      } else if (expression.charAt(right) == '(') {
        match++;
      }
      right++;
    }
    put("expression", expression.substring(left, right - 1));
    jdbcTypeOpt(expression, right);
  }

  private void property(String expression, int left) {
    // 开始解析property的位置必须不是expression的长度
    if (left < expression.length()) {
      // 根据任意',:'结束符获取right的位置（第一个必然是属性）
      int right = skipUntil(expression, left, ",:");
      // 去掉空格之后放入Map的property属性中
      put("property", trimmedStr(expression, left, right));
      // 从right开始解析额外属性eg:javaType,jdbcType,typeHandler
      jdbcTypeOpt(expression, right);
    }
  }

  /**
   * 跳过空格 0x20 16进制代表空格
   * @param expression
   * @param p
   * @return 返回不是空格的位置
   */
  private int skipWS(String expression, int p) {
    for (int i = p; i < expression.length(); i++) {
      if (expression.charAt(i) > 0x20) {
        return i;
      }
    }
    return expression.length();
  }

  private int skipUntil(String expression, int p, final String endChars) {
    for (int i = p; i < expression.length(); i++) {
      char c = expression.charAt(i);
      if (endChars.indexOf(c) > -1) {
        return i;
      }
    }
    return expression.length();
  }

  private void jdbcTypeOpt(String expression, int p) {
    // #{property,javaType=int,jdbcType=NUMERIC}
    // property:VARCHAR
    p = skipWS(expression, p);
    if (p < expression.length()) {
      //第一个property解析完有两种情况，逗号和冒号
      if (expression.charAt(p) == ':') {
        jdbcType(expression, p + 1);
      } else if (expression.charAt(p) == ',') {
        option(expression, p + 1);
      } else {
        throw new BuilderException("Parsing error in {" + expression + "} in position " + p);
      }
    }
  }

  private void jdbcType(String expression, int p) {
    // property:VARCHAR 所以解析的应该是属性对应jdbcType
    int left = skipWS(expression, p);
    int right = skipUntil(expression, left, ",");
    // 结束的位置应该大于开始的位置
    if (right > left) {
      put("jdbcType", trimmedStr(expression, left, right));
      // 否则抛出 BuilderException在p位置有错
    } else {
      throw new BuilderException("Parsing error in {" + expression + "} in position " + p);
    }
    // 接着解析options
    option(expression, right + 1);
  }

  private void option(String expression, int p) {
    int left = skipWS(expression, p);
    if (left < expression.length()) {
      int right = skipUntil(expression, left, "=");
      String name = trimmedStr(expression, left, right);
      left = right + 1;
      right = skipUntil(expression, left, ",");
      String value = trimmedStr(expression, left, right);
      // name,value键值对放入map
      put(name, value);
      option(expression, right + 1);
    }
  }

  /**
   * 首尾去空格
   * @param str
   * @param start
   * @param end
   * @return
   */
  private String trimmedStr(String str, int start, int end) {
    while (str.charAt(start) <= 0x20) {
      start++;
    }
    while (str.charAt(end - 1) <= 0x20) {
      end--;
    }
    return start >= end ? "" : str.substring(start, end);
  }

}
