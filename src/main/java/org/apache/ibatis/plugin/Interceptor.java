/**
 *    Copyright 2009-2015 the original author or authors.
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

import java.util.Properties;

/**
 * 拦截器接口
 * @author Clinton Begin
 */
public interface Interceptor {

  /**
   * 执行拦截逻辑的方法
   * @param invocation 调用信息
   * @return 调用结果
   * @throws Throwable
   */
  Object intercept(Invocation invocation) throws Throwable;

  /**
   * 应用插件
   * 如应用成功，则会创建目标对象的代理对象。决定了是否触发intercept(Invocation)方法
   * @param target 目标对象
   * @return 应用的结果对象，可以是代理对象，也可以是 target 对象，也可以是任意对象
   */
  Object plugin(Object target);

  /**
   * 设置拦截器属性
   * @param properties 属性
   */
  void setProperties(Properties properties);

}
