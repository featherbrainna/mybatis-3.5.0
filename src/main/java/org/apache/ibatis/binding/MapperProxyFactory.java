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
package org.apache.ibatis.binding;

import org.apache.ibatis.session.SqlSession;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mapper Proxy 工厂类
 * @author Lasse Voss
 */
public class MapperProxyFactory<T> {

  /**
   * Mapper接口
   */
  private final Class<T> mapperInterface;
  /**
   * 方法与 MapperMethod 的映射缓存
   *
   * key:mapperInterface中的Method对象
   * value:对应的MapperMethod对象
   */
  private final Map<Method, MapperMethod> methodCache = new ConcurrentHashMap<>();

  /**
   * 构造器，初始化mapperInterface字段
   * @param mapperInterface
   */
  public MapperProxyFactory(Class<T> mapperInterface) {
    this.mapperInterface = mapperInterface;
  }

  public Class<T> getMapperInterface() {
    return mapperInterface;
  }

  public Map<Method, MapperMethod> getMethodCache() {
    return methodCache;
  }

  /**
   * 创建 Mapper Proxy 对象
   * @param mapperProxy 代理InvocationHandler对象
   * @return Mapper Proxy 对象
   */
  @SuppressWarnings("unchecked")
  protected T newInstance(MapperProxy<T> mapperProxy) {
    //创建实现了 mapperInterface 接口的代理对象
    return (T) Proxy.newProxyInstance(mapperInterface.getClassLoader(), new Class[] { mapperInterface }, mapperProxy);
  }

  /**
   * 创建 Mapper Proxy 对象
   * @param sqlSession SqlSession对象
   * @return Mapper Proxy 对象
   */
  public T newInstance(SqlSession sqlSession) {
    //创建 MapperProxy 对象，每次调用时都会创建新的MapperProxy对象
    final MapperProxy<T> mapperProxy = new MapperProxy<>(sqlSession, mapperInterface, methodCache);
    //调用本类newInstance(mapperProxy)创建代理对象
    return newInstance(mapperProxy);
  }

}
