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
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

import java.util.concurrent.locks.ReadWriteLock;

/**
 * 带日志功能的缓存，添加日志功能
 * @author Clinton Begin
 */
public class LoggingCache implements Cache {

  /**
   * 日志对象
   */
  private final Log log;
  /**
   * 底层缓存对象
   */
  private final Cache delegate;
  /**
   * 请求缓存次数
   */
  protected int requests = 0;
  /**
   * 命中缓存次数
   */
  protected int hits = 0;

  public LoggingCache(Cache delegate) {
    this.delegate = delegate;
    this.log = LogFactory.getLog(getId());
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  @Override
  public void putObject(Object key, Object object) {
    delegate.putObject(key, object);
  }

  /**
   * 获取缓存
   * @param key The key
   * @return
   */
  @Override
  public Object getObject(Object key) {
    //1.递增访问次数requests字段
    requests++;
    //2.获取缓存值
    final Object value = delegate.getObject(key);
    //3.获取成功，命中字段递增
    if (value != null) {
      hits++;
    }
    //4.打印命中率
    if (log.isDebugEnabled()) {
      log.debug("Cache Hit Ratio [" + getId() + "]: " + getHitRatio());
    }
    //5.返回缓存值
    return value;
  }

  @Override
  public Object removeObject(Object key) {
    return delegate.removeObject(key);
  }

  @Override
  public void clear() {
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
   * 通过hits、requests字段计算命中率
   * @return
   */
  private double getHitRatio() {
    return (double) hits / (double) requests;
  }

}
