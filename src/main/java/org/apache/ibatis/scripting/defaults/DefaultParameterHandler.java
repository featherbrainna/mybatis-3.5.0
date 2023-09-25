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
package org.apache.ibatis.scripting.defaults;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeException;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * 实现 ParameterHandler 接口，默认唯一的 ParameterHandler 实现类
 * 由 {@link org.apache.ibatis.scripting.xmltags.XMLLanguageDriver#createParameterHandler(MappedStatement, Object, BoundSql)} 调用
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class DefaultParameterHandler implements ParameterHandler {

  private final TypeHandlerRegistry typeHandlerRegistry;

  /**
   * MappedStatement 对象
   */
  private final MappedStatement mappedStatement;
  /**
   * 参数对象
   */
  private final Object parameterObject;
  /**
   * BoundSql 对象
   */
  private final BoundSql boundSql;
  private final Configuration configuration;

  /**
   * 构造器。初始化所有属性
   * @param mappedStatement MappedStatement对象
   * @param parameterObject 参数对象
   * @param boundSql BoundSql对象
   */
  public DefaultParameterHandler(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql) {
    this.mappedStatement = mappedStatement;
    this.configuration = mappedStatement.getConfiguration();
    this.typeHandlerRegistry = mappedStatement.getConfiguration().getTypeHandlerRegistry();
    this.parameterObject = parameterObject;
    this.boundSql = boundSql;
  }

  @Override
  public Object getParameterObject() {
    return parameterObject;
  }

  /**
   * 给 PreparedStatement对象 设置参数值
   * @param ps PreparedStatement 对象
   */
  @Override
  public void setParameters(PreparedStatement ps) {
    ErrorContext.instance().activity("setting parameters").object(mappedStatement.getParameterMap().getId());
    //1.从boundSql对象获取 ParameterMapping 集合。即sql语句中的每个"#{}"解析后的对象
    List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    if (parameterMappings != null) {
      //2.遍历 ParameterMapping 集合。开始给 PreparedStatement 对象逐一设置参数
      for (int i = 0; i < parameterMappings.size(); i++) {
        //3.获取集合中 ParameterMapping 对象
        ParameterMapping parameterMapping = parameterMappings.get(i);
        //4.ParameterMapping的 mode 属性不为 out，才设置参数
        if (parameterMapping.getMode() != ParameterMode.OUT) {
          Object value;
          //4.1获取ParameterMapping的参数名
          String propertyName = parameterMapping.getProperty();
          //4.2根据参数名获取参数值
          if (boundSql.hasAdditionalParameter(propertyName)) { // issue #448 ask first for additional params
            //4.2.1从 boundSql.additionalParameters 中根据参数名获取参数值
            value = boundSql.getAdditionalParameter(propertyName);
          } else if (parameterObject == null) {
            //4.2.2parameterObject参数对象为空value也为空
            value = null;
          } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
            //4.2.3单个参数
            value = parameterObject;
          } else {
            //4.2.4多个参数从 parameterObject 映射对象中根据参数名获取参数值
            MetaObject metaObject = configuration.newMetaObject(parameterObject);
            value = metaObject.getValue(propertyName);
          }
          //4.3获取 typeHandler、jdbcType 属性
          TypeHandler typeHandler = parameterMapping.getTypeHandler();
          JdbcType jdbcType = parameterMapping.getJdbcType();
          if (value == null && jdbcType == null) {
            jdbcType = configuration.getJdbcTypeForNull();
          }
          //4.4依赖 typeHandler 设置 ? 占位符的参数（SQL执行核心流程：设置SQL参数！！！）
          try {
            typeHandler.setParameter(ps, i + 1, value, jdbcType);
          } catch (TypeException e) {
            throw new TypeException("Could not set parameters for mapping: " + parameterMapping + ". Cause: " + e, e);
          } catch (SQLException e) {
            throw new TypeException("Could not set parameters for mapping: " + parameterMapping + ". Cause: " + e, e);
          }
        }
      }
    }
  }

}
