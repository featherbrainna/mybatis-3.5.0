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
package org.apache.ibatis.executor.loader;

import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;

import java.util.List;
import java.util.Properties;

/**
 * 代理工厂接口，用于创建需要延迟加载属性的结果对象（默认使用CglibProxyFactory）
 * @author Eduardo Macarron
 */
public interface ProxyFactory {

  /**
   * 根据配置初始化ProxyFactory对象。
   * 设置属性，目前是空实现，可以暂时无视该方法
   * @param properties 属性对象
   */
  void setProperties(Properties properties);

  /**
   * 创建代理对象
   * @param target 目标被代理对象
   * @param lazyLoader ResultLoaderMap对象
   * @param configuration 配置对象
   * @param objectFactory 对象工厂
   * @param constructorArgTypes 构造器参数类型集合
   * @param constructorArgs 构造器参数值集合
   * @return 代理对象
   */
  Object createProxy(Object target, ResultLoaderMap lazyLoader, Configuration configuration, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs);

}
