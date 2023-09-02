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
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * 二级缓存。用于预先缓存到二级缓存，当提交事务时放置到一级缓存
 * The 2nd level cache transactional buffer.
 *
 * This class holds all cache entries that are to be added to the 2nd level cache during a Session.
 * Entries are sent to the cache when commit is called or discarded if the Session is rolled back.
 * Blocking cache support has been added. Therefore any get() that returns a cache miss
 * will be followed by a put() so any lock associated with the key can be released.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class TransactionalCache implements Cache {

  private static final Log log = LogFactory.getLog(TransactionalCache.class);

  /**
   * 底层缓存对象
   */
  private final Cache delegate;
  /**
   * 标志是否提交事务时清理，默认初始化为false
   */
  private boolean clearOnCommit;
  /**
   * 二级缓存，在提交事务时缓存到一级缓存
   */
  private final Map<Object, Object> entriesToAddOnCommit;
  /**
   * 一级缓存缺失的key
   */
  private final Set<Object> entriesMissedInCache;

  public TransactionalCache(Cache delegate) {
    this.delegate = delegate;
    this.clearOnCommit = false;
    this.entriesToAddOnCommit = new HashMap<>();
    this.entriesMissedInCache = new HashSet<>();
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
   * 从一级缓存获取缓存
   * @param key The key
   * @return
   */
  @Override
  public Object getObject(Object key) {
    // issue #116
    //1.获取一级缓存值
    Object object = delegate.getObject(key);
    //2.缓存值为null时，将未缓存的键记录到entriesMissedInCache集合
    if (object == null) {
      entriesMissedInCache.add(key);
    }
    // issue #146
    if (clearOnCommit) {
      return null;
    } else {
      return object;
    }
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  /**
   * 添加到二级缓存
   * @param key Can be any object but usually it is a {@link CacheKey}
   * @param object
   */
  @Override
  public void putObject(Object key, Object object) {
    //添加缓存到entriesToAddOnCommit映射集合中即二级缓存中
    entriesToAddOnCommit.put(key, object);
  }

  @Override
  public Object removeObject(Object key) {
    return null;
  }

  /**
   * 清空一级缓存和二级缓存
   */
  @Override
  public void clear() {
    //设置clearOnCommit为true，提交时再清空一级缓存
    clearOnCommit = true;
    //清空二级缓存
    entriesToAddOnCommit.clear();
  }

  /**
   * 提交事务
   */
  public void commit() {
    //1.提交时清理一级缓存，若有清空动作
    if (clearOnCommit) {
      delegate.clear();
    }
    //2.刷新一级缓存使用二级缓存
    flushPendingEntries();
    //3.重置二级缓存
    reset();
  }

  /**
   * 回滚事务
   */
  public void rollback() {
    //1.回滚事务底层实现
    unlockMissedEntries();
    //2.重置二级缓存
    reset();
  }

  /**
   * 重置二级缓存
   */
  private void reset() {
    clearOnCommit = false;
    entriesToAddOnCommit.clear();
    entriesMissedInCache.clear();
  }

  /**
   * 执行二级缓存的事务
   */
  private void flushPendingEntries() {
    //1.将二级缓存的键值添加到一级缓存
    for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
      delegate.putObject(entry.getKey(), entry.getValue());
    }
    //2.处理一级缓存缺失的缓存，若二级缓存也没有就添加null值
    for (Object entry : entriesMissedInCache) {
      if (!entriesToAddOnCommit.containsKey(entry)) {
        delegate.putObject(entry, null);
      }
    }
  }

  /**
   * 回滚事务底层实现 TODO ???不理解
   */
  private void unlockMissedEntries() {
    for (Object entry : entriesMissedInCache) {
      try {
        delegate.removeObject(entry);
      } catch (Exception e) {
        log.warn("Unexpected exception while notifiying a rollback to the cache adapter."
            + "Consider upgrading your cache adapter to the latest version.  Cause: " + e);
      }
    }
  }

}
