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
 * 二级缓存的代理对象
 * 在事务未提交时，entriesToAddOnCommit 属性，会暂存当前事务新产生的缓存 KV 对。
 * 在事务提交时，entriesToAddOnCommit 属性，会同步到二级缓存 delegate 中。
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
   * 底层封装的 二级缓存对象
   */
  private final Cache delegate;
  /**
   * 提交时清空二级缓存
   *
   * 默认初始化为false
   * 当为true时，则表示二级缓存不可用不可查询，提交时会将底层二级缓存清空
   */
  private boolean clearOnCommit;
  /**
   * 待提交的临时缓存。在提交事务时缓存到二级缓存（防止脏读，即读未提交）
   */
  private final Map<Object, Object> entriesToAddOnCommit;
  /**
   * 记录缓存未命中的CacheKey对象（防止BlockingCache底层二级缓存死锁）
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
   * 首先查询底层的二级缓存delegate，并将未命中的key记录到 entriesMissedlnCache 中
   * 查询未提交的结果暂存的 entriesToAddOnCommit 映射集合！！！
   * @param key The key
   * @return
   */
  @Override
  public Object getObject(Object key) {
    // issue #116
    //1.从 delegaet 获取二级缓存key对应的缓存值（代理对象为BlockingCache时会加锁，导致二级缓存不可用阻塞）
    Object object = delegate.getObject(key);
    //2.二级缓存未查找到，则将未缓存的key记录到entriesMissedInCache集合
    if (object == null) {
      entriesMissedInCache.add(key);
    }
    // issue #146
    //3.如果对象 clearOnCommit 属性为 true，表示二级缓存不可用返回null
    if (clearOnCommit) {
      return null;
    } else {
      //3.如果对象 clearOnCommit 属性为 false，直接返回
      return object;
    }
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  /**
   * 添加到二级缓存的临时缓存
   * @param key Can be any object but usually it is a {@link CacheKey}
   * @param object
   */
  @Override
  public void putObject(Object key, Object object) {
    //将缓存项暂存在 entriesToAddOnCommit 映射集合中
    entriesToAddOnCommit.put(key, object);
  }

  /**
   * 不支持删除二级缓存元素
   * @param key The key
   * @return
   */
  @Override
  public Object removeObject(Object key) {
    return null;
  }

  /**
   * 清空二级缓存暂存缓存，且二级缓存不可查。
   * 会清空 entriesToAddOnCommit 集合，并设置 clearOnCommit 为 true
   * 该方法，不会清空 delegate 的缓存。真正的清空，在事务提交时。
   */
  @Override
  public void clear() {
    //1.设置clearOnCommit为true，提交时再清空二级缓存，二级缓存后序不可用
    clearOnCommit = true;
    //2.清空二级缓存的暂存缓存
    entriesToAddOnCommit.clear();
  }

  //################################# 事务相关方法 ##################################################################

  /**
   * 提交事务
   * 事务提交时，才将当前事务中查询时产生的缓存，同步到二级缓存中！！！
   * 因为二级缓存是跨 Session 共享缓存，在事务尚未结束时，不能对二级缓存做任何修改
   */
  public void commit() {
    //1.如果 clearOnCommit 为 true ，则清空delegate二级缓存（提交时才真正清空二级缓存）
    if (clearOnCommit) {
      delegate.clear();
    }
    //2.将 entriesToAddOnCommit、entriesMissedInCache 集合中的数据保存到二级缓存（同步entriesMissedInCache解锁）
    flushPendingEntries();
    //3.重置 clearOnCommit 为 true，并清空 entriesToAddOnCommit、entriesMissedInCache 集合
    reset();
  }

  /**
   * 回滚事务
   */
  public void rollback() {
    //1.将 entriesMissedInCache 集合记录的缓存项从二级缓存中删除
    unlockMissedEntries();
    //2.重置TransactionalCache对象
    reset();
  }

  /**
   * 重置 TransactionalCache 对象，但底层二级缓存对象不变动
   */
  private void reset() {
    //1.重置 clearOnCommit 为 false
    clearOnCommit = false;
    //2.清空 entriesToAddOnCommit、entriesMissedInCache 集合
    entriesToAddOnCommit.clear();
    entriesMissedInCache.clear();
  }

  /**
   * 将 entriesToAddOnCommit、entriesMissedInCache 同步到 delegate 中
   */
  private void flushPendingEntries() {
    //1.遍历 entriesToAddOnCommit 集合，将 二级缓存的暂存缓存 添加到 二级缓存
    for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
      delegate.putObject(entry.getKey(), entry.getValue());
    }
    //2.遍历 entriesMissedInCache 集合，将 entriesToAddOnCommit 集合中不包含的缓存项添加到二级缓存
    for (Object entry : entriesMissedInCache) {
      if (!entriesToAddOnCommit.containsKey(entry)) {
        //保证支持 BlockingCache解锁
        delegate.putObject(entry, null);
      }
    }
  }

  /**
   * 将 entriesMissedInCache 集合记录的缓存项从二级缓存中删除
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
