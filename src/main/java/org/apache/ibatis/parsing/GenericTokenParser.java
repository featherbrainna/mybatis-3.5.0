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
package org.apache.ibatis.parsing;

/**
 * 通用的占位符解析器
 * @author Clinton Begin
 */
public class GenericTokenParser {

  /**
   * 占位符的开始标记
   */
  private final String openToken;
  /**
   * 占位符的结束标记
   */
  private final String closeToken;
  /**
   * 底层实现占位符处理的组件。
   * TokenHandler接口的实现会按照一定的逻辑解析占位符
   */
  private final TokenHandler handler;

  public GenericTokenParser(String openToken, String closeToken, TokenHandler handler) {
    this.openToken = openToken;
    this.closeToken = closeToken;
    this.handler = handler;
  }

  /**
   * 解析处理含openToken和closeToken的字符串
   * @param text 原字符串
   * @return 解析后字符串
   */
  public String parse(String text) {
    //1.检测text是否为空，为空返回""
    if (text == null || text.isEmpty()) {
      return "";
    }
    //查找token的开始标记 search open token
    int start = text.indexOf(openToken);
    //没找到token返回原text
    if (start == -1) {
      return text;
    }
    //原字符串
    char[] src = text.toCharArray();
    int offset = 0;//以处理字符串的下标
    //解析后的字符串
    final StringBuilder builder = new StringBuilder();
    //记录占位符的字面值
    StringBuilder expression = null;
    //2.遍历解析处理字符串
    while (start > -1) {
      if (start > 0 && src[start - 1] == '\\') {
        // this open token is escaped. remove the backslash and continue.
        //3.遇到转义的开始标记，就不对其解析处理，直接将前面的字符串以及开始标记追加到builder中
        builder.append(src, offset, start - offset - 1).append(openToken);
        offset = start + openToken.length();
      } else {
        // found open token. let's search close token.
        //4.查找到开始标记，且未转义
        if (expression == null) {
          expression = new StringBuilder();
        } else {
          expression.setLength(0);
        }
        //将前面的字符串追加到builder中
        builder.append(src, offset, start - offset);
        offset = start + openToken.length();
        //向offset后继续查找结束标记
        int end = text.indexOf(closeToken, offset);
        while (end > -1) {
          if (end > offset && src[end - 1] == '\\') {
            // this close token is escaped. remove the backslash and continue.
            //5.处理转义的结束标记，先保存到expression
            expression.append(src, offset, end - offset - 1).append(closeToken);
            offset = end + closeToken.length();
            end = text.indexOf(closeToken, offset);
          } else {
            //6.将开始标记和结束标记之间的字符追加到expression中保存
            expression.append(src, offset, end - offset);
            offset = end + closeToken.length();
            break;
          }
        }
        if (end == -1) {
          // close token was not found.
          //未找到结束标记
          builder.append(src, start, src.length - start);
          offset = src.length;
        } else {
          //找到结束标记。将占位符的字面值交给TokenHandler处理，并将处理结果追加到builder中保存，最终拼凑出解析后的完整内容
          //调用底层TokenHandler的handleToken方法
          builder.append(handler.handleToken(expression.toString()));
          offset = end + closeToken.length();
        }
      }
      start = text.indexOf(openToken, offset);
    }
    if (offset < src.length) {
      builder.append(src, offset, src.length - offset);
    }
    return builder.toString();
  }
}
