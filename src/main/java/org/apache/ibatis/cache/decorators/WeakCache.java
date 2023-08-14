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
package org.apache.ibatis.cache.decorators;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * 弱引用缓存，GC时清理缓存
 * Weak Reference cache decorator.
 * Thanks to Dr. Heinz Kabutz for his guidance here.
 *
 * @author Clinton Begin
 */
public class WeakCache implements Cache {
  /**
   * 强引用value列表集合
   */
  private final Deque<Object> hardLinksToAvoidGarbageCollection;
  /**
   * 引用队列，被GC的弱引用WeakReference对象集合
   */
  private final ReferenceQueue<Object> queueOfGarbageCollectedEntries;
  /**
   * 底层缓存对象
   */
  private final Cache delegate;
  /**
   * 强引用缓存大小，默认256
   */
  private int numberOfHardLinks;

  public WeakCache(Cache delegate) {
    this.delegate = delegate;
    this.numberOfHardLinks = 256;
    this.hardLinksToAvoidGarbageCollection = new LinkedList<>();
    this.queueOfGarbageCollectedEntries = new ReferenceQueue<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    // 移除已经被 GC 回收的 WeakEntry
    removeGarbageCollectedItems();
    return delegate.getSize();
  }

  public void setSize(int size) {
    this.numberOfHardLinks = size;
  }

  /**
   * 添加缓存
   * @param key Can be any object but usually it is a {@link CacheKey}
   * @param value The result of a select.
   */
  @Override
  public void putObject(Object key, Object value) {
    //1.清理引用队列，移除已经被 GC 回收的 WeakEntry
    removeGarbageCollectedItems();
    //2.向缓存中添加封装的缓存项WeakEntry
    delegate.putObject(key, new WeakEntry(key, value, queueOfGarbageCollectedEntries));
  }

  /**
   * 获取缓存
   * @param key The key
   * @return
   */
  @Override
  public Object getObject(Object key) {
    Object result = null;
    @SuppressWarnings("unchecked") // assumed delegate cache is totally managed by this cache
    //1.获取缓存项
    WeakReference<Object> weakReference = (WeakReference<Object>) delegate.getObject(key);
    //2.缓存项存在
    if (weakReference != null) {
      //2.1获取缓存项封装的value
      result = weakReference.get();
      //3.value为null，说明被GC清理了，删除缓存项
      if (result == null) {
        delegate.removeObject(key);
      } else {
        //3.不为null，未被GC清理，添加value到强引用集合
        hardLinksToAvoidGarbageCollection.addFirst(result);
        if (hardLinksToAvoidGarbageCollection.size() > numberOfHardLinks) {
          hardLinksToAvoidGarbageCollection.removeLast();
        }
      }
    }
    return result;
  }

  @Override
  public Object removeObject(Object key) {
    // 移除已经被 GC 回收的 WeakEntry
    removeGarbageCollectedItems();
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    hardLinksToAvoidGarbageCollection.clear();
    removeGarbageCollectedItems();
    delegate.clear();
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  /**
   * 手动清理垃圾对象WeakEntry，清理垃圾集合中的垃圾
   */
  private void removeGarbageCollectedItems() {
    WeakEntry sv;
    while ((sv = (WeakEntry) queueOfGarbageCollectedEntries.poll()) != null) {
      delegate.removeObject(sv.key);
    }
  }

  /**
   * 封装的缓存项，弱引用类
   */
  private static class WeakEntry extends WeakReference<Object> {
    private final Object key;

    private WeakEntry(Object key, Object value, ReferenceQueue<Object> garbageCollectionQueue) {
      //关联引用队列
      super(value, garbageCollectionQueue);
      this.key = key;
    }
  }

}
