/**
 *    Copyright 2009-2018 the original author or authors.
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

import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeAliasRegistry;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 基础构造器抽象类，为子类提供通用的工具类。
 * @author Clinton Begin
 */
public abstract class BaseBuilder {
  /**
   * mybatis全局配置对象。
   * 是mybatis初始化过程中的核心对象，几乎全部的配置信息都会保存到此对象
   */
  protected final Configuration configuration;
  /**
   * 类别名注册对象。
   * 在 mybatis-config.xml配置文件中可以使用<typeAliases>标签定义别名，
   * 定义的别名记录在此对象中
   */
  protected final TypeAliasRegistry typeAliasRegistry;
  /**
   * 类型转换处理器注册对象。
   * 在 mybatis-config.xml配置文件中可以使用<typeHandlers>标签定义TypeHandler器（完成类型转换），
   * 定义的TypeHandler记录在此对象中
   */
  protected final TypeHandlerRegistry typeHandlerRegistry;

  /**
   * 构造器，通过configuration对象初始化所有属性
   * @param configuration
   */
  public BaseBuilder(Configuration configuration) {
    this.configuration = configuration;
    this.typeAliasRegistry = this.configuration.getTypeAliasRegistry();
    this.typeHandlerRegistry = this.configuration.getTypeHandlerRegistry();
  }

  public Configuration getConfiguration() {
    return configuration;
  }

  /**
   * 创建正则表达式 Pattern 对象
   * @param regex 指定表达式
   * @param defaultValue 默认表达式
   * @return 正则表达式 Pattern 对象
   */
  protected Pattern parseExpression(String regex, String defaultValue) {
    return Pattern.compile(regex == null ? defaultValue : regex);
  }

  /**
   * 将字符串转换成对应的数据类型的值
   * @param value 原始字符串
   * @param defaultValue 默认值
   * @return 转换的数据类型值
   */
  protected Boolean booleanValueOf(String value, Boolean defaultValue) {
    return value == null ? defaultValue : Boolean.valueOf(value);
  }

  protected Integer integerValueOf(String value, Integer defaultValue) {
    return value == null ? defaultValue : Integer.valueOf(value);
  }

  protected Set<String> stringSetValueOf(String value, String defaultValue) {
    value = value == null ? defaultValue : value;
    return new HashSet<>(Arrays.asList(value.split(",")));
  }

  /**
   * 解析对应的 JdbcType 类型，根据枚举的名字创建 JdbcType 枚举对象
   * 【原理】：枚举类的静态方法：枚举类.valueof(枚举名)
   * @param alias jdbc类型枚举名，例如"CHAR"
   * @return JdbcType对象
   */
  protected JdbcType resolveJdbcType(String alias) {
    if (alias == null) {
      return null;
    }
    try {
      return JdbcType.valueOf(alias);
    } catch (IllegalArgumentException e) {
      throw new BuilderException("Error resolving JdbcType. Cause: " + e, e);
    }
  }

  /**
   * 根据枚举名解析对应的 ResultSetType 类型
   * @param alias ResultSetType枚举名
   * @return ResultSetType对象
   */
  protected ResultSetType resolveResultSetType(String alias) {
    if (alias == null) {
      return null;
    }
    try {
      return ResultSetType.valueOf(alias);
    } catch (IllegalArgumentException e) {
      throw new BuilderException("Error resolving ResultSetType. Cause: " + e, e);
    }
  }

  /**
   * 根据枚举名解析对应的 ParameterMode 类型
   * @param alias ParameterMode枚举名
   * @return ParameterMode对象
   */
  protected ParameterMode resolveParameterMode(String alias) {
    if (alias == null) {
      return null;
    }
    try {
      return ParameterMode.valueOf(alias);
    } catch (IllegalArgumentException e) {
      throw new BuilderException("Error resolving ParameterMode. Cause: " + e, e);
    }
  }

  /**
   * 创建指定对象
   * @param alias 类别名
   * @return
   */
  protected Object createInstance(String alias) {
    //1.获得指定别名类的class对象
    Class<?> clazz = resolveClass(alias);
    if (clazz == null) {
      return null;
    }
    try {
      //2.创建对象，重复调用resolveClass(alias)是考虑多线程避免使用旧数据别名映射
      return resolveClass(alias).newInstance();
    } catch (Exception e) {
      throw new BuilderException("Error creating instance. Cause: " + e, e);
    }
  }

  /**
   * 获得别名对应的类型class对象，底层调用 {@link #resolveAlias(String)} 实现
   * @param alias 类别名
   * @param <T> 类型
   * @return class对象
   */
  protected <T> Class<? extends T> resolveClass(String alias) {
    if (alias == null) {
      return null;
    }
    try {
      return resolveAlias(alias);
    } catch (Exception e) {
      throw new BuilderException("Error resolving class. Cause: " + e, e);
    }
  }

  protected TypeHandler<?> resolveTypeHandler(Class<?> javaType, String typeHandlerAlias) {
    if (typeHandlerAlias == null) {
      return null;
    }
    Class<?> type = resolveClass(typeHandlerAlias);
    if (type != null && !TypeHandler.class.isAssignableFrom(type)) {
      throw new BuilderException("Type " + type.getName() + " is not a valid TypeHandler because it does not implement TypeHandler interface");
    }
    @SuppressWarnings( "unchecked" ) // already verified it is a TypeHandler
    Class<? extends TypeHandler<?>> typeHandlerType = (Class<? extends TypeHandler<?>>) type;
    return resolveTypeHandler(javaType, typeHandlerType);
  }

  /**
   * 从 typeHandlerRegistry 中获得或创建对应的 TypeHandler 对象。
   * @param javaType java类型的class对象
   * @param typeHandlerType TypeHandler类型的class对象
   * @return TypeHandler对象
   */
  protected TypeHandler<?> resolveTypeHandler(Class<?> javaType, Class<? extends TypeHandler<?>> typeHandlerType) {
    if (typeHandlerType == null) {
      return null;
    }
    // javaType ignored for injected handlers see issue #746 for full detail
    //1.从 typeHandlerRegistry 中根据TypeHandler的class对象获取 TypeHandler 对象
    TypeHandler<?> handler = typeHandlerRegistry.getMappingTypeHandler(typeHandlerType);
    //2.如果不存在，使用typeHandlerRegistry进行创建 TypeHandler 对象
    if (handler == null) {
      // not in registry, create a new one
      handler = typeHandlerRegistry.getInstance(javaType, typeHandlerType);
    }
    return handler;
  }

  /**
   * 解析类别名为 class 对象，底层调用typeAliasRegistry实现
   * @param alias
   * @param <T>
   * @return
   */
  protected <T> Class<? extends T> resolveAlias(String alias) {
    return typeAliasRegistry.resolveAlias(alias);
  }
}
