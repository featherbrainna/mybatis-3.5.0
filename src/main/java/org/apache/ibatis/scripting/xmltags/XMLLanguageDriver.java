/**
 *    Copyright 2009-2015 the original author or authors.
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

import org.apache.ibatis.builder.xml.XMLMapperEntityResolver;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * 实现 LanguageDriver 接口，XML 语言驱动实现类
 * @author Eduardo Macarron
 */
public class XMLLanguageDriver implements LanguageDriver {

  /**
   * 创建 ParameterHandler 对象
   * @param mappedStatement The mapped statement that is being executed
   * @param parameterObject The input parameter object (can be null)
   * @param boundSql The resulting SQL once the dynamic language has been executed.
   * @return
   */
  @Override
  public ParameterHandler createParameterHandler(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql) {
    // 创建 DefaultParameterHandler 对象
    return new DefaultParameterHandler(mappedStatement, parameterObject, boundSql);
  }

  /**
   * 依据xml文件中的 script 节点创建 SqlSource 对象。底层依赖 XMLScriptBuilder 对象实现
   * 由 XMLStatementBuilder 调用
   * @param configuration The MyBatis configuration
   * @param script XNode parsed from a XML file
   * @param parameterType input parameter type got from a mapper method or specified in the parameterType xml attribute. Can be null.
   * @return
   */
  @Override
  public SqlSource createSqlSource(Configuration configuration, XNode script, Class<?> parameterType) {
    //1.创建 XMLScriptBuilder 对象
    XMLScriptBuilder builder = new XMLScriptBuilder(configuration, script, parameterType);
    //2.解析创建 SqlSource 对象（DynamicSqlSource或RawSqlSource对象）
    return builder.parseScriptNode();
  }

  /**
   * 依据注解中的 script 字符串创建 SqlSource 对象。
   * 由 MapperAnnotationBuilder 调用
   * @param configuration The MyBatis configuration
   * @param script The content of the annotation
   * @param parameterType input parameter type got from a mapper method or specified in the parameterType xml attribute. Can be null.
   * @return
   */
  @Override
  public SqlSource createSqlSource(Configuration configuration, String script, Class<?> parameterType) {
    // issue #3
    //1.如果注解内容以 <script> 开头，使用 XML 配置的方式，使用动态 SQL
    if (script.startsWith("<script>")) {
      //1.1创建 XPathParser 对象，解析出 script 节点
      XPathParser parser = new XPathParser(script, false, configuration.getVariables(), new XMLMapperEntityResolver());
      //1.2调用上面的 #createSqlSource(...) 方法，创建 SqlSource 对象
      return createSqlSource(configuration, parser.evalNode("/script"), parameterType);
    } else {
      // issue #127
      //2.如果注解内容不是以 <script> 开头
      //2.1属性变量替换
      script = PropertyParser.parse(script, configuration.getVariables());
      //2.2创建 TextSqlNode 对象
      TextSqlNode textSqlNode = new TextSqlNode(script);
      //2.3如果是 动态SQL（包含未解析的${}） ，则创建 DynamicSqlSource 对象
      if (textSqlNode.isDynamic()) {
        return new DynamicSqlSource(configuration, textSqlNode);
      } else {
        //2.3如果是 非动态SQL ，则创建 RawSqlSource 对象
        return new RawSqlSource(configuration, script, parameterType);
      }
    }
  }

}
