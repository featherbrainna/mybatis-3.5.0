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
package org.apache.ibatis.cache.decorators;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * 最近最少使用缓存，固定大小超过时最近最少使用逐一清理
 * Lru (least recently used) cache decorator
 *
 * @author Clinton Begin
 */
public class LruCache implements Cache {

  /**
   * 被装饰的底层Cache对象
   */
  private final Cache delegate;
  /**
   * LinkedHashMap<Object, Object>类型对象，它是一个有序HashMap，用于记录key最近的使用情况
   */
  private Map<Object, Object> keyMap;
  /**
   * 记录最少被使用的缓存项的key
   */
  private Object eldestKey;

  public LruCache(Cache delegate) {
    this.delegate = delegate;
    setSize(1024);
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  /**
   * 初始化字段keyMap
   * @param size keyMap的大小
   */
  public void setSize(final int size) {
    /**
     * 注意LinkedHashMap构造器的第三个参数，true表示该LinkedHashMap记录顺序是access-order，
     * 也就是说LinkedHashMap.get()方法会改变记录的顺序
     */
    keyMap = new LinkedHashMap<Object, Object>(size, .75F, true) {
      private static final long serialVersionUID = 4267176411845948333L;

      /**
       * 当调用LinkedHashMap.put()方法时，会调用该方法
       * @param eldest
       * @return
       */
      @Override
      protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
        boolean tooBig = size() > size;
        //如果已到达缓存上限，则更新eldestKey字段，后面会删除该项
        if (tooBig) {
          eldestKey = eldest.getKey();
        }
        return tooBig;
      }
    };
  }

  /**
   * 增加缓存
   * @param key Can be any object but usually it is a {@link CacheKey}
   * @param value The result of a select.
   */
  @Override
  public void putObject(Object key, Object value) {
    //1.添加缓存项
    delegate.putObject(key, value);
    //2.删除最久未使用的缓存项
    cycleKeyList(key);
  }

  /**
   * 获取缓存
   * @param key The key
   * @return
   */
  @Override
  public Object getObject(Object key) {
    //1.调用map的get方法，修改LinkedHashMap中记录的顺序
    keyMap.get(key); //touch
    //2.获取缓存
    return delegate.getObject(key);
  }

  @Override
  public Object removeObject(Object key) {
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    delegate.clear();
    keyMap.clear();
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  /**
   * 底层清理缓存的方法
   * @param key
   */
  private void cycleKeyList(Object key) {
    //1.通过LinkedHashMap的put方法，删除缓存和获取最近最久未使用的key
    keyMap.put(key, key);
    //2.eldestKey不为空，表示已经达到缓存上限
    if (eldestKey != null) {
      //删除最久未使用的缓存项
      delegate.removeObject(eldestKey);
      eldestKey = null;
    }
  }

}
