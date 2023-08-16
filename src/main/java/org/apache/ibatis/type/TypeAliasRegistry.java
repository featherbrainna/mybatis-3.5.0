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
package org.apache.ibatis.type;

import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.io.Resources;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.util.*;

/**
 * 完成类的别名注册和管理的功能
 * @author Clinton Begin
 */
public class TypeAliasRegistry {

  /**
   * 类别名和类类型的对应映射关系
   */
  private final Map<String, Class<?>> TYPE_ALIASES = new HashMap<>();

  /**
   * 初始化默认的类型与别名
   *
   * 另外，在 {@link org.apache.ibatis.session.Configuration} 构造方法中，也有默认的注册
   */
  public TypeAliasRegistry() {
    registerAlias("string", String.class);

    registerAlias("byte", Byte.class);
    registerAlias("long", Long.class);
    registerAlias("short", Short.class);
    registerAlias("int", Integer.class);
    registerAlias("integer", Integer.class);
    registerAlias("double", Double.class);
    registerAlias("float", Float.class);
    registerAlias("boolean", Boolean.class);

    registerAlias("byte[]", Byte[].class);
    registerAlias("long[]", Long[].class);
    registerAlias("short[]", Short[].class);
    registerAlias("int[]", Integer[].class);
    registerAlias("integer[]", Integer[].class);
    registerAlias("double[]", Double[].class);
    registerAlias("float[]", Float[].class);
    registerAlias("boolean[]", Boolean[].class);

    registerAlias("_byte", byte.class);
    registerAlias("_long", long.class);
    registerAlias("_short", short.class);
    registerAlias("_int", int.class);
    registerAlias("_integer", int.class);
    registerAlias("_double", double.class);
    registerAlias("_float", float.class);
    registerAlias("_boolean", boolean.class);

    registerAlias("_byte[]", byte[].class);
    registerAlias("_long[]", long[].class);
    registerAlias("_short[]", short[].class);
    registerAlias("_int[]", int[].class);
    registerAlias("_integer[]", int[].class);
    registerAlias("_double[]", double[].class);
    registerAlias("_float[]", float[].class);
    registerAlias("_boolean[]", boolean[].class);

    registerAlias("date", Date.class);
    registerAlias("decimal", BigDecimal.class);
    registerAlias("bigdecimal", BigDecimal.class);
    registerAlias("biginteger", BigInteger.class);
    registerAlias("object", Object.class);

    registerAlias("date[]", Date[].class);
    registerAlias("decimal[]", BigDecimal[].class);
    registerAlias("bigdecimal[]", BigDecimal[].class);
    registerAlias("biginteger[]", BigInteger[].class);
    registerAlias("object[]", Object[].class);

    registerAlias("map", Map.class);
    registerAlias("hashmap", HashMap.class);
    registerAlias("list", List.class);
    registerAlias("arraylist", ArrayList.class);
    registerAlias("collection", Collection.class);
    registerAlias("iterator", Iterator.class);

    registerAlias("ResultSet", ResultSet.class);
  }

  /**
   * 解析别名为类型
   * 解析时统一以小写解析，故实现了别名不区分大小写
   * @param string 别名
   * @param <T> 类型
   * @return 类型
   */
  @SuppressWarnings("unchecked")
  // throws class cast exception as well if types cannot be assigned
  public <T> Class<T> resolveAlias(String string) {
    try {
      //1.别名为null，返回null
      if (string == null) {
        return null;
      }
      // issue #748
      //2.别名转小写
      String key = string.toLowerCase(Locale.ENGLISH);
      Class<T> value;
      //3.已注册该别名，从TYPE_ALIASES集合获取
      if (TYPE_ALIASES.containsKey(key)) {
        value = (Class<T>) TYPE_ALIASES.get(key);
      } else {
        //3.未注册别名，依据别名获取类
        value = (Class<T>) Resources.classForName(string);
      }
      //4.返回
      return value;
    } catch (ClassNotFoundException e) {
      throw new TypeException("Could not resolve type alias '" + string + "'.  Cause: " + e, e);
    }
  }

  //####################################### registerAlias #################################################

  /**
   * 为指定包下的所有类注册别名
   * @param packageName
   */
  public void registerAliases(String packageName){
    registerAliases(packageName, Object.class);
  }

  /**
   * 为指定包下的指定父类型注册别名
   * @param packageName 包名
   * @param superType 父类型
   */
  public void registerAliases(String packageName, Class<?> superType){
    //1.获取指定包下的指定类们
    ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
    resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
    Set<Class<? extends Class<?>>> typeSet = resolverUtil.getClasses();
    for(Class<?> type : typeSet){
      // Ignore inner classes and interfaces (including package-info.java)
      // Skip also inner classes. See issue #6
      //2.过滤掉内部类、接口、抽象类
      if (!type.isAnonymousClass() && !type.isInterface() && !type.isMemberClass()) {
        //注册别名
        registerAlias(type);
      }
    }
  }

  /**
   * 根据类型注册别名
   * 底层调用 {@link #registerAlias(String, Class)} 注册别名
   * @param type 类型
   */
  public void registerAlias(Class<?> type) {
    //1.获取默认的简单别名
    String alias = type.getSimpleName();
    //2.读取类型的Alias注解
    Alias aliasAnnotation = type.getAnnotation(Alias.class);
    //3.Alias注解不为空，重置为注解指定的别名
    if (aliasAnnotation != null) {
      alias = aliasAnnotation.value();
    }
    //4.委托registerAlias(alias, type)方法，根据别名和类型注册别名
    registerAlias(alias, type);
  }

  /**
   * 底层根据别名和类型注册别名
   * 注册时统一以小写注册别名
   * @param alias 别名
   * @param value 类型
   */
  public void registerAlias(String alias, Class<?> value) {
    //1.别名未指定，抛出异常
    if (alias == null) {
      throw new TypeException("The parameter alias cannot be null");
    }
    //2.小写别名
    // issue #748
    String key = alias.toLowerCase(Locale.ENGLISH);
    //3.别名已存在，且指定的别名冲突，抛出异常
    if (TYPE_ALIASES.containsKey(key) && TYPE_ALIASES.get(key) != null && !TYPE_ALIASES.get(key).equals(value)) {
      throw new TypeException("The alias '" + alias + "' is already mapped to the value '" + TYPE_ALIASES.get(key).getName() + "'.");
    }
    //4.注册别名
    TYPE_ALIASES.put(key, value);
  }

  /**
   * 根据指定别名指定类的全类名注册别名
   * @param alias 别名
   * @param value 全类名
   */
  public void registerAlias(String alias, String value) {
    try {
      registerAlias(alias, Resources.classForName(value));
    } catch (ClassNotFoundException e) {
      throw new TypeException("Error registering type alias "+alias+" for "+value+". Cause: " + e, e);
    }
  }

  /**
   * @since 3.2.2
   */
  public Map<String, Class<?>> getTypeAliases() {
    return Collections.unmodifiableMap(TYPE_ALIASES);
  }

}
