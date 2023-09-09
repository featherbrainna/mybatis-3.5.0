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
package org.apache.ibatis.builder.annotation;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.session.Configuration;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

/**
 * 基于方法上的 @ProviderXXX 注解的 SqlSource 实现类
 * 由 MapperAnnotationBuilder 调用，解析 @XXXProvider 注解为 SqlSource 对象。
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class ProviderSqlSource implements SqlSource {

  private final Configuration configuration;
  /**
   * SqlSourceBuilder 对象
   */
  private final SqlSourceBuilder sqlSourceParser;
  /**
   * `@ProviderXXX` 注解的对应的类class对象
   */
  private final Class<?> providerType;
  /**
   * `@ProviderXXX` 注解的对应的方法反射对象
   */
  private Method providerMethod;
  /**
   * `@ProviderXXX` 注解的对应的方法的参数名数组
   */
  private String[] providerMethodArgumentNames;
  /**
   * `@ProviderXXX` 注解的对应的方法的参数类型数组
   */
  private Class<?>[] providerMethodParameterTypes;
  /**
   * 若 {@link #providerMethodParameterTypes} 参数有 ProviderContext 类型的，创建 ProviderContext 对象
   */
  private ProviderContext providerContext;
  /**
   * {@link #providerMethodParameterTypes} 参数中，ProviderContext 类型的参数，在数组中的位置
   */
  private Integer providerContextIndex;

  /**
   * @deprecated Please use the {@link #ProviderSqlSource(Configuration, Object, Class, Method)} instead of this.
   */
  @Deprecated
  public ProviderSqlSource(Configuration configuration, Object provider) {
    this(configuration, provider, null, null);
  }

  /**
   * 构造器
   * @since 3.4.5
   */
  public ProviderSqlSource(Configuration configuration, Object provider, Class<?> mapperType, Method mapperMethod) {
    String providerMethodName;
    try {
      //1.初始化 configuration、sqlSourceParser 属性，创建 SqlSourceBuilder 对象
      this.configuration = configuration;
      this.sqlSourceParser = new SqlSourceBuilder(configuration);
      //2.获取 @ProviderXXX 注解对应的类，初始化 providerType 属性
      this.providerType = (Class<?>) provider.getClass().getMethod("type").invoke(provider);
      //3.获取 @ProviderXXX 注解对应的方法名相关信息
      providerMethodName = (String) provider.getClass().getMethod("method").invoke(provider);
      //4.遍历 @ProviderXXX 注解指定类的所有方法反射对象
      for (Method m : this.providerType.getMethods()) {
        //5.如果方法名与@ProviderXXX注解指定的方法名一致，且方法返回值类型为 CharSequence，则初始化属性
        if (providerMethodName.equals(m.getName()) && CharSequence.class.isAssignableFrom(m.getReturnType())) {
          //已经重复初始化，抛出异常
          if (providerMethod != null){
            throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
                    + providerMethodName + "' is found multiple in SqlProvider '" + this.providerType.getName()
                    + "'. Sql provider method can not overload.");
          }
          //初始化 providerMethod、providerMethodArgumentNames、providerMethodParameterTypes 属性
          this.providerMethod = m;
          this.providerMethodArgumentNames = new ParamNameResolver(configuration, m).getNames();
          this.providerMethodParameterTypes = m.getParameterTypes();
        }
      }
    } catch (BuilderException e) {
      throw e;
    } catch (Exception e) {
      throw new BuilderException("Error creating SqlSource for SqlProvider.  Cause: " + e, e);
    }
    //6.如果 providerMethod 未初始化成功抛出异常
    if (this.providerMethod == null) {
      throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
          + providerMethodName + "' not found in SqlProvider '" + this.providerType.getName() + "'.");
    }
    //7.遍历 providerMethodParameterTypes 属性数组，初始化属性 providerContext、providerContextIndex
    for (int i = 0; i< this.providerMethodParameterTypes.length; i++) {
      //7.1获取参数类型
      Class<?> parameterType = this.providerMethodParameterTypes[i];
      //7.2如果参数类型为 ProviderContext
      if (parameterType == ProviderContext.class) {
        if (this.providerContext != null){
          throw new BuilderException("Error creating SqlSource for SqlProvider. ProviderContext found multiple in SqlProvider method ("
              + this.providerType.getName() + "." + providerMethod.getName()
              + "). ProviderContext can not define multiple in SqlProvider method argument.");
        }
        //7.3依据 mapperType、mapperMethod 创建 ProviderContext 对象并初始化 providerContext 属性
        this.providerContext = new ProviderContext(mapperType, mapperMethod);
        //7.4初始化 providerContextIndex 属性
        this.providerContextIndex = i;
      }
    }
  }

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    //1.创建 StaticSqlSource 对象
    SqlSource sqlSource = createSqlSource(parameterObject);
    //2.获得 BoundSql 对象
    return sqlSource.getBoundSql(parameterObject);
  }

  /**
   * 创建 StaticSqlSource 对象
   * 通过 @ProviderXXX 注解的指定类的指定方法，动态生成 SQL
   * 1.通过invokeProviderMethod方法，构建动态拼接 sql 语句
   * 2.通过SqlSourceBuilder.parse方法，将 #{} 解析内容并替换最后以StaticSqlSource返回
   * @param parameterObject
   * @return
   */
  private SqlSource createSqlSource(Object parameterObject) {
    try {
      //1.获取方法参数个数
      int bindParameterCount = providerMethodParameterTypes.length - (providerContext == null ? 0 : 1);
      //2.原始sql记录
      String sql;
      if (providerMethodParameterTypes.length == 0) {
        //2.1如果 provider注解指定的方法 参数个数为0，则直接调用invokeProviderMethod()
        sql = invokeProviderMethod();
      } else if (bindParameterCount == 0) {
        //2.2如果 provider注解指定的方法 除providerContext的参数个数为0，则直接调用invokeProviderMethod(providerContext)
        sql = invokeProviderMethod(providerContext);
      } else if (bindParameterCount == 1 &&
              (parameterObject == null || providerMethodParameterTypes[providerContextIndex == null || providerContextIndex == 1 ? 0 : 1].isAssignableFrom(parameterObject.getClass()))) {
        //2.3如果 provider注解指定的方法 除providerContext的参数个数为1，且参数类型合法
        sql = invokeProviderMethod(extractProviderMethodArguments(parameterObject));
      } else if (parameterObject instanceof Map) {
        //2.4如果用户传入的参数类型为 Map
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) parameterObject;
        sql = invokeProviderMethod(extractProviderMethodArguments(params, providerMethodArgumentNames));
      } else {
        throw new BuilderException("Error invoking SqlProvider method ("
                + providerType.getName() + "." + providerMethod.getName()
                + "). Cannot invoke a method that holds "
                + (bindParameterCount == 1 ? "named argument(@Param)": "multiple arguments")
                + " using a specifying parameterObject. In this case, please specify a 'java.util.Map' object.");
      }
      //3.获取用户传入参数类型
      Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
      //4.替换掉 SQL 上的属性${}
      //5.使用 SqlSourceBuilder 解析出 StaticSqlSource 对象
      return sqlSourceParser.parse(replacePlaceholder(sql), parameterType, new HashMap<>());
    } catch (BuilderException e) {
      throw e;
    } catch (Exception e) {
      throw new BuilderException("Error invoking SqlProvider method ("
          + providerType.getName() + "." + providerMethod.getName()
          + ").  Cause: " + e, e);
    }
  }

  /**
   * 获得方法参数值数组（如果只有一个用户传入的参数）
   * @param parameterObject 用户传入参数对象
   * @return 参数数组
   */
  private Object[] extractProviderMethodArguments(Object parameterObject) {
    //如果 providerContext 不为空，则返回args数组，大小为2
    if (providerContext != null) {
      Object[] args = new Object[2];
      args[providerContextIndex == 0 ? 1 : 0] = parameterObject;
      args[providerContextIndex] = providerContext;
      return args;
    } else {
      //如果 providerContext 为空，则返回args数组，大小为1
      return new Object[] { parameterObject };
    }
  }

  /**
   * 获得方法参数值数组（如果有多个用户传入的参数）
   * @param params 用户传入参数对象
   * @param argumentNames 方法参数名
   * @return 参数值数组
   */
  private Object[] extractProviderMethodArguments(Map<String, Object> params, String[] argumentNames) {
    Object[] args = new Object[argumentNames.length];
    for (int i = 0; i < args.length; i++) {
      if (providerContextIndex != null && providerContextIndex == i) {
        args[i] = providerContext;
      } else {
        //依据参数名获取参数值，并依次赋值给参数值数组
        args[i] = params.get(argumentNames[i]);
      }
    }
    return args;
  }

  /**
   * 传入参数，执行 @Provider 注解指定的方法生成 sql 语句
   * @param args 参数数组
   * @return sql 语句
   * @throws Exception
   */
  private String invokeProviderMethod(Object... args) throws Exception {
    Object targetObject = null;
    //1.依据 providerType 指定的provider类获得对象
    if (!Modifier.isStatic(providerMethod.getModifiers())) {
      targetObject = providerType.newInstance();
    }
    //2.通过 providerMethod 反射方法对象执行方法
    CharSequence sql = (CharSequence) providerMethod.invoke(targetObject, args);
    //3.返回sql语句
    return sql != null ? sql.toString() : null;
  }

  /**
   * 替换掉 SQL 语句上的属性
   * @param sql
   * @return
   */
  private String replacePlaceholder(String sql) {
    return PropertyParser.parse(sql, configuration.getVariables());
  }

}
