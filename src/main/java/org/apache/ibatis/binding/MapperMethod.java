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
package org.apache.ibatis.binding;

import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Mapper 方法。在 Mapper 接口中，每个定义的方法，对应一个 MapperMethod 对象。
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 * @author Kazuki Shimizu
 */
public class MapperMethod {

  /**
   * SqlCommand对象，记录了sql语句的名称和类型（mapper.xml相关）
   */
  private final SqlCommand command;
  /**
   * MethodSignature对象，Mapper接口中对应方法的相关信息（mapper接口相关）
   */
  private final MethodSignature method;

  /**
   * 构造器，初始化 command 和 method 字段
   * @param mapperInterface mapper接口class对象
   * @param method Method反射对象
   * @param config mybatis全局配置对象
   */
  public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
    this.command = new SqlCommand(config, mapperInterface, method);
    this.method = new MethodSignature(config, mapperInterface, method);
  }

  /**
   * 执行sql,由Mapper接口的代理对象的底层的handler调用 {@link MapperProxy#invoke(Object, Method, Object[])} 本方法
   * 底层路由给SqlSession对象实现sql语句调用执行
   * 1.先根据 SQL 语句的类型路由
   * 2.再根据 方法返回值类型 路由
   * @param sqlSession sqlSession对象
   * @param args sql参数
   * @return
   */
  public Object execute(SqlSession sqlSession, Object[] args) {
    Object result;//记录结果
    //1.根据 SQL 语句的类型调用 sqlSession 对应的方法
    switch (command.getType()) {
      case INSERT: {
        //2.1使用 paramNameResolver 处理参数值数组，获取参数对象
    	Object param = method.convertArgsToSqlCommandParam(args);
    	//2.2执行 insert 操作
    	//调用 sqlSession.insert() 方法执行写操作，rowCountResult() 方法会根据method属性中记录的方法返回值类型对结果进行转换
        result = rowCountResult(sqlSession.insert(command.getName(), param));
        break;
      }
      case UPDATE: {
        //2.1使用 paramNameResolver 处理参数值数组，获取参数对象
        Object param = method.convertArgsToSqlCommandParam(args);
        //2.2执行 update 操作
        //调用 sqlSession.update() 方法，rowCountResult() 方法会根据method属性中记录的方法返回值类型对结果进行转换
        result = rowCountResult(sqlSession.update(command.getName(), param));
        break;
      }
      case DELETE: {
        //2.1使用 paramNameResolver 处理参数值数组，获取参数对象
        Object param = method.convertArgsToSqlCommandParam(args);
        //2.2执行 delete 操作
        //调用 sqlSession.delete() 方法，rowCountResult() 方法会根据method属性中记录的方法返回值类型对结果进行转换
        result = rowCountResult(sqlSession.delete(command.getName(), param));
        break;
      }
      case SELECT:
        //2.1依据 method方法属性的返回类型 进行分别处理
        //2.2如果方法返回值类型为Void且有ResultHandler参数，委托executeWithResultHandler(sqlSession, args)方法
        if (method.returnsVoid() && method.hasResultHandler()) {
          executeWithResultHandler(sqlSession, args);
          result = null;
        } else if (method.returnsMany()) {
          //2.2如果方法返回值为 collection类型 或者 数组类型，调用executeForMany(sqlSession, args)执行查询
          result = executeForMany(sqlSession, args);
        } else if (method.returnsMap()) {
          //2.2如果方法返回值为 Map类型，调用executeForMap(sqlSession, args)执行查询
          result = executeForMap(sqlSession, args);
        } else if (method.returnsCursor()) {
          //2.2如果方法返回值类型为 Cursor 类型，调用executeForCursor(sqlSession, args)执行查询
          result = executeForCursor(sqlSession, args);
        } else {
          //2.2如果方法返回值类型为其他返回类型
          //2.3使用 paramNameResolver 处理参数值数组，获取参数对象
          Object param = method.convertArgsToSqlCommandParam(args);
          //2.4调用 sqlSession.selectOne() 方法
          result = sqlSession.selectOne(command.getName(), param);
          //返回值类型为Optional，构造Optional对象
          if (method.returnsOptional() &&
              (result == null || !method.getReturnType().equals(result.getClass()))) {
            result = Optional.ofNullable(result);
          }
        }
        break;
      case FLUSH:
        //flush类型sql语句，调用sqlSession.flushStatements()
        result = sqlSession.flushStatements();
        break;
      default:
        //其他sql语句类型，抛出异常
        throw new BindingException("Unknown execution method for: " + command.getName());
    }
    //3.如果结果对象为空，且方法返回值为基本数据类型，且方法返回值类型不为void，则抛出异常
    if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
      throw new BindingException("Mapper method '" + command.getName()
          + " attempted to return null from a method with a primitive return type (" + method.getReturnType() + ").");
    }
    //4.返回result
    return result;
  }

  /**
   * 返回行数据影响数值，由 INSERT、UPDATE、DELETE sql语句类型的execute方法调用
   * @param rowCount sqlsession返回值
   * @return
   */
  private Object rowCountResult(int rowCount) {
    final Object result;
    //1.方法返回类型为void,返回null
    if (method.returnsVoid()) {
      result = null;
    } else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {
      //2.方法返回类型为Integer或int,返回rowCount
      result = rowCount;
    } else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {
      //3.方法返回类型为Long或long,返回(long)rowCount
      result = (long)rowCount;
    } else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {
      //4.方法返回类型为Boolean或boolean,返回rowCount>0
      result = rowCount > 0;
    } else {
      //5.其他方法返回类型，抛出异常
      throw new BindingException("Mapper method '" + command.getName() + "' has an unsupported return type: " + method.getReturnType());
    }
    return result;
  }

  /**
   * void方法返回值，且ResultHandler处理结果。路由给sqlSesson的select方法执行读操作
   * @param sqlSession sqlSession对象
   * @param args 方法参数
   */
  private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
    //1.从sqlSession获取MappedStatement对象
    MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
    //2.当使用 ResultHandler 处理结果集时，必须指定 ResultMap 或 ResultType
    if (!StatementType.CALLABLE.equals(ms.getStatementType())
        && void.class.equals(ms.getResultMaps().get(0).getType())) {
      throw new BindingException("method " + command.getName()
          + " needs either a @ResultMap annotation, a @ResultType annotation,"
          + " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
    }
    //3.从参数数组获取参数对象
    Object param = method.convertArgsToSqlCommandParam(args);
    //4.底层调用 sqlSession.select 方法执行sql
    if (method.hasRowBounds()) {
      //4.1从method和 参数数组 获取 RowBounds 对象（来源于参数数组）
      RowBounds rowBounds = method.extractRowBounds(args);
      //4.2从method和 参数数组 获取 ResultHandler 对象（来源于参数数组）
      //4.3调用 sqlSession.select 方法执行sql
      sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
    } else {
      sqlSession.select(command.getName(), param, method.extractResultHandler(args));
    }
  }

  /**
   * 集合或数组返回值。路由给sqlSesson的selectList方法执行读操作
   * @param sqlSession sqlSession对象
   * @param args 方法参数
   * @param <E> 集合元素类型
   * @return
   */
  private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
    List<E> result;
    //1.从参数数组获取参数对象
    Object param = method.convertArgsToSqlCommandParam(args);
    //2.方法如果有 RowBounds 参数
    if (method.hasRowBounds()) {
      //2.1从method和args中提取 RowBounds 参数
      RowBounds rowBounds = method.extractRowBounds(args);
      //2.2调用 sqlSession.<E>selectList 方法执行sql
      result = sqlSession.<E>selectList(command.getName(), param, rowBounds);
    } else {
      //2.调用 sqlSession.<E>selectList 方法执行sql
      result = sqlSession.<E>selectList(command.getName(), param);
    }
    // issue #510 Collections & arrays support
    //3.将结果转换为 数组 或 Collection集合
    if (!method.getReturnType().isAssignableFrom(result.getClass())) {
      //3.1将list集合对象转换成数组
      if (method.getReturnType().isArray()) {
        return convertToArray(result);
      } else {
        //3.2将list集合对象转换成指定Collection类型集合
        return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
      }
    }
    //4.直接返回结果
    return result;
  }

  /**
   * 游标结果对象为返回值。路由给sqlSesson的selectCursor方法执行读操作
   * @param sqlSession sqlSession对象
   * @param args 方法参数
   * @param <T> 结果类型
   * @return 游标结果对象
   */
  private <T> Cursor<T> executeForCursor(SqlSession sqlSession, Object[] args) {
    Cursor<T> result;
    //1.从参数数组获取参数对象
    Object param = method.convertArgsToSqlCommandParam(args);
    //2.如果方法签名中有 RowBounds 类型的参数
    if (method.hasRowBounds()) {
      //2.1从方法签名和参数数组中获取 RowBounds对象
      RowBounds rowBounds = method.extractRowBounds(args);
      //2.2调用 sqlSession.<T>selectCursor 执行查询
      result = sqlSession.<T>selectCursor(command.getName(), param, rowBounds);
    } else {
      //2.如果方法签名中没有 RowBounds 类型的参数
      result = sqlSession.<T>selectCursor(command.getName(), param);
    }
    return result;
  }

  /**
   * 将 list集合类型 转换成 方法返回值指定的集合类型
   * @param config 配置对象
   * @param list list原集合对象
   * @param <E> 集合元素类型
   * @return 指定类型的集合对象
   */
  private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
    //1.通过 ObjectFactory 通过反射原理，创建最终集合对象
    Object collection = config.getObjectFactory().create(method.getReturnType());
    //2.获取collection集合对象对应的 MetaObject 对象
    MetaObject metaObject = config.newMetaObject(collection);
    //3.使用原集合内容初始化 metaObject 集合
    metaObject.addAll(list);
    return collection;
  }

  /**
   * 将List转换成数组
   * @param list list对象
   * @param <E> 数组元素类型
   * @return 数组对象
   */
  @SuppressWarnings("unchecked")
  private <E> Object convertToArray(List<E> list) {
    //1.获取数组元素类型
    Class<?> arrayComponentType = method.getReturnType().getComponentType();
    //2.新建空数组
    Object array = Array.newInstance(arrayComponentType, list.size());
    //3.如果数组元素是基本数据类型，遍历设置数组值
    if (arrayComponentType.isPrimitive()) {
      for (int i = 0; i < list.size(); i++) {
        Array.set(array, i, list.get(i));
      }
    return array;
    } else {
      //3.如果数组元素是其他数据类型，直接调用toArray方法
      return list.toArray((E[])array);
    }
  }

  /**
   * Map返回值。路由给sqlSesson的selectMap方法执行读操作
   * @param sqlSession sqlSession对象
   * @param args 方法参数
   * @param <K> key
   * @param <V> value
   * @return Map对象
   */
  private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
    Map<K, V> result;
    //1.获取参数名参数值映射
    Object param = method.convertArgsToSqlCommandParam(args);
    //2.调用 sqlSession.selectMap 方法执行sql
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.selectMap(command.getName(), param, method.getMapKey(), rowBounds);
    } else {
      result = sqlSession.selectMap(command.getName(), param, method.getMapKey());
    }
    return result;
  }

  public static class ParamMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = -2212268410512043556L;

    @Override
    public V get(Object key) {
      //未获取到指定 参数名 抛出异常
      if (!super.containsKey(key)) {
        throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + keySet());
      }
      return super.get(key);
    }

  }

  /**
   * MapperMethod 的内部静态类，SQL 命令。
   */
  public static class SqlCommand {

    /**
     * sql语句的名称 {@link MappedStatement#getId()}
     */
    private final String name;
    /**
     * sql语句的类型
     */
    private final SqlCommandType type;

    /**
     * 构造器，初始化name和type字段
     * @param configuration
     * @param mapperInterface
     * @param method
     */
    public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
      final String methodName = method.getName();//方法名
      final Class<?> declaringClass = method.getDeclaringClass();//接口class对象
      //1.根据 方法名、接口class、配置等 获取 MappedStatement 对象
      MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass,
          configuration);
      //2.找不到 MappedStatement 对象
      if (ms == null) {
        //2.1如果有 @Flush注解，则标记为 FLUSH 类型的sql语句
        if(method.getAnnotation(Flush.class) != null){
          name = null;
          type = SqlCommandType.FLUSH;
        } else {
          //2.2没有 @Flush注解，则抛出异常
          throw new BindingException("Invalid bound statement (not found): "
              + mapperInterface.getName() + "." + methodName);
        }
      } else {
        //3.找到 MappedStatement 对象
        //3.1初始化name字段
        name = ms.getId();
        //3.2初始化type字段
        type = ms.getSqlCommandType();
        //3.3如果type是UNKNOWN,则抛出异常
        if (type == SqlCommandType.UNKNOWN) {
          throw new BindingException("Unknown execution method for: " + name);
        }
      }
    }

    public String getName() {
      return name;
    }

    public SqlCommandType getType() {
      return type;
    }

    /**
     * 获得 MappedStatement 对象
     * @param mapperInterface mapper接口
     * @param methodName 方法名
     * @param declaringClass 方法所在接口class
     * @param configuration mybatis全局配置
     * @return MappedStatement对象
     */
    private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName,
        Class<?> declaringClass, Configuration configuration) {
      //1.获得 MappedStatement 的编号，以接口全类名和方法名组成
      String statementId = mapperInterface.getName() + "." + methodName;
      //2.如果配置文件中有该sql Statement,获得MappedStatement对象并返回（大部分情况直接从configuration找到）
      if (configuration.hasStatement(statementId)) {
        return configuration.getMappedStatement(statementId);
      } else if (mapperInterface.equals(declaringClass)) {
        //2.如果没有，并且当前方法就是 declaringClass声明的，则说明真的找不到
        return null;
      }
      //3.递归遍历父接口，继续获得 MappedStatement 对象（少部分情况需要递归找父mapper）
      for (Class<?> superInterface : mapperInterface.getInterfaces()) {
        //如果 declaringClass 是 superInterface 的父类或同类
        if (declaringClass.isAssignableFrom(superInterface)) {
          MappedStatement ms = resolveMappedStatement(superInterface, methodName,
              declaringClass, configuration);
          if (ms != null) {
            return ms;
          }
        }
      }
      //4.真找不到返回null
      return null;
    }
  }

  /**
   * MapperMethod 的内部静态类，方法签名
   */
  public static class MethodSignature {

    /**
     * 返回类型是否为集合Collecton类型或数组类型
     */
    private final boolean returnsMany;
    /**
     * 返回类型是否为 Map
     */
    private final boolean returnsMap;
    /**
     * 返回类型是否为 Void
     */
    private final boolean returnsVoid;
    /**
     * 返回类型是否为 {@link org.apache.ibatis.cursor.Cursor}
     */
    private final boolean returnsCursor;
    /**
     * 返回类型是否为 {@link java.util.Optional}
     */
    private final boolean returnsOptional;
    /**
     * 返回类型
     */
    private final Class<?> returnType;
    /**
     * 返回方法上的 {@link MapKey#value()},前提是返回类型为 Map
     */
    private final String mapKey;
    /**
     * 获得 {@link ResultHandler} 在方法参数中的位置
     * 如果为 null ，则说明不存在这个类型，一般为null
     */
    private final Integer resultHandlerIndex;
    /**
     * 获得 {@link RowBounds} 在方法参数中的位置
     * 如果为 null ，则说明不存在这个类型，一般为null
     */
    private final Integer rowBoundsIndex;
    /**
     * 该方法对应的 ParamNameResolver 对象
     */
    private final ParamNameResolver paramNameResolver;

    /**
     * 构造器，初始化字段
     * @param configuration mybatis配置对象
     * @param mapperInterface mapper接口
     * @param method Method反射对象
     */
    public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {
      //1.解析方法返回值类型，并初始化 returnType 属性
      Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
      if (resolvedReturnType instanceof Class<?>) {// 普通类
        this.returnType = (Class<?>) resolvedReturnType;
      } else if (resolvedReturnType instanceof ParameterizedType) {// 泛型
        this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
      } else {// 内部类等等
        this.returnType = method.getReturnType();
      }
      //2.初始化 returnsVoid 属性
      this.returnsVoid = void.class.equals(this.returnType);
      //3.初始化 returnsMany 属性
      this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();
      //4.初始化 returnsCursor 属性
      this.returnsCursor = Cursor.class.equals(this.returnType);
      //5.初始化 returnsOptional 属性
      this.returnsOptional = Optional.class.equals(this.returnType);
      //6.初始化 mapKey 属性，通过方法上的注解值初始化
      this.mapKey = getMapKey(method);
      //7.初始化 returnsMap 属性，通过mapKey属性是否为空初始化
      this.returnsMap = this.mapKey != null;
      //8.初始化 rowBoundsIndex 属性
      this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);
      //9.初始化 resultHandlerIndex 属性
      this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);
      //10.初始化 paramNameResolver 属性
      this.paramNameResolver = new ParamNameResolver(configuration, method);
    }

    /**
     * 将 参数数组 转换为 参数对象
     * 获得 SQL 通用参数映射，底层调用 {@link ParamNameResolver#getNamedParams(Object[])} 实现
     * @param args 方法参数
     * @return 参数名与参数值的映射
     */
    public Object convertArgsToSqlCommandParam(Object[] args) {
      return paramNameResolver.getNamedParams(args);
    }

    public boolean hasRowBounds() {
      return rowBoundsIndex != null;
    }

    public RowBounds extractRowBounds(Object[] args) {
      return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
    }

    public boolean hasResultHandler() {
      return resultHandlerIndex != null;
    }

    public ResultHandler extractResultHandler(Object[] args) {
      return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
    }

    public String getMapKey() {
      return mapKey;
    }

    public Class<?> getReturnType() {
      return returnType;
    }

    public boolean returnsMany() {
      return returnsMany;
    }

    public boolean returnsMap() {
      return returnsMap;
    }

    public boolean returnsVoid() {
      return returnsVoid;
    }

    public boolean returnsCursor() {
      return returnsCursor;
    }

    /**
     * return whether return type is {@code java.util.Optional}
     * @return return {@code true}, if return type is {@code java.util.Optional}
     * @since 3.5.0
     */
    public boolean returnsOptional() {
      return returnsOptional;
    }

    /**
     * 获取参数索引
     * @param method 方法反射对象
     * @param paramType 参数类型
     * @return 索引
     */
    private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
      Integer index = null;//索引记录，从0开始
      //1.获取方法参数类型数组
      final Class<?>[] argTypes = method.getParameterTypes();
      //2.遍历数组，根据 指定的类型 和 真正的参数类型 赋值索引
      for (int i = 0; i < argTypes.length; i++) {
        if (paramType.isAssignableFrom(argTypes[i])) {
          if (index == null) {
            index = i;
          } else {
            throw new BindingException(method.getName() + " cannot have multiple " + paramType.getSimpleName() + " parameters");
          }
        }
      }
      return index;
    }

    /**
     * 获取@MapKey注解的值
     * @param method
     * @return
     */
    private String getMapKey(Method method) {
      String mapKey = null;
      //1.如果方法返回值是Map的子类
      if (Map.class.isAssignableFrom(method.getReturnType())) {
        //2.获取方法注解MapKey
        final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
        //3.如果有此注解，则获取value值
        if (mapKeyAnnotation != null) {
          mapKey = mapKeyAnnotation.value();
        }
      }
      return mapKey;
    }
  }

}
