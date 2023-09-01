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
package org.apache.ibatis.mapping;

import org.apache.ibatis.builder.InitializingObject;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;
import org.apache.ibatis.cache.decorators.*;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Cache 构建器。
 * @author Clinton Begin
 */
public class CacheBuilder {
  /**
   * 编号。记录的是命名空间
   */
  private final String id;
  /**
   * 负责存储的 Cache 实现类
   */
  private Class<? extends Cache> implementation;
  /**
   * Cache 装饰类集合
   */
  private final List<Class<? extends Cache>> decorators;
  /**
   * 缓存容器大小
   */
  private Integer size;
  /**
   * 清空缓存的频率。0代表不清空
   */
  private Long clearInterval;
  /**
   * 是否序列化
   */
  private boolean readWrite;
  /**
   * Properties 对象
   */
  private Properties properties;
  /**
   * 是否阻塞
   */
  private boolean blocking;

  /**
   *构造器。用命名空间初始化 id，用空集合初始化 decorators 属性
   * @param id
   */
  public CacheBuilder(String id) {
    this.id = id;
    this.decorators = new ArrayList<>();
  }

  public CacheBuilder implementation(Class<? extends Cache> implementation) {
    this.implementation = implementation;
    return this;
  }

  public CacheBuilder addDecorator(Class<? extends Cache> decorator) {
    if (decorator != null) {
      this.decorators.add(decorator);
    }
    return this;
  }

  public CacheBuilder size(Integer size) {
    this.size = size;
    return this;
  }

  public CacheBuilder clearInterval(Long clearInterval) {
    this.clearInterval = clearInterval;
    return this;
  }

  public CacheBuilder readWrite(boolean readWrite) {
    this.readWrite = readWrite;
    return this;
  }

  public CacheBuilder blocking(boolean blocking) {
    this.blocking = blocking;
    return this;
  }

  public CacheBuilder properties(Properties properties) {
    this.properties = properties;
    return this;
  }

  /**
   * 核心构建 Cache 对象方法
   * @return Cache 对象
   */
  public Cache build() {
    //1.设置默认实现类，包括缓存的实现和清除缓存的策略实现
    setDefaultImplementations();
    //2.创建基础 Cache 对象
    Cache cache = newBaseCacheInstance(implementation, id);
    //3.设置Cache属性
    setCacheProperties(cache);
    // issue #352, do not apply decorators to custom caches
    //4.如果 cache 是PerpetualCache类，则进行包装
    if (PerpetualCache.class.equals(cache.getClass())) {
      //4.1遍历 decorators 属性进行包装
      for (Class<? extends Cache> decorator : decorators) {
        //4.2包装 cache 对象
        cache = newCacheDecoratorInstance(decorator, cache);
        //4.3设置属性
        setCacheProperties(cache);
      }
      //4.4执行标准化的 Cache 包装
      cache = setStandardDecorators(cache);
    } else if (!LoggingCache.class.isAssignableFrom(cache.getClass())) {
      //4.如果是自定义的 cache 类，不是LoggingCache类，则包装成 LoggingCache 对象，统计命中率
      cache = new LoggingCache(cache);
    }
    return cache;
  }

  /**
   * 设置默认实现类，在 implementation 属性为空时设置，在 decorators 集合为空时设置
   */
  private void setDefaultImplementations() {
    if (implementation == null) {
      implementation = PerpetualCache.class;
      if (decorators.isEmpty()) {
        decorators.add(LruCache.class);
      }
    }
  }

  /**
   * 执行标准化的 Cache 包装
   * @param cache 被包装的 cache 对象
   * @return
   */
  private Cache setStandardDecorators(Cache cache) {
    try {
      //1.获取 cache 对象对应的 MetaObject 对象
      MetaObject metaCache = SystemMetaObject.forObject(cache);
      //2.如果size属性不为空且有size方法，则进行设置
      if (size != null && metaCache.hasSetter("size")) {
        metaCache.setValue("size", size);
      }
      //3.如果 clearInterval 不为空，则包装成 ScheduledCache 对象
      if (clearInterval != null) {
        cache = new ScheduledCache(cache);
        ((ScheduledCache) cache).setClearInterval(clearInterval);
      }
      //4.如果readWrite为true则包装成 SerializedCache 对象
      if (readWrite) {
        cache = new SerializedCache(cache);
      }
      //5.包装成 LoggingCache 对象
      cache = new LoggingCache(cache);
      //6.包装成 SynchronizedCache 对象
      cache = new SynchronizedCache(cache);
      //7.如果blocking为true则包装成 BlockingCache 对象
      if (blocking) {
        cache = new BlockingCache(cache);
      }
      //8.返回多层包装的 cache 对象
      return cache;
    } catch (Exception e) {
      throw new CacheException("Error building standard cache decorators.  Cause: " + e, e);
    }
  }

  /**
   * 设置缓存属性
   * @param cache
   */
  private void setCacheProperties(Cache cache) {
    //1.如果 properties 属性不为空则设置缓存属性
    if (properties != null) {
      //1.1获取缓存对应的 MetaObject 对象
      MetaObject metaCache = SystemMetaObject.forObject(cache);
      //1.2遍历属性
      for (Map.Entry<Object, Object> entry : properties.entrySet()) {
        //1.3获取属性的键值
        String name = (String) entry.getKey();
        String value = (String) entry.getValue();
        //1.4如果有此属性的setter方法
        if (metaCache.hasSetter(name)) {
          //1.4.1获取 setter 方法参数的类型
          Class<?> type = metaCache.getSetterType(name);
          //1.4.2依据类型设置属性值
          if (String.class == type) {
            metaCache.setValue(name, value);
          } else if (int.class == type
              || Integer.class == type) {
            metaCache.setValue(name, Integer.valueOf(value));
          } else if (long.class == type
              || Long.class == type) {
            metaCache.setValue(name, Long.valueOf(value));
          } else if (short.class == type
              || Short.class == type) {
            metaCache.setValue(name, Short.valueOf(value));
          } else if (byte.class == type
              || Byte.class == type) {
            metaCache.setValue(name, Byte.valueOf(value));
          } else if (float.class == type
              || Float.class == type) {
            metaCache.setValue(name, Float.valueOf(value));
          } else if (boolean.class == type
              || Boolean.class == type) {
            metaCache.setValue(name, Boolean.valueOf(value));
          } else if (double.class == type
              || Double.class == type) {
            metaCache.setValue(name, Double.valueOf(value));
          } else {
            //不支持除以上数据类型的设置
            throw new CacheException("Unsupported property type for cache: '" + name + "' of type " + type);
          }
        }
      }
    }
    //2.如果实现了 InitializingObject 接口，执行进一步cache对象初始化逻辑
    if (InitializingObject.class.isAssignableFrom(cache.getClass())){
      try {
        //3.初始化缓存
        ((InitializingObject) cache).initialize();
      } catch (Exception e) {
        throw new CacheException("Failed cache initialization for '" +
            cache.getId() + "' on '" + cache.getClass().getName() + "'", e);
      }
    }
  }

  /**
   * 创建基础 Cache 对象
   * @param cacheClass Cache实现类（传入 implementation 属性）
   * @param id 编号，命名空间
   * @return Cache 对象
   */
  private Cache newBaseCacheInstance(Class<? extends Cache> cacheClass, String id) {
    //1.获得 cacheClass 类指定的反射构造方法对象
    Constructor<? extends Cache> cacheConstructor = getBaseCacheConstructor(cacheClass);
    try {
      //2.创建对象
      return cacheConstructor.newInstance(id);
    } catch (Exception e) {
      throw new CacheException("Could not instantiate cache implementation (" + cacheClass + "). Cause: " + e, e);
    }
  }

  /**
   * 获得 Cache 类的反射构造方法对象
   * @param cacheClass 指定的cache实现类
   * @return
   */
  private Constructor<? extends Cache> getBaseCacheConstructor(Class<? extends Cache> cacheClass) {
    try {
      return cacheClass.getConstructor(String.class);
    } catch (Exception e) {
      throw new CacheException("Invalid base cache implementation (" + cacheClass + ").  " +
          "Base cache implementations must have a constructor that takes a String id as a parameter.  Cause: " + e, e);
    }
  }

  /**
   * 使用 cacheClass 的实例对象包装 base 对象
   * @param cacheClass 包装的 Cache 类
   * @param base 被包装的 Cache 对象
   * @return 包装后的 Cache 对象
   */
  private Cache newCacheDecoratorInstance(Class<? extends Cache> cacheClass, Cache base) {
    //1.获得cacheClass的方法参数为 Cache 的构造方法
    Constructor<? extends Cache> cacheConstructor = getCacheDecoratorConstructor(cacheClass);
    try {
      //2.创建 Cache对象
      return cacheConstructor.newInstance(base);
    } catch (Exception e) {
      throw new CacheException("Could not instantiate cache decorator (" + cacheClass + "). Cause: " + e, e);
    }
  }

  /**
   * 获得指定 cacheClass 类的方法参数为 Cache 的构造方法
   * @param cacheClass 指定的Cache包装类
   * @return
   */
  private Constructor<? extends Cache> getCacheDecoratorConstructor(Class<? extends Cache> cacheClass) {
    try {
      return cacheClass.getConstructor(Cache.class);
    } catch (Exception e) {
      throw new CacheException("Invalid cache decorator (" + cacheClass + ").  " +
          "Cache decorators must have a constructor that takes a Cache instance as a parameter.  Cause: " + e, e);
    }
  }
}
