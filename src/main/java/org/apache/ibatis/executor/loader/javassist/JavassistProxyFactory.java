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
package org.apache.ibatis.executor.loader.javassist;

import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.Proxy;
import javassist.util.proxy.ProxyFactory;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.AbstractEnhancedDeserializationProxy;
import org.apache.ibatis.executor.loader.AbstractSerialStateHolder;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.loader.WriteReplaceInterface;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyCopier;
import org.apache.ibatis.reflection.property.PropertyNamer;
import org.apache.ibatis.session.Configuration;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * 实现 ProxyFactory 接口，基于 Javassist 的 ProxyFactory 实现类。
 * @author Eduardo Macarron
 */
public class JavassistProxyFactory implements org.apache.ibatis.executor.loader.ProxyFactory {

  private static final String FINALIZE_METHOD = "finalize";
  private static final String WRITE_REPLACE_METHOD = "writeReplace";

  /**
   * 构造器
   */
  public JavassistProxyFactory() {
    try {
      //加载 javassist.util.proxy.ProxyFactory 类，检测能否加载。不能加载则抛出异常。
      Resources.classForName("javassist.util.proxy.ProxyFactory");
    } catch (Throwable e) {
      throw new IllegalStateException("Cannot enable lazy loading because Javassist is not available. Add Javassist to your classpath.", e);
    }
  }

  /**
   * 创建代理对象
   * @param target 目标被代理对象
   * @param lazyLoader ResultLoaderMap对象
   * @param configuration 配置对象
   * @param objectFactory 对象工厂
   * @param constructorArgTypes 构造器参数类型集合
   * @param constructorArgs 构造器参数值集合
   * @return
   */
  @Override
  public Object createProxy(Object target, ResultLoaderMap lazyLoader, Configuration configuration, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
    return EnhancedResultObjectProxyImpl.createProxy(target, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
  }

  /**
   * 创建支持反序列化的代理对象
   * 实际场景下不太使用该功能，所以暂时无视。
   */
  public Object createDeserializationProxy(Object target, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
    return EnhancedDeserializationProxyImpl.createProxy(target, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
  }

  @Override
  public void setProperties(Properties properties) {
      // Not Implemented
  }

  /**
   * 静态方法，创建代理对象
   * 1.创建javassist ProxyFactory
   * 2.设置ProxyFactory的父类、实现接口和执行器
   * 3.ProxyFactory.create(typesArray, valuesArray)创建代理对象
   * @param type 被代理对象类型
   * @param callback MethodHandler对象
   * @param constructorArgTypes 构造器参数类型集合
   * @param constructorArgs 构造器参数值集合
   * @return
   */
  static Object crateProxy(Class<?> type, MethodHandler callback, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {

    //1.创建 javassist ProxyFactory 对象
    ProxyFactory enhancer = new ProxyFactory();
    //2.设置ProxyFactory的父类
    enhancer.setSuperclass(type);

    //3.根据父类是否有 writeReplace 方法，设置ProxyFactory实现WriteReplaceInterface接口
    try {
      type.getDeclaredMethod(WRITE_REPLACE_METHOD);
      // ObjectOutputStream will call writeReplace of objects returned by writeReplace
      if (LogHolder.log.isDebugEnabled()) {
        LogHolder.log.debug(WRITE_REPLACE_METHOD + " method was found on bean " + type + ", make sure it returns this");
      }
    } catch (NoSuchMethodException e) {
      enhancer.setInterfaces(new Class[]{WriteReplaceInterface.class});
    } catch (SecurityException e) {
      // nothing to do here
    }

    Object enhanced;//代理对象
    //4.获取构造器参数类型、参数值数组
    Class<?>[] typesArray = constructorArgTypes.toArray(new Class[constructorArgTypes.size()]);
    Object[] valuesArray = constructorArgs.toArray(new Object[constructorArgs.size()]);
    try {
      //5.调用ProxyFactory.create(typesArray, valuesArray)创建代理对象
      enhanced = enhancer.create(typesArray, valuesArray);
    } catch (Exception e) {
      throw new ExecutorException("Error creating lazy proxy.  Cause: " + e, e);
    }
    //6.设置代理对象的执行器，即增强逻辑
    ((Proxy) enhanced).setHandler(callback);
    //7.返回代理对象
    return enhanced;
  }

  /**
   * 内部静态类，实现 javassist.util.proxy.MethodHandler 接口，方法处理器实现类
   */
  private static class EnhancedResultObjectProxyImpl implements MethodHandler {

    /**
     * 被代理对象类型
     */
    private final Class<?> type;
    /**
     * ResultLoaderMap对象。用于记录延迟加载的属性与结果加载器的映射关系
     */
    private final ResultLoaderMap lazyLoader;
    /**
     * aggressiveLazyLoading属性值
     */
    private final boolean aggressive;
    /**
     * 触发延迟加载执行加载的方法集
     */
    private final Set<String> lazyLoadTriggerMethods;
    /**
     * 工厂对象
     */
    private final ObjectFactory objectFactory;
    /**
     * 构造器方法参数类型集合
     */
    private final List<Class<?>> constructorArgTypes;
    /**
     * 构造器方法参数值集合
     */
    private final List<Object> constructorArgs;

    /**
     * 构造器
     */
    private EnhancedResultObjectProxyImpl(Class<?> type, ResultLoaderMap lazyLoader, Configuration configuration, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      this.type = type;
      this.lazyLoader = lazyLoader;
      //从configuration初始化 aggressive 属性
      this.aggressive = configuration.isAggressiveLazyLoading();
      //从configuration初始化 lazyLoadTriggerMethods 属性
      this.lazyLoadTriggerMethods = configuration.getLazyLoadTriggerMethods();
      this.objectFactory = objectFactory;
      this.constructorArgTypes = constructorArgTypes;
      this.constructorArgs = constructorArgs;
    }

    /**
     * 底层创建代理对象，并设置方法处理器为 EnhancedResultObjectProxyImpl 对象
     * @param target 目标对象
     * @param lazyLoader ResultLoaderMap对象
     * @param configuration 配置对象
     * @param objectFactory 对象工厂
     * @param constructorArgTypes 构造器参数类型集合
     * @param constructorArgs 构造器参数值集合
     * @return
     */
    public static Object createProxy(Object target, ResultLoaderMap lazyLoader, Configuration configuration, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      //1.获取被代理对象的类型
      final Class<?> type = target.getClass();
      //2.创建 EnhancedResultObjectProxyImpl 对象
      EnhancedResultObjectProxyImpl callback = new EnhancedResultObjectProxyImpl(type, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
      //3.调用 JavassistProxyFactory#crateProxy() 静态方法创建代理对象
      Object enhanced = crateProxy(type, callback, constructorArgTypes, constructorArgs);
      //4.将 被代理对象属性 复制到 代理对象属性
      PropertyCopier.copyBeanProperties(type, target, enhanced);
      //5.返回代理对象
      return enhanced;
    }

    /**
     * 执行方法，即代理增强的逻辑
     * 1.获取方法名称
     * 2.依据方法名称执行延迟加载属性值
     * 3.调用被代理对象即父类的方法
     * @param enhanced 代理对象
     * @param method 方法反射对象
     * @param methodProxy 父类方法反射对象
     * @param args 方法参数
     * @return 方法结果
     * @throws Throwable
     */
    @Override
    public Object invoke(Object enhanced, Method method, Method methodProxy, Object[] args) throws Throwable {
      //1.步骤一：获取方法名称
      final String methodName = method.getName();
      try {
        synchronized (lazyLoader) {
          //2.如果方法名为 writeReplace，则进行相关处理
          if (WRITE_REPLACE_METHOD.equals(methodName)) {
            Object original;
            if (constructorArgTypes.isEmpty()) {
              original = objectFactory.create(type);
            } else {
              original = objectFactory.create(type, constructorArgTypes, constructorArgs);
            }
            PropertyCopier.copyBeanProperties(type, enhanced, original);
            if (lazyLoader.size() > 0) {
              return new JavassistSerialStateHolder(original, lazyLoader.getProperties(), objectFactory, constructorArgTypes, constructorArgs);
            } else {
              return original;
            }
          } else {
            //2.如果存在需要延迟加载的属性且方法名不为finalize
            if (lazyLoader.size() > 0 && !FINALIZE_METHOD.equals(methodName)) {
              //2.1如果aggressive为true 或者 方法名在lazyLoadTriggerMethods集合，则直接加载全部属性
              if (aggressive || lazyLoadTriggerMethods.contains(methodName)) {
                lazyLoader.loadAll();
              } else if (PropertyNamer.isSetter(methodName)) {
                //2.1如果方法名称为setter方法，则从lazyLoader移除需要延迟加载的映射关系
                final String property = PropertyNamer.methodToProperty(methodName);
                lazyLoader.remove(property);
              } else if (PropertyNamer.isGetter(methodName)) {
                //2.1如果方法名称为getter方法，则延迟加载对应的属性
                final String property = PropertyNamer.methodToProperty(methodName);
                if (lazyLoader.hasLoader(property)) {
                  lazyLoader.load(property);
                }
              }
            }
          }
        }
        //3.调用代理对象的父类方法
        return methodProxy.invoke(enhanced, args);
      } catch (Throwable t) {
        throw ExceptionUtil.unwrapThrowable(t);
      }
    }
  }

  private static class EnhancedDeserializationProxyImpl extends AbstractEnhancedDeserializationProxy implements MethodHandler {

    private EnhancedDeserializationProxyImpl(Class<?> type, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
            List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      super(type, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
    }

    public static Object createProxy(Object target, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
            List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      final Class<?> type = target.getClass();
      EnhancedDeserializationProxyImpl callback = new EnhancedDeserializationProxyImpl(type, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
      Object enhanced = crateProxy(type, callback, constructorArgTypes, constructorArgs);
      PropertyCopier.copyBeanProperties(type, target, enhanced);
      return enhanced;
    }

    @Override
    public Object invoke(Object enhanced, Method method, Method methodProxy, Object[] args) throws Throwable {
      final Object o = super.invoke(enhanced, method, args);
      return o instanceof AbstractSerialStateHolder ? o : methodProxy.invoke(o, args);
    }

    @Override
    protected AbstractSerialStateHolder newSerialStateHolder(Object userBean, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
            List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      return new JavassistSerialStateHolder(userBean, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
    }
  }

  private static class LogHolder {
    private static final Log log = LogFactory.getLog(JavassistProxyFactory.class);
  }

}
