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
package org.apache.ibatis.reflection;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * ReflectorFactory接口的框架默认实现
 */
public class DefaultReflectorFactory implements ReflectorFactory {
  /**
   * 该字段决定是否开启对Reflector对象的缓存
   */
  private boolean classCacheEnabled = true;
  /**
   * 使用ConcurrentMap集合实现对Reflector对象的缓存
   */
  private final ConcurrentMap<Class<?>, Reflector> reflectorMap = new ConcurrentHashMap<>();

  public DefaultReflectorFactory() {
  }

  @Override
  public boolean isClassCacheEnabled() {
    return classCacheEnabled;
  }

  @Override
  public void setClassCacheEnabled(boolean classCacheEnabled) {
    this.classCacheEnabled = classCacheEnabled;
  }

  /**
   * 为指定的Class创建 Reflector 对象，并将 Reflector 对象缓存到 reflectorMap 中。
   * @param type 类类型对象
   * @return 对应的Reflector对象
   */
  @Override
  public Reflector findForClass(Class<?> type) {
    //1.检测factory是否开启缓存
    if (classCacheEnabled) {
      //2.开启缓存，有缓存返回缓存，未缓存创建
            // synchronized (type) removed see issue #461
      return reflectorMap.computeIfAbsent(type, Reflector::new);
    } else {
      //3.未开启缓存，则直接创建并返回Reflector对象
      return new Reflector(type);
    }
  }

}
