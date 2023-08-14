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
import java.lang.ref.SoftReference;
import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * 软引用缓存，由JVM内存不足时清理
 * Soft Reference cache decorator
 * Thanks to Dr. Heinz Kabutz for his guidance here.
 *
 * @author Clinton Begin
 */
public class SoftCache implements Cache {
  /**
   * 缓存的强引用集合
   * 在SoftCache中，最近使用的一部分缓存项不会被GC回收，这就是通过将其value添加到
   * hardLinksToAvoidGarbageCollection集合中实现的（即有强引用指向value）
   * hardLinksToAvoidGarbageCollection集合是LinkedList<Object>类型
   */
  private final Deque<Object> hardLinksToAvoidGarbageCollection;
  /**
   * 缓存的引用队列，被回收的软引用记录
   * ReferenceQueue引用队列，用于记录已经被GC回收的缓存项对应的 SoftEntry 对象
   */
  private final ReferenceQueue<Object> queueOfGarbageCollectedEntries;
  /**
   * 底层被装饰的Cache对象
   */
  private final Cache delegate;
  /**
   * 强引用的个数，默认值是256
   */
  private int numberOfHardLinks;

  public SoftCache(Cache delegate) {
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
    //1.清除已经被GC回收的缓存项
    removeGarbageCollectedItems();
    //2.向缓存中添加缓存项，使用封装的软引用对象来缓存
    delegate.putObject(key, new SoftEntry(key, value, queueOfGarbageCollectedEntries));
  }

  /**
   * 获取缓存
   * 此方法可能会创建一个强引用缓存，通过hardLinksToAvoidGarbageCollection.addFirst(result);
   * 在获取缓存时，有一个强引用value的缓存大小256，但若内存足够其毫无效果，内存不够时由于强引用的存在不会回收软引用的对象。
   * @param key The key
   * @return
   */
  @Override
  public Object getObject(Object key) {
    //缓存结果记录
    Object result = null;
    @SuppressWarnings("unchecked") // assumed delegate cache is totally managed by this cache
    //1.从缓存中查找对应的缓存项
    SoftReference<Object> softReference = (SoftReference<Object>) delegate.getObject(key);
    //2.缓存中有对应的缓存项
    if (softReference != null) {
      //2.1获取SoftReference引用的真实value值
      result = softReference.get();
      //2.2value软引用已被GC回收
      if (result == null) {
        //从缓存中清除对应的缓存项
        delegate.removeObject(key);
      } else {
        //2.2value软引用未被GC回收，缓存value到强引用
        // See #586 (and #335) modifications need more than a read lock
        synchronized (hardLinksToAvoidGarbageCollection) {//同步
          //3.缓存项的value添加到 hardLinksToAvoidGarbageCollection 集合中保存
          hardLinksToAvoidGarbageCollection.addFirst(result);
          //4.超过numberOfHardLinks，则将最老的缓存项从hardLinksToAvoidGarbageCollection集合中清除
          if (hardLinksToAvoidGarbageCollection.size() > numberOfHardLinks) {
            hardLinksToAvoidGarbageCollection.removeLast();
          }
        }
      }
    }
    return result;
  }

  /**
   * 删除缓存
   * @param key The key
   * @return
   */
  @Override
  public Object removeObject(Object key) {
    removeGarbageCollectedItems();
    return delegate.removeObject(key);
  }

  /**
   * 清空缓存
   */
  @Override
  public void clear() {
    //1.同步清理强引用集合
    synchronized (hardLinksToAvoidGarbageCollection) {
      hardLinksToAvoidGarbageCollection.clear();
    }
    //2.清理被GC回收的缓存项
    removeGarbageCollectedItems();
    //3.清理底层delegate缓存中的缓存项
    delegate.clear();
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  /**
   * 手动清理垃圾对象SoftEntry，清理垃圾集合中的垃圾
   */
  private void removeGarbageCollectedItems() {
    SoftEntry sv;
    //1.遍历queueOfGarbageCollectedEntries集合
    while ((sv = (SoftEntry) queueOfGarbageCollectedEntries.poll()) != null) {
      //2.将已经被GC回收的value对象对应的缓存项清除，清除底层delegate的map中的value（其类型为SoftEntry），将SoftEntry的强引用断开
      delegate.removeObject(sv.key);
    }
  }

  /**
   * 软引用类。软引用值是缓存值
   */
  private static class SoftEntry extends SoftReference<Object> {
    private final Object key;

    SoftEntry(Object key, Object value, ReferenceQueue<Object> garbageCollectionQueue) {
      //指向value的引用是软引用，且关联了引用队列
      super(value, garbageCollectionQueue);
      //强引用
      this.key = key;
    }
  }

}
