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
package org.apache.ibatis.executor.keygen;

import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.ArrayUtil;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.defaults.DefaultSqlSession.StrictMap;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * KeyGenerator 实现类，适用于 MySQL、H2 主键
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class Jdbc3KeyGenerator implements KeyGenerator {

  /**
   * 共享的单例
   * A shared instance.
   *
   * @since 3.4.3
   */
  public static final Jdbc3KeyGenerator INSTANCE = new Jdbc3KeyGenerator();

  /**
   * 前置处理，空实现。因为对于 Jdbc3KeyGenerator 类的主键，是在 SQL 执行后才生成
   */
  @Override
  public void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    // do nothing
  }

  /**
   * 后置处理。处理返回的自增主键，单个 parameter 参数，可以认为是批量的一个特例
   * @param executor 执行器对象
   * @param ms MappedStatement对象
   * @param stmt Statement对象
   * @param parameter 参数对象
   */
  @Override
  public void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    processBatch(ms, stmt, parameter);
  }

  /**
   * 批量处理自增主键，设置到参数对象中
   * @param ms MappedStatement对象
   * @param stmt Statement对象
   * @param parameter 参数对象
   */
  public void processBatch(MappedStatement ms, Statement stmt, Object parameter) {
    //1.获取SQL节点 keyProperties 属性配置，如果为空直接返回，说明无需主键
    final String[] keyProperties = ms.getKeyProperties();
    if (keyProperties == null || keyProperties.length == 0) {
      return;
    }
    //2.SQL节点 keyProperties 属性不为空时，执行以下处理
    //3.获取数据库自动生成的主键
    try (ResultSet rs = stmt.getGeneratedKeys()) {
      //获取配置对象
      final Configuration configuration = ms.getConfiguration();
      //4.如果主键ResultSet元数据信息表头个数大于等于 keyProperties 属性数组大小（初步校验生成的主键是否符合指定的主键）
      if (rs.getMetaData().getColumnCount() >= keyProperties.length) {
        //4.1获取唯一的参数对象
        Object soleParam = getSoleParameter(parameter);
        //4.2如果 soleParam 不为空，则设置主键到参数 soleParam 中（mapper接口单一参数）
        if (soleParam != null) {
          assignKeysToParam(configuration, rs, keyProperties, soleParam);
        } else {
          //4.2如果 soleParam 为空，则设置主键到参数 parameter 中 （mapper接口多参数）
          assignKeysToOneOfParams(configuration, rs, keyProperties, (Map<?, ?>) parameter);
        }
      }
    } catch (Exception e) {
      throw new ExecutorException("Error getting generated key or setting result to parameter object. Cause: " + e, e);
    }
  }

  /**
   * 设置主键到多参数的其中一个 参数对象 中（mapper接口多个参数时调用）
   * 从 keyProperties 中解析出参数名后，再委托给 assignKeysToParam()方法处理
   * 1.插入单个元素时，keyProperty指定"参数名.属性名"。如多个参数为 User user,OtherType other，则keyProperty为user.id
   * 2.插入多个元素时，keyProperty指定"集合的参数名.属性名"。如多个参数为 List<User> users,OtherType other，则则keyProperty为users.id
   * @param configuration 配置对象
   * @param rs 主键结果集对象
   * @param keyProperties 主键属性名数组
   * @param paramMap 参数对象Map类型
   * @throws SQLException
   */
  protected void assignKeysToOneOfParams(final Configuration configuration, ResultSet rs, final String[] keyProperties,
      Map<?, ?> paramMap) throws SQLException {
    // Assuming 'keyProperty' includes the parameter name. e.g. 'param.id'.
    //1.获取主键属性名数组第一个元素的 "." 的下标位置
    int firstDot = keyProperties[0].indexOf('.');
    //2.如果主键属性名中不存在 "."（即没有指定参数名），则抛出异常
    if (firstDot == -1) {
      throw new ExecutorException(
          "Could not determine which parameter to assign generated keys to. "
              + "Note that when there are multiple parameters, 'keyProperty' must include the parameter name (e.g. 'param.id'). "
              + "Specified key properties are " + ArrayUtil.toString(keyProperties) + " and available parameters are "
              + paramMap.keySet());
    }
    //3.获取 第一个主键属性名 的 参数名
    String paramName = keyProperties[0].substring(0, firstDot);
    Object param;//参数对象
    //4.如果参数对象Map中包含此参数名key，则获取此参数对象值
    if (paramMap.containsKey(paramName)) {
      param = paramMap.get(paramName);
    } else {
      //4.如果参数对象Map中不包含此参数名key，则抛出异常
      throw new ExecutorException("Could not find parameter '" + paramName + "'. "
          + "Note that when there are multiple parameters, 'keyProperty' must include the parameter name (e.g. 'param.id'). "
          + "Specified key properties are " + ArrayUtil.toString(keyProperties) + " and available parameters are "
          + paramMap.keySet());
    }
    // Remove param name from 'keyProperty' string. e.g. 'param.id' -> 'id'
    //6.将 keyProperties 中的参数名去除
    String[] modifiedKeyProperties = new String[keyProperties.length];
    //6.遍历 keyProperties 数组
    for (int i = 0; i < keyProperties.length; i++) {
      //7.如果主键属性名有 "." 字符，且以解析出的参数名开头，则截取后面的属性名部分赋值给 modifiedKeyProperties 数组
      if (keyProperties[i].charAt(firstDot) == '.' && keyProperties[i].startsWith(paramName)) {
        modifiedKeyProperties[i] = keyProperties[i].substring(firstDot + 1);
      } else {
        //7.其它主键属性名情况，则抛出异常
        throw new ExecutorException("Assigning generated keys to multiple parameters is not supported. "
            + "Note that when there are multiple parameters, 'keyProperty' must include the parameter name (e.g. 'param.id'). "
            + "Specified key properties are " + ArrayUtil.toString(keyProperties) + " and available parameters are "
            + paramMap.keySet());
      }
    }
    //8.委托给assignKeysToParam方法，来赋值给这个单独参数对象
    assignKeysToParam(configuration, rs, modifiedKeyProperties, param);
  }

  /**
   * 设置主键到 参数对象 中（mapper接口单个参数时调用）
   * 1.插入一个元素时，keyProperty 直接指定这个单个参数元素的属性。如参数为 USer 类型，则keyProperty为id
   * 2.插入多个元素时，keyProperty 直接指定这个参数集合对象中每个元素的属性。如参数为 List<User> 类型，则keyProperty为id
   * @param configuration 配置对象
   * @param rs 主键结果集对象
   * @param keyProperties 主键属性名数组
   * @param param 参数对象
   * @throws SQLException
   */
  private void assignKeysToParam(final Configuration configuration, ResultSet rs, final String[] keyProperties,
      Object param)
      throws SQLException {
    //1.获取类型处理器
    final TypeHandlerRegistry typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    //2.获取主键结果集元信息对象
    final ResultSetMetaData rsmd = rs.getMetaData();
    // Wrap the parameter in Collection to normalize the logic.
    //3.将 单独的参数对象 包装成 集合对象
    Collection<?> paramAsCollection = null;
    //3.如果参数对象类型为 对象数组类型，将其封装为list集合
    if (param instanceof Object[]) {
      paramAsCollection = Arrays.asList((Object[]) param);
    } else if (!(param instanceof Collection)) {
      //3.如果参数类型为 非集合类型，将其封装为list集合
      paramAsCollection = Arrays.asList(param);
    } else {
      //3.如果参数类型为 集合类型，直接强制转型为 Collection
      paramAsCollection = (Collection<?>) param;
    }
    //4.遍历参数集合 paramAsCollection ，开始通过MetaObject设置参数主键
    TypeHandler<?>[] typeHandlers = null;
    for (Object obj : paramAsCollection) {
      //5.移动游标遍历 rs 结果集
      if (!rs.next()) {
        break;
      }
      //6.获取参数集合中单个参数对象的 MetaObject，实现对 参数对象 的属性访问
      MetaObject metaParam = configuration.newMetaObject(obj);
      //7.获取 typeHandlers 数组
      if (typeHandlers == null) {
        typeHandlers = getTypeHandlers(typeHandlerRegistry, metaParam, keyProperties, rsmd);
      }
      //8.将主键填充到参数对象
      populateKeys(rs, metaParam, keyProperties, typeHandlers);
    }
  }

  /**
   * 获取唯一参数对象
   * 如果获取不到唯一参数对象，则返回null
   * @param parameter 参数对象
   * @return 唯一的参数对象
   */
  private Object getSoleParameter(Object parameter) {
    //1.如果参数对象类型非 Map 对象，则直接返回 parameter 对象
    if (!(parameter instanceof ParamMap || parameter instanceof StrictMap)) {
      return parameter;
    }
    //2.如果是 Map 类型参数，则获取第一个元素的值
    // 如果Map有多个映射，则说明取不到唯一的参数对象，则返回null
    Object soleParam = null;
    for (Object paramValue : ((Map<?, ?>) parameter).values()) {
      if (soleParam == null) {
        soleParam = paramValue;
      } else if (soleParam != paramValue) {
        soleParam = null;
        break;
      }
    }
    return soleParam;
  }

  /**
   * 获取类型处理器TypeHandler数组
   * 依据参数对象的主键属性类型，获取对应的TypeHandler对象
   * @param typeHandlerRegistry TypeHandlerRegistry注册器对象
   * @param metaParam 单个参数对象对应的 MetaObject 对象
   * @param keyProperties 主键对应的参数属性名
   * @param rsmd 主键结果集元信息
   * @return TypeHandler数组
   * @throws SQLException
   */
  private TypeHandler<?>[] getTypeHandlers(TypeHandlerRegistry typeHandlerRegistry, MetaObject metaParam, String[] keyProperties, ResultSetMetaData rsmd) throws SQLException {
    //1.创建 TypeHandler数组，大小为指定的keyProperties个数
    TypeHandler<?>[] typeHandlers = new TypeHandler<?>[keyProperties.length];
    //2.遍历 keyProperties 数组
    for (int i = 0; i < keyProperties.length; i++) {
      //3.从 metaParam 判断该 keyProperties 有setter方法
      if (metaParam.hasSetter(keyProperties[i])) {
        //3.1获取属性的类型class
        Class<?> keyPropertyType = metaParam.getSetterType(keyProperties[i]);
        //3.2从 typeHandlerRegistry 获取指定java类型指定jdbc类型的 TypeHandler 对象并初始化数组
        typeHandlers[i] = typeHandlerRegistry.getTypeHandler(keyPropertyType, JdbcType.forCode(rsmd.getColumnType(i + 1)));
      } else {
        //3.从 metaParam 判断该 keyProperties 无setter方法
        throw new ExecutorException("No setter found for the keyProperty '" + keyProperties[i] + "' in '"
            + metaParam.getOriginalObject().getClass().getName() + "'.");
      }
    }
    return typeHandlers;
  }

  /**
   * 底层真正的设置参数主键
   * @param rs 主键结果集
   * @param metaParam 参数对象对应的MetaObject对象
   * @param keyProperties 主键属性名数组
   * @param typeHandlers 对应的类型处理器数组
   * @throws SQLException
   */
  private void populateKeys(ResultSet rs, MetaObject metaParam, String[] keyProperties, TypeHandler<?>[] typeHandlers) throws SQLException {
    //1.遍历 keyProperties 数组，设置所有主键属性
    for (int i = 0; i < keyProperties.length; i++) {
      //2.获取属性名
      String property = keyProperties[i];
      //3.获取 类型处理器TypeHandler 对象
      TypeHandler<?> th = typeHandlers[i];
      //4.如果 类型处理器TypeHandler 不为空
      if (th != null) {
        //4.1从 rs 获取属性值
        Object value = th.getResult(rs, i + 1);
        //4.2设置参数对象对应属性值
        metaParam.setValue(property, value);
      }
    }
  }

}
