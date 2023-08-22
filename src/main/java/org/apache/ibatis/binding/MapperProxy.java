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

import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.SqlSession;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

/**
 * Mapper接口的动态代理调用处理类
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class MapperProxy<T> implements InvocationHandler, Serializable {

  private static final long serialVersionUID = -6424540398559729838L;
  /**
   * 底层SqlSession对象，由MapperProxyFactory传入构造器初始化
   */
  private final SqlSession sqlSession;
  /**
   * Mapper接口、
   * 从 {MapperProxyFactory#mapperInterface} 传递过来
   */
  private final Class<T> mapperInterface;
  /**
   * 方法与 MapperMethod 的映射。MapperMethod对象会完成参数转换以及SQL语句的执行功能
   * 从 {MapperProxyFactory#methodCache} 传递过来
   */
  private final Map<Method, MapperMethod> methodCache;

  /**
   * 构造器，由 {@link MapperProxyFactory#newInstance(SqlSession)} 调用
   * @param sqlSession
   * @param mapperInterface
   * @param methodCache
   */
  public MapperProxy(SqlSession sqlSession, Class<T> mapperInterface, Map<Method, MapperMethod> methodCache) {
    this.sqlSession = sqlSession;
    this.mapperInterface = mapperInterface;
    this.methodCache = methodCache;
  }

  /**
   * 代理对象底层调用方法
   * @param proxy 代理对象
   * @param method 反射方法对象
   * @param args 方法参数
   * @return 方法返回值
   * @throws Throwable 异常
   */
  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      //1.如果调用的是 Object 定义的方法，则直接反射调用
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, args);
      } else if (isDefaultMethod(method)) {
        //2.如果调用的是Mapper接口中的 jdk8 default 方法，直接反射执行
        return invokeDefaultMethod(proxy, method, args);
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
    //3.从缓存中获取 MapperMethod 对象，如果缓存没有则创建新的MapperMethod对象并添加到缓存中
    final MapperMethod mapperMethod = cachedMapperMethod(method);
    //4.调用mapperMethod.execute（）方法执行sql语句
    return mapperMethod.execute(sqlSession, args);
  }

  /**
   * 获得 MapperMethod 对象
   * @param method 原始Method反射对象
   * @return MapperMethod对象
   */
  private MapperMethod cachedMapperMethod(Method method) {
    return methodCache.computeIfAbsent(method, k -> new MapperMethod(mapperInterface, method, sqlSession.getConfiguration()));
  }

  /**
   * 反射执行接口中default方法
   * @param proxy 代理对象
   * @param method Method反射对象
   * @param args 方法参数
   * @return
   * @throws Throwable
   */
  private Object invokeDefaultMethod(Object proxy, Method method, Object[] args)
      throws Throwable {
    final Constructor<MethodHandles.Lookup> constructor = MethodHandles.Lookup.class
        .getDeclaredConstructor(Class.class, int.class);
    if (!constructor.isAccessible()) {
      constructor.setAccessible(true);
    }
    //获取方法的声明接口类
    final Class<?> declaringClass = method.getDeclaringClass();
    //反射执行default方法
    return constructor
        .newInstance(declaringClass,
            MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED
                | MethodHandles.Lookup.PACKAGE | MethodHandles.Lookup.PUBLIC)
        .unreflectSpecial(method, declaringClass).bindTo(proxy).invokeWithArguments(args);
  }

  /**
   * 根据 Method 对象判断是否为接口中的 default 修饰的方法
   * Backport of java.lang.reflect.Method#isDefault()
   */
  private boolean isDefaultMethod(Method method) {
    return (method.getModifiers()
        & (Modifier.ABSTRACT | Modifier.PUBLIC | Modifier.STATIC)) == Modifier.PUBLIC
        && method.getDeclaringClass().isInterface();
  }
}
