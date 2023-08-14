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
package org.apache.ibatis.cache.decorators;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;
import org.apache.ibatis.cache.CacheKey;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 阻塞缓存
 * 这里的阻塞比较特殊，当前线程去获取缓存值时，如果不存在，则会阻塞后续的其他线程去获取该缓存。
 * Simple blocking decorator
 *
 * Simple and inefficient version of EhCache's BlockingCache decorator.
 * It sets a lock over a cache key when the element is not found in cache.
 * This way, other threads will wait until this element is filled instead of hitting the database.
 *
 * @author Eduardo Macarron
 *
 */
public class BlockingCache implements Cache {

  /**
   * 获取锁的阻塞超时时长
   */
  private long timeout;
  /**
   * 被装饰的底层Cache对象
   */
  private final Cache delegate;
  /**
   * 每个key都有对应的ReentrantLock对象
   */
  private final ConcurrentHashMap<Object, ReentrantLock> locks;

  public BlockingCache(Cache delegate) {
    this.delegate = delegate;
    this.locks = new ConcurrentHashMap<>();
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
   * 添加缓存
   * @param key Can be any object but usually it is a {@link CacheKey}
   * @param value The result of a select.
   */
  @Override
  public void putObject(Object key, Object value) {
    try {
      //1.委托delegate添加缓存
      delegate.putObject(key, value);
    } finally {
      //2.释放锁
      releaseLock(key);
    }
  }

  /**
   * 获取缓存
   * @param key The key
   * @return
   */
  @Override
  public Object getObject(Object key) {
    //1.获取该key对应的锁
    acquireLock(key);
    //2.委托delegate查询key对应的缓存
    Object value = delegate.getObject(key);
    //3.缓存有key对应的缓存项，释放锁，否则继续持有锁
    if (value != null) {
      releaseLock(key);
    }
    return value;
  }

  /**
   * 此方法不支持删除缓存，只释放锁
   * @param key The key
   * @return
   */
  @Override
  public Object removeObject(Object key) {
    // despite of its name, this method is called only to release locks
    releaseLock(key);
    return null;
  }

  @Override
  public void clear() {
    delegate.clear();
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  /**
   * 从locks获取指定key对应的ReentrantLock锁对象
   * @param key
   * @return
   */
  private ReentrantLock getLockForKey(Object key) {
    //存在key就获取，不存在就创建
    return locks.computeIfAbsent(key, k -> new ReentrantLock());
  }

  /**
   * 获取锁并加锁
   * @param key
   */
  private void acquireLock(Object key) {
    //1.获取指定key对应的ReentrantLock对象
    Lock lock = getLockForKey(key);
    //2.设置了锁的获取超时时长
    if (timeout > 0) {
      try {
        //2.1带超时时间的锁获取
        boolean acquired = lock.tryLock(timeout, TimeUnit.MILLISECONDS);
        //2.2获取锁超时，则抛出异常
        if (!acquired) {
          throw new CacheException("Couldn't get a lock in " + timeout + " for the key " +  key + " at the cache " + delegate.getId());
        }
      } catch (InterruptedException e) {
        throw new CacheException("Got interrupted while trying to acquire lock for key " + key, e);
      }
    } else {
      //2.没设置锁的获取超时时长，阻塞获取锁
      lock.lock();
    }
  }

  /**
   * 释放锁
   * @param key
   */
  private void releaseLock(Object key) {
    //从locks获取锁对象
    ReentrantLock lock = locks.get(key);
    //若当前线程持有此锁，释放锁
    if (lock.isHeldByCurrentThread()) {
      lock.unlock();
    }
  }

  public long getTimeout() {
    return timeout;
  }

  public void setTimeout(long timeout) {
    this.timeout = timeout;
  }
}
