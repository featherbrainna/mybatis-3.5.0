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
package org.apache.ibatis.scripting.defaults;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.scripting.xmltags.DynamicContext;
import org.apache.ibatis.scripting.xmltags.DynamicSqlSource;
import org.apache.ibatis.scripting.xmltags.SqlNode;
import org.apache.ibatis.session.Configuration;

import java.util.HashMap;

/**
 * 原始的 SqlSource 实现类
 * Static SqlSource. It is faster than {@link DynamicSqlSource} because mappings are
 * calculated during startup.
 *
 * @since 3.2.0
 * @author Eduardo Macarron
 */
public class RawSqlSource implements SqlSource {

  /**
   * StaticSqlSource 对象
   */
  private final SqlSource sqlSource;

  public RawSqlSource(Configuration configuration, SqlNode rootSqlNode, Class<?> parameterType) {
    //调用 getSql() 方法，完成SQL语句的拼装和初步解析
    this(configuration, getSql(configuration, rootSqlNode), parameterType);
  }

  public RawSqlSource(Configuration configuration, String sql, Class<?> parameterType) {
    //1.创建 SqlSourceBuilder 对象，完成占位符解析和替换
    SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
    //2.获取参数类型。对于非动态SQL根据 SQL节点属性 传入参数类型获取参数类型
    Class<?> clazz = parameterType == null ? Object.class : parameterType;
    //3.初始化 sqlSource 属性为 StaticSqlSource 对象
    sqlSource = sqlSourceParser.parse(sql, clazz, new HashMap<>());
  }

  /**
   * 拼装 sql 语句并返回
   * @param configuration Configuration对象
   * @param rootSqlNode SqlNode对象
   * @return
   */
  private static String getSql(Configuration configuration, SqlNode rootSqlNode) {
    // 创建 DynamicContext 对象
    DynamicContext context = new DynamicContext(configuration, null);
    // 解析出 SqlSource 对象
    rootSqlNode.apply(context);
    // 获得 sql
    return context.getSql();
  }

  /**
   * 传入SQL实参获取 BoundSql 对象
   * @param parameterObject 用户传入的SQL实参
   * @return BoundSql对象
   */
  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    //实际调用的是 StaticSqlSource.getBoundSql(parameterObject) 方法
    return sqlSource.getBoundSql(parameterObject);
  }

}
