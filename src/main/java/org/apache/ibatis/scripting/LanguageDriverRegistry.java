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
package org.apache.ibatis.scripting;

import java.util.HashMap;
import java.util.Map;

/**
 * LanguageDriver 注册表
 * @author Frank D. Martinez [mnesarco]
 */
public class LanguageDriverRegistry {

  /**
   * LanguageDriver 映射（已注册激活）
   */
  private final Map<Class<? extends LanguageDriver>, LanguageDriver> LANGUAGE_DRIVER_MAP = new HashMap<>();
  /**
   * 默认的 LanguageDriver 类
   */
  private Class<? extends LanguageDriver> defaultDriverClass;

  /**
   * 依据 LanguageDriver的class对象 注册
   * @param cls
   */
  public void register(Class<? extends LanguageDriver> cls) {
    //1.如果 class对象 为空怕抛出异常
    if (cls == null) {
      throw new IllegalArgumentException("null is not a valid Language Driver");
    }
    //2.如果 class对象 不为空且 LANGUAGE_DRIVER_MAP 映射集合中没有以class对象为键的映射，即没有注册
    if (!LANGUAGE_DRIVER_MAP.containsKey(cls)) {
      try {
        //2.1注册。创建 cls 对应的对象，并添加到 LANGUAGE_DRIVER_MAP 中
        LANGUAGE_DRIVER_MAP.put(cls, cls.newInstance());
      } catch (Exception ex) {
        throw new ScriptingException("Failed to load language driver for " + cls.getName(), ex);
      }
    }
  }

  /**
   * 依据 LanguageDriver的实例对象 注册
   * @param instance
   */
  public void register(LanguageDriver instance) {
    //1.如果 LanguageDriver对象 为空则抛出异常
    if (instance == null) {
      throw new IllegalArgumentException("null is not a valid Language Driver");
    }
    //2.获取 LanguageDriver对象对应得 class 对象
    Class<? extends LanguageDriver> cls = instance.getClass();
    //3.如果 LanguageDriver对象 不为空且 LANGUAGE_DRIVER_MAP映射集合没有注册
    if (!LANGUAGE_DRIVER_MAP.containsKey(cls)) {
      //注册
      LANGUAGE_DRIVER_MAP.put(cls, instance);
    }
  }

  public LanguageDriver getDriver(Class<? extends LanguageDriver> cls) {
    return LANGUAGE_DRIVER_MAP.get(cls);
  }

  public LanguageDriver getDefaultDriver() {
    return getDriver(getDefaultDriverClass());
  }

  public Class<? extends LanguageDriver> getDefaultDriverClass() {
    return defaultDriverClass;
  }

  /**
   * 设置 {@link #defaultDriverClass} 属性
   * @param defaultDriverClass 默认的 LanguageDriver 类
   */
  public void setDefaultDriverClass(Class<? extends LanguageDriver> defaultDriverClass) {
    //1.注册到 LANGUAGE_DRIVER_MAP 中
    register(defaultDriverClass);
    //2.设置 defaultDriverClass 属性
    this.defaultDriverClass = defaultDriverClass;
  }

}
