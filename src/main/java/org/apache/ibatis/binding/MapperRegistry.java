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

import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Mapper 注册表
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 */
public class MapperRegistry {

  /**
   * Configuration 对象，Mybatis全局唯一的配置对象，其中包含了所有配置信息
   */
  private final Configuration config;
  /**
   * 记录了 Mapper 接口与对应 MapperProxyFactory 之间的关系
   * key:Mapper接口的class对象
   * value:MapperProxyFactory对象
   */
  private final Map<Class<?>, MapperProxyFactory<?>> knownMappers = new HashMap<>();

  public MapperRegistry(Configuration config) {
    this.config = config;
  }

  /**
   * 获得 Mapper Proxy 对象
   * @param type 接口类型
   * @param sqlSession session对象
   * @param <T> 类型
   * @return
   */
  @SuppressWarnings("unchecked")
  public <T> T getMapper(Class<T> type, SqlSession sqlSession) {
    //1.获得指定type对应的 MapperProxyFactory 对象
    final MapperProxyFactory<T> mapperProxyFactory = (MapperProxyFactory<T>) knownMappers.get(type);
    //2.不存在，则抛出 BindingException 异常
    if (mapperProxyFactory == null) {
      throw new BindingException("Type " + type + " is not known to the MapperRegistry.");
    }
    //3.通过MapperProxyFactory对象创建 Mapper Proxy 对象
    try {
      return mapperProxyFactory.newInstance(sqlSession);
    } catch (Exception e) {
      throw new BindingException("Error getting mapper instance. Cause: " + e, e);
    }
  }

  /**
   * 判断 knownMappers 中是否有 Mapper
   * @param type 接口类型
   * @param <T> 类型
   * @return
   */
  public <T> boolean hasMapper(Class<T> type) {
    return knownMappers.containsKey(type);
  }

  /**
   * 添加到 knownMappers 中
   * @param type 接口类型
   * @param <T> 类型
   */
  public <T> void addMapper(Class<T> type) {
    //1.检测 type 是否是接口
    if (type.isInterface()) {
      //2.检测是否已经加载过该接口，已加载过抛出异常
      if (hasMapper(type)) {
        throw new BindingException("Type " + type + " is already known to the MapperRegistry.");
      }
      boolean loadCompleted = false;
      try {
        //3.将 type 和对应的 MapperProxyFactory 添加到 knownMappers 集合
        knownMappers.put(type, new MapperProxyFactory<>(type));
        // It's important that the type is added before the parser is run
        // otherwise the binding may automatically be attempted by the
        // mapper parser. If the type is already known, it won't try.
        //4.解析 Mapper 的注解
        MapperAnnotationBuilder parser = new MapperAnnotationBuilder(config, type);
        //5.解析注解和XML文件
        parser.parse();
        //6.标记加载完成Mapper接口
        loadCompleted = true;
      } finally {
        //7.若加载未完成，从 knownMappers 中移除 Mapper接口
        if (!loadCompleted) {
          knownMappers.remove(type);
        }
      }
    }
  }

  /**
   * @since 3.2.2
   */
  public Collection<Class<?>> getMappers() {
    return Collections.unmodifiableCollection(knownMappers.keySet());
  }

  /**
   * 扫描指定包，并将符合的类，添加到 knownMappers 中
   * @since 3.2.2
   */
  public void addMappers(String packageName, Class<?> superType) {
    //1.使用 ResolverUtil 扫描指定包下的指定类（父类为superType的类）
    ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<>();
    resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
    Set<Class<? extends Class<?>>> mapperSet = resolverUtil.getClasses();
    //2.遍历，添加到 knownMappers 集合中
    for (Class<?> mapperClass : mapperSet) {
      addMapper(mapperClass);
    }
  }

  /**
   * 扫描指定包的所有类，添加到 knownMappers 中
   * @since 3.2.2
   */
  public void addMappers(String packageName) {
    addMappers(packageName, Object.class);
  }

}
