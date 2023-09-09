/**
 *    Copyright 2009-2017 the original author or authors.
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

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

import java.util.Map;

/**
 * 动态的 SqlSource 实现类
 * @author Clinton Begin
 */
public class DynamicSqlSource implements SqlSource {

  private final Configuration configuration;
  /**
   * 根 SqlNode 对象
   */
  private final SqlNode rootSqlNode;

  public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
    this.configuration = configuration;
    this.rootSqlNode = rootSqlNode;
  }

  /**
   * 传入SQL实参获取 BoundSql 对象
   * @param parameterObject 用户传入的SQL实参
   * @return BoundSql对象
   */
  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    //1.创建 DynamicContext 对象，parameterObject 是用户传入的实参
    DynamicContext context = new DynamicContext(configuration, parameterObject);
    //2.应用节点。通过调用 rootSqlNode.apply()方法调用整个树形结构中全部 SqlNode.apply() 方法
    // 每个 SqlNode 的 apply() 方法都将解析得到的 SQL 语句片段追加到 context 中，最终通过 context.getSql() 的到完整的SQL语句
    rootSqlNode.apply(context);
    //3.创建 SqlSourceBuilder 对象，用于构建 StaticSqlSource 对象
    SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
    //4.对于动态SQL根据参数对象获取参数类型
    Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
    //5.获取完整的sql，解析#{}其中的参数属性，并将sql语句中的"#{}"替换成"?"，最后解析后的sql创建 StaticSqlSource 对象
    SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());
    //6.传入SQL实参获取 BoundSql 对象。实际调用的是 StaticSqlSource.getBoundSql(parameterObject) 方法
    BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
    //7.将 DynamicContext.bindings 中的参数信息复制到其 additionalParameters 集合中保存
    for (Map.Entry<String, Object> entry : context.getBindings().entrySet()) {
      boundSql.setAdditionalParameter(entry.getKey(), entry.getValue());
    }
    //8.返回
    return boundSql;
  }

}
