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
package org.apache.ibatis.builder;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

import java.util.List;

/**
 * 静态的 SqlSource 实现类
 * @author Clinton Begin
 */
public class StaticSqlSource implements SqlSource {

  /**
   * 静态的 SQL，sql语句带 "?" 占位符
   */
  private final String sql;
  /**
   * ParameterMapping 集合
   */
  private final List<ParameterMapping> parameterMappings;
  /**
   * mybatis全局配置对象
   */
  private final Configuration configuration;

  public StaticSqlSource(Configuration configuration, String sql) {
    this(configuration, sql, null);
  }

  public StaticSqlSource(Configuration configuration, String sql, List<ParameterMapping> parameterMappings) {
    this.sql = sql;
    this.parameterMappings = parameterMappings;
    this.configuration = configuration;
  }

  /**
   * 底层获取 BoundSql 的方法
   * 由 {@link org.apache.ibatis.scripting.xmltags.DynamicSqlSource#getBoundSql(Object)} 调用
   * 由 {@link org.apache.ibatis.scripting.defaults.RawSqlSource#getBoundSql(Object)} 调用
   * @param parameterObject 实际SQl传入的参数
   * @return
   */
  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    // 创建 BoundSql 对象
    return new BoundSql(configuration, sql, parameterMappings, parameterObject);
  }

}
