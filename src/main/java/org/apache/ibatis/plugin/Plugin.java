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
package org.apache.ibatis.plugin;

import org.apache.ibatis.reflection.ExceptionUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 实现 InvocationHandler 接口，插件类
 * 一方面提供创建动态代理对象的方法，另一方面实现对指定类的指定方法的拦截处理。
 *
 * 是 MyBatis 插件体系的核心类
 * @author Clinton Begin
 */
public class Plugin implements InvocationHandler {

  /**
   * 目标对象（被代理对象）
   */
  private final Object target;
  /**
   * 拦截器
   */
  private final Interceptor interceptor;
  /**
   * 拦截的方法映射。记录了 @Signature 注解中的信息
   * key:类
   * value:方法集合
   */
  private final Map<Class<?>, Set<Method>> signatureMap;

  /**
   * 构造器。初始化所有属性
   */
  private Plugin(Object target, Interceptor interceptor, Map<Class<?>, Set<Method>> signatureMap) {
    this.target = target;
    this.interceptor = interceptor;
    this.signatureMap = signatureMap;
  }

  /**
   * 静态方法，创建目标类的代理对象
   * @param target 目标对象
   * @param interceptor 拦截器对象
   * @return 代理对象
   */
  public static Object wrap(Object target, Interceptor interceptor) {
    //1.从拦截器对象获取 注解定义的 方法签名映射
    Map<Class<?>, Set<Method>> signatureMap = getSignatureMap(interceptor);
    //2.从目标对象获取 目标对象的类型
    Class<?> type = target.getClass();
    //3.获取目标类型 被拦截的 实现接口（这是JDK动态代理的基础）
    Class<?>[] interfaces = getAllInterfaces(type, signatureMap);
    //4.如果有接口，则创建目标对象的 JDK Proxy 对象
    if (interfaces.length > 0) {
      return Proxy.newProxyInstance(
          type.getClassLoader(),
          interfaces,
          new Plugin(target, interceptor, signatureMap));
    }
    //5.如果没有，则返回原始的目标对象
    return target;
  }

  /**
   * 代理增强逻辑
   * @param proxy 代理对象
   * @param method 方法反射对象
   * @param args 方法参数
   * @return 方法结果
   * @throws Throwable
   */
  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      //1.获取当前方法所在接口所对应的 被拦截方法集合
      Set<Method> methods = signatureMap.get(method.getDeclaringClass());
      //2.如果方法对象非空，且被拦截方法有当前方法
      if (methods != null && methods.contains(method)) {
        //调用拦截器的拦截方法
        return interceptor.intercept(new Invocation(target, method, args));
      }
      //2.如果方法不被拦截，则直接调用方法
      return method.invoke(target, args);
    } catch (Exception e) {
      throw ExceptionUtil.unwrapThrowable(e);
    }
  }

  /**
   * 获得拦截器的方法映射
   * @param interceptor 拦截器对象
   * @return 方法映射
   */
  private static Map<Class<?>, Set<Method>> getSignatureMap(Interceptor interceptor) {
    //1.从拦截器获取 Intercepts 注解
    Intercepts interceptsAnnotation = interceptor.getClass().getAnnotation(Intercepts.class);
    // issue #251
    //2.如果注解为空，则抛出异常
    if (interceptsAnnotation == null) {
      throw new PluginException("No @Intercepts annotation was found in interceptor " + interceptor.getClass().getName());
    }
    //3.获取Intercepts注解中的 Signature 注解数组
    Signature[] sigs = interceptsAnnotation.value();
    //4.创建方法映射集合
    Map<Class<?>, Set<Method>> signatureMap = new HashMap<>();
    //5.遍历 Signature 注解数组
    for (Signature sig : sigs) {
      //6.初始化类对应的方法集合
      Set<Method> methods = signatureMap.computeIfAbsent(sig.type(), k -> new HashSet<>());
      try {
        //7.依据 Signature 注解获取方法对象
        Method method = sig.type().getMethod(sig.method(), sig.args());
        //8.将方法对象放入方法集合
        methods.add(method);
      } catch (NoSuchMethodException e) {
        throw new PluginException("Could not find method on " + sig.type() + " named " + sig.method() + ". Cause: " + e, e);
      }
    }
    //9.返回方法映射集合
    return signatureMap;
  }

  /**
   * 获得目标类的接口数组
   * @param type 目标类型
   * @param signatureMap 方法映射集合
   * @return 接口数组
   */
  private static Class<?>[] getAllInterfaces(Class<?> type, Map<Class<?>, Set<Method>> signatureMap) {
    //1.创建接口的集合
    Set<Class<?>> interfaces = new HashSet<>();
    //2.循环递归 type 类型
    while (type != null) {
      //3.遍历本目标类型的实现类
      for (Class<?> c : type.getInterfaces()) {
        //4.如果signatureMap包含 当前接口 的key（即接口被拦截），则将接口添加到 interfaces
        if (signatureMap.containsKey(c)) {
          interfaces.add(c);
        }
      }
      //5.获取当前类的父类
      type = type.getSuperclass();
    }
    //6.创建接口的数组并返回
    return interfaces.toArray(new Class<?>[interfaces.size()]);
  }

}
