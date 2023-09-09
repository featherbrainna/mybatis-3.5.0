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
package org.apache.ibatis.executor;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.TransactionalCacheManager;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

import java.sql.SQLException;
import java.util.List;

/**
 * 实现 Executor 接口，支持二级缓存的 Executor 的实现类
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class CachingExecutor implements Executor {

  /**
   * 被代理的 Executor 对象
   */
  private final Executor delegate;
  /**
   * TransactionalCacheManager对象，管理了二级缓存代理对象TransactionalCache
   */
  private final TransactionalCacheManager tcm = new TransactionalCacheManager();

  public CachingExecutor(Executor delegate) {
    this.delegate = delegate;
    //设置 delegate 的包装对象为当前对象
    delegate.setExecutorWrapper(this);
  }

  @Override
  public Transaction getTransaction() {
    return delegate.getTransaction();
  }

  @Override
  public void close(boolean forceRollback) {
    try {
      //issues #499, #524 and #573
      if (forceRollback) {
        tcm.rollback();
      } else {
        tcm.commit();
      }
    } finally {
      delegate.close(forceRollback);
    }
  }

  @Override
  public boolean isClosed() {
    return delegate.isClosed();
  }

  @Override
  public int update(MappedStatement ms, Object parameterObject) throws SQLException {
    //1.根据SQL节点 flushCache 属性，决定是否清空二级缓存（影响二级缓存）
    flushCacheIfRequired(ms);
    //2.执行 delete 对应的方法
    return delegate.update(ms, parameterObject);
  }

  @Override
  public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
    //1.获取 BoundSql 对象
    BoundSql boundSql = ms.getBoundSql(parameterObject);
    //2.创建 CacheKey 对象
    CacheKey key = createCacheKey(ms, parameterObject, rowBounds, boundSql);
    //3.调用 重载query 方法查询
    return query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
  }

  @Override
  public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
    //1.根据select节点 flushCache 属性，决定是否清空二级缓存（影响二级缓存）
    flushCacheIfRequired(ms);
    //2.执行 delegate 对应的方法
    return delegate.queryCursor(ms, parameter, rowBounds);
  }

  /**
   * 带二级缓存的查询
   */
  @Override
  public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql)
      throws SQLException {
    //1.从 MappedStatement 获取即mapper.xml指定的二级缓存对象
    Cache cache = ms.getCache();
    //2.如果二级缓存对象不为空，则从二级缓存对象获取结果（检测指定mapper文件是否开启了二级缓存）
    if (cache != null) {
      //2.1根据select节点 flushCache 属性，决定是否清空二级缓存
      flushCacheIfRequired(ms);
      //2.2如果select节点 useCache 属性为 true且没有使用 resultHandler 对象（检测指定sql节点是否使用二级缓存）
      if (ms.isUseCache() && resultHandler == null) {
        //2.2.1确保没有输出类型的参数
        ensureNoOutParams(ms, boundSql);
        @SuppressWarnings("unchecked")
        //2.2.2从二级缓存中获取结果
        List<E> list = (List<E>) tcm.getObject(cache, key);
        if (list == null) {
          //2.2.3如果二级缓存没有相应的结果对象，则直接委托代理对象查询；否则直接返回结果
          list = delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
          //2.2.4将查询的结果缓存到二级缓存
          tcm.putObject(cache, key, list); // issue #578 and #116
        }
        //2.2.5返回结果
        return list;
      }
    }
    //3.如果二级缓存对象为空，即mapper.xml没有配置cache或cache-ref节点，则直接委托代理对象查询
    return delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
  }

  @Override
  public List<BatchResult> flushStatements() throws SQLException {
    return delegate.flushStatements();
  }

  @Override
  public void commit(boolean required) throws SQLException {
    //1.执行 delegate 的提交
    delegate.commit(required);
    //2.遍历所有相关的 TransactionalCache 对象执行 commit() 方法
    tcm.commit();
  }

  @Override
  public void rollback(boolean required) throws SQLException {
    try {
      //1.执行 delegate 的回滚
      delegate.rollback(required);
    } finally {
      if (required) {
        //2.遍历所有相关的 TransactionalCache 对象执行 rollback() 方法
        tcm.rollback();
      }
    }
  }

  private void ensureNoOutParams(MappedStatement ms, BoundSql boundSql) {
    if (ms.getStatementType() == StatementType.CALLABLE) {
      for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
        if (parameterMapping.getMode() != ParameterMode.IN) {
          throw new ExecutorException("Caching stored procedures with OUT params is not supported.  Please configure useCache=false in " + ms.getId() + " statement.");
        }
      }
    }
  }

  @Override
  public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
    return delegate.createCacheKey(ms, parameterObject, rowBounds, boundSql);
  }

  @Override
  public boolean isCached(MappedStatement ms, CacheKey key) {
    return delegate.isCached(ms, key);
  }

  @Override
  public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
    delegate.deferLoad(ms, resultObject, property, key, targetType);
  }

  @Override
  public void clearLocalCache() {
    delegate.clearLocalCache();
  }

  /**
   * 根据配置清空二级缓存
   * @param ms
   */
  private void flushCacheIfRequired(MappedStatement ms) {
    Cache cache = ms.getCache();
    if (cache != null && ms.isFlushCacheRequired()) {
      tcm.clear(cache);
    }
  }

  @Override
  public void setExecutorWrapper(Executor executor) {
    throw new UnsupportedOperationException("This method should not be called");
  }

}
