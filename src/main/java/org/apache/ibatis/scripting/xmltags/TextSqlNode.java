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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.scripting.ScriptingException;
import org.apache.ibatis.type.SimpleTypeRegistry;

import java.util.regex.Pattern;

/**
 * 包含“$｛｝”占位符的动态 SQL 节点。文本sql动态sql节点，封装纯文本数据。（叶子节点）
 *
 * 相比 StaticTextSqlNode 的实现来说，TextSqlNode 不确定是否为静态文本，
 * 所以提供 #isDynamic() 方法，进行判断是否为动态文本
 * @author Clinton Begin
 */
public class TextSqlNode implements SqlNode {
  /**
   * 带 ${} 属性的动态sql语句数据
   */
  private final String text;
  /**
   * 目前该属性只在单元测试中使用，暂时无视
   */
  private final Pattern injectionFilter;

  public TextSqlNode(String text) {
    this(text, null);
  }

  public TextSqlNode(String text, Pattern injectionFilter) {
    this.text = text;
    this.injectionFilter = injectionFilter;
  }

  /**
   * 判断是否为 动态sql
   * @return
   */
  public boolean isDynamic() {
    //1.创建一个 TokenParser 对象，解析 ${},但实现什么都没做，只标记是否动态
    DynamicCheckerTokenParser checker = new DynamicCheckerTokenParser();
    //2.创建 GenericTokenParser 对象
    GenericTokenParser parser = createParser(checker);
    //3.解析 text ,但未改变原 text 字符串
    parser.parse(text);
    //4.返回是否为动态字符串
    return checker.isDynamic();
  }

  @Override
  public boolean apply(DynamicContext context) {
    //1.创建 BindingTokenParser 对象（用于解析处理${XXX}内容）
    //2.创建 GenericTokenParser 对象（用于找到占位符并通过传入的BindingTokenParser解析）
    GenericTokenParser parser = createParser(new BindingTokenParser(context, injectionFilter));
    //3.执行解析
    //4.将解析后的结果，添加到 DynamicContext.sqlBuilder 中
    context.appendSql(parser.parse(text));
    return true;
  }

  /**
   * 创建 GenericTokenParser 对象
   * 通过这个方法，只要存在 ${xxx} 对，就认为是动态文本
   * @param handler
   * @return
   */
  private GenericTokenParser createParser(TokenHandler handler) {
    return new GenericTokenParser("${", "}", handler);
  }

  private static class BindingTokenParser implements TokenHandler {

    private DynamicContext context;
    private Pattern injectionFilter;

    public BindingTokenParser(DynamicContext context, Pattern injectionFilter) {
      this.context = context;
      this.injectionFilter = injectionFilter;
    }

    @Override
    public String handleToken(String content) {
      //初始化 value 属性到 context 中
      //1.获取参数
      Object parameter = context.getBindings().get("_parameter");
      //2.参数为 null，则放入 value,null 映射到context
      if (parameter == null) {
        context.getBindings().put("value", null);
      } else if (SimpleTypeRegistry.isSimpleType(parameter.getClass())) {
        //2.参数为简单类型，则放入 value,parameter 映射到context
        context.getBindings().put("value", parameter);
      }
      //3.使用 OGNL 表达式，获取对应的值(一般content为value)
      Object value = OgnlCache.getValue(content, context.getBindings());
      String srtValue = value == null ? "" : String.valueOf(value); // issue #274 return "" instead of "null"
      //检测合法性
      checkInjection(srtValue);
      //4.返回该值
      return srtValue;
    }

    private void checkInjection(String value) {
      if (injectionFilter != null && !injectionFilter.matcher(value).matches()) {
        throw new ScriptingException("Invalid input. Please conform to regex" + injectionFilter.pattern());
      }
    }
  }

  private static class DynamicCheckerTokenParser implements TokenHandler {

    /**
     * 是否为动态文本
     */
    private boolean isDynamic;

    public DynamicCheckerTokenParser() {
      // Prevent Synthetic Access
    }

    public boolean isDynamic() {
      return isDynamic;
    }

    @Override
    public String handleToken(String content) {
      //当检测到 token ,标记为动态文本
      this.isDynamic = true;
      return null;
    }
  }

}
