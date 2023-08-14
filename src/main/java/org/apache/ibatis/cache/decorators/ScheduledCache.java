/**
 *    Copyright 2009-2017 the original author or authors.
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

import java.util.concurrent.locks.ReadWriteLock;

/**
 * 周期性清理缓存的装饰器
 * 每次缓存操作时，都调用 #clearWhenStale() 方法，根据情况，是否清空全部缓存
 * @author Clinton Begin
 */
public class ScheduledCache implements Cache {

  /**
   * 底层缓存
   */
  private final Cache delegate;
  /**
   * 清理周期，默认 1 小时
   */
  protected long clearInterval;
  /**
   * 上一次清理时间戳，默认初始化为对象的创建时间
   */
  protected long lastClear;

  public ScheduledCache(Cache delegate) {
    this.delegate = delegate;
    this.clearInterval = 60 * 60 * 1000; // 1 hour
    this.lastClear = System.currentTimeMillis();
  }

  public void setClearInterval(long clearInterval) {
    this.clearInterval = clearInterval;
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    clearWhenStale();
    return delegate.getSize();
  }

  @Override
  public void putObject(Object key, Object object) {
    clearWhenStale();
    delegate.putObject(key, object);
  }

  @Override
  public Object getObject(Object key) {
    return clearWhenStale() ? null : delegate.getObject(key);
  }

  @Override
  public Object removeObject(Object key) {
    clearWhenStale();
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
    //记录lastClear
    lastClear = System.currentTimeMillis();
    //清空缓存
    delegate.clear();
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  @Override
  public int hashCode() {
    return delegate.hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return delegate.equals(obj);
  }

  /**
   * 底层定期清理方法
   * @return
   */
  private boolean clearWhenStale() {
    //1.如果当前时间据上次清理时间超过了清理周期，则调用本类clear()方法并返回true，表示已清空缓存
    if (System.currentTimeMillis() - lastClear > clearInterval) {
      clear();
      return true;
    }
    //2.如果在清理周期内，返回flase，表示未清空缓存
    return false;
  }

}
