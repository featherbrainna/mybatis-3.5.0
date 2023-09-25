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
package org.apache.ibatis.executor.loader.cglib;

import net.sf.cglib.proxy.Callback;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.apache.ibatis.executor.loader.*;
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
 * 实现 ProxyFactory 接口，基于 Cglib 的 ProxyFactory 实现类
 * @author Clinton Begin
 */
public class CglibProxyFactory implements ProxyFactory {

  private static final String FINALIZE_METHOD = "finalize";
  private static final String WRITE_REPLACE_METHOD = "writeReplace";

  public CglibProxyFactory() {
    try {
      //加载 net.sf.cglib.proxy.Enhancer 类，检测能否加载。不能加载则抛出异常。
      Resources.classForName("net.sf.cglib.proxy.Enhancer");
    } catch (Throwable e) {
      throw new IllegalStateException("Cannot enable lazy loading because CGLIB is not available. Add CGLIB to your classpath.", e);
    }
  }

  /**
   * 创建代理对象。
   * 底层调用 {@link EnhancedResultObjectProxyImpl#createProxy(Object, ResultLoaderMap, Configuration, ObjectFactory, List, List)} 实现
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
   * 创建代理对象静态方法
   * 1.创建Enhancer
   * 2.设置Enhancer父类、接口和Callback
   * 3.通过Enhancer创建代理对象
   * @param type 被代理对象的类型
   * @param callback Callback对象。即代理增强逻辑
   * @param constructorArgTypes 构造器参数类型集合
   * @param constructorArgs 构造器参数值集合
   * @return 代理对象
   */
  static Object crateProxy(Class<?> type, Callback callback, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
    //1.创建 Enhancer 对象
    Enhancer enhancer = new Enhancer();
    //2.设置 Callback 对象
    enhancer.setCallback(callback);
    //3.设置生成的代理类的父类
    enhancer.setSuperclass(type);
    try {
      //4.从被代理类型中查找 writeReplace 方法，没找到抛出异常
      type.getDeclaredMethod(WRITE_REPLACE_METHOD);
      // ObjectOutputStream will call writeReplace of objects returned by writeReplace
      if (LogHolder.log.isDebugEnabled()) {
        LogHolder.log.debug(WRITE_REPLACE_METHOD + " method was found on bean " + type + ", make sure it returns this");
      }
    } catch (NoSuchMethodException e) {
      //4.没有找到 writeReplace 方法，则设置生成的代理类实现 WriteReplaceInterface 接口
      enhancer.setInterfaces(new Class[]{WriteReplaceInterface.class});
    } catch (SecurityException e) {
      // nothing to do here
    }
    Object enhanced;//代理对象
    //5.如果构造器方法参数类型列表为空，则直接enhancer.create()创建子类代理对象
    if (constructorArgTypes.isEmpty()) {
      enhanced = enhancer.create();
    } else {
      //5.如果构造器方法参数类型列表不为空
      //5.1获取 构造器方法参数类型数组 和 构造器参数值数组
      Class<?>[] typesArray = constructorArgTypes.toArray(new Class[constructorArgTypes.size()]);
      Object[] valuesArray = constructorArgs.toArray(new Object[constructorArgs.size()]);
      //5.2调用enhancer.create(typesArray, valuesArray)创建代理对象
      enhanced = enhancer.create(typesArray, valuesArray);
    }
    //6.返回代理对象
    return enhanced;
  }

  /**
   * 静态内部类，实现了 net.sf.cglib.proxy.MethodInterceptor 接口，用于创建代理类。
   */
  private static class EnhancedResultObjectProxyImpl implements MethodInterceptor {

    /**
     * 被代理类的类型
     */
    private final Class<?> type;
    /**
     * ResultLoaderMap对象。其中记录了 延迟加载的属性名 和 ResultLoader结果加载器 对象之间的映射
     */
    private final ResultLoaderMap lazyLoader;
    /**
     * 在 mybatis-config.xml 文件中，aggressiveLazyLoading 配置项的值
     */
    private final boolean aggressive;
    /**
     * 触发延迟加载的方法名列表。如果调用了该列表的方法，则对全部的延迟加载属性进行加载操作
     */
    private final Set<String> lazyLoadTriggerMethods;
    /**
     * 对象工厂
     */
    private final ObjectFactory objectFactory;
    /**
     * 创建代理对象时，使用的构造方法的参数类型
     */
    private final List<Class<?>> constructorArgTypes;
    /**
     * 创建代理对象时，使用的构造方法的参数值
     */
    private final List<Object> constructorArgs;

    /**
     * 构造器
     */
    private EnhancedResultObjectProxyImpl(Class<?> type, ResultLoaderMap lazyLoader, Configuration configuration, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      this.type = type;
      this.lazyLoader = lazyLoader;
      //从configuration初始化aggressive
      this.aggressive = configuration.isAggressiveLazyLoading();
      //从configuration初始化lazyLoadTriggerMethods
      this.lazyLoadTriggerMethods = configuration.getLazyLoadTriggerMethods();
      this.objectFactory = objectFactory;
      this.constructorArgTypes = constructorArgTypes;
      this.constructorArgs = constructorArgs;
    }

    /**
     * 底层创建代理对象，并设置代理逻辑callback为 EnhancedResultObjectProxyImpl 对象
     * 底层再调用 {@link CglibProxyFactory#crateProxy(Class, Callback, List, List)} 实现
     * @param target 被代理对象
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
      //2.使用传入的参数创建 EnhancedResultObjectProxyImpl 对象（即本类）
      EnhancedResultObjectProxyImpl callback = new EnhancedResultObjectProxyImpl(type, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
      //3.调用 CglibProxyFactory#crateProxy() 静态方法创建代理对象
      Object enhanced = crateProxy(type, callback, constructorArgTypes, constructorArgs);
      //4.将 target 被代理对象的属性值复制到 enhanced 代理对象的属性中
      PropertyCopier.copyBeanProperties(type, target, enhanced);
      //5.返回代理对象
      return enhanced;
    }

    /**
     * 代理增强的逻辑
     * 1.获取方法名称
     * 2.依据方法名称执行延迟加载属性值
     * 3.调用被代理对象即父类的方法
     * @param enhanced 代理对象
     * @param method 方法反射对象
     * @param args 参数数组
     * @param methodProxy MethodProxy父类方法反射对象
     * @return 方法返回值
     * @throws Throwable
     */
    @Override
    public Object intercept(Object enhanced, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
      //1.获取方法名称
      final String methodName = method.getName();
      try {
        //同步
        synchronized (lazyLoader) {
          //2.如果方法名为 “writeReplace”，则进行相关处理
          if (WRITE_REPLACE_METHOD.equals(methodName)) {
            //2.1构造代理对象类型的 新对象
            Object original;
            if (constructorArgTypes.isEmpty()) {
              original = objectFactory.create(type);
            } else {
              original = objectFactory.create(type, constructorArgTypes, constructorArgs);
            }
            //2.2复制 代理对象属性 到 新对象
            PropertyCopier.copyBeanProperties(type, enhanced, original);
            //2.3如果有需要延迟加载的属性，则创建 CglibSerialStateHolder 对象
            if (lazyLoader.size() > 0) {
              return new CglibSerialStateHolder(original, lazyLoader.getProperties(), objectFactory, constructorArgTypes, constructorArgs);
            } else {
              //2.3如果没有要延迟加载的属性，则返回新对象
              return original;
            }
          } else {
            //2.如果 lazyLoader 映射大小大于0且方法名不为 finalize
            if (lazyLoader.size() > 0 && !FINALIZE_METHOD.equals(methodName)) {
              //2.1如果 aggressiveLazyLoading 为true或者 lazyLoadTriggerMethods集合中有该方法名，则立即执行所有延迟加载
              if (aggressive || lazyLoadTriggerMethods.contains(methodName)) {
                lazyLoader.loadAll();
              } else if (PropertyNamer.isSetter(methodName)) {
                //2.1如果方法名以set开头
                //获取方法对应的属性名
                final String property = PropertyNamer.methodToProperty(methodName);
                //将该属性的映射从 lazyLoader 删除
                lazyLoader.remove(property);
              } else if (PropertyNamer.isGetter(methodName)) {
                //2.1如果方法名以is或get开头
                //获取方法对应的属性名
                final String property = PropertyNamer.methodToProperty(methodName);
                //如果lazyLoader有对应的属性名映射，则立即执行该属性对应的延迟加载
                if (lazyLoader.hasLoader(property)) {
                  lazyLoader.load(property);
                }
              }
            }
          }
        }
        //3.执行父类方法的调用（即被代理方法的调用）
        return methodProxy.invokeSuper(enhanced, args);
      } catch (Throwable t) {
        throw ExceptionUtil.unwrapThrowable(t);
      }
    }
  }

  private static class EnhancedDeserializationProxyImpl extends AbstractEnhancedDeserializationProxy implements MethodInterceptor {

    private EnhancedDeserializationProxyImpl(Class<?> type, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
            List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      super(type, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
    }

    /**
     * 底层创建代理对象
     * 底层再调用 {@link CglibProxyFactory#crateProxy(Class, Callback, List, List)} 实现
     * @param target 被代理对象
     * @param unloadedProperties 未加载的属性映射
     * @param objectFactory 对象工厂
     * @param constructorArgTypes 构造器方法参数类型集合
     * @param constructorArgs 构造器方法参数值集合
     * @return
     */
    public static Object createProxy(Object target, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
            List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      final Class<?> type = target.getClass();
      EnhancedDeserializationProxyImpl callback = new EnhancedDeserializationProxyImpl(type, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
      Object enhanced = crateProxy(type, callback, constructorArgTypes, constructorArgs);
      PropertyCopier.copyBeanProperties(type, target, enhanced);
      return enhanced;
    }

    @Override
    public Object intercept(Object enhanced, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
      final Object o = super.invoke(enhanced, method, args);
      return o instanceof AbstractSerialStateHolder ? o : methodProxy.invokeSuper(o, args);
    }

    @Override
    protected AbstractSerialStateHolder newSerialStateHolder(Object userBean, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
            List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      return new CglibSerialStateHolder(userBean, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
    }
  }

  /**
   * 私有静态内部类实现，实现单例的日志对象
   */
  private static class LogHolder {
    private static final Log log = LogFactory.getLog(CglibProxyFactory.class);
  }

}
