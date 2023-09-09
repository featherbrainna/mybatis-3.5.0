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
package org.apache.ibatis.executor;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

import java.sql.SQLException;
import java.util.List;

/**
 * 执行器接口
 * @author Clinton Begin
 */
public interface Executor {

  /**
   * 空 ResultHandler 对象的枚举
   */
  ResultHandler NO_RESULT_HANDLER = null;

  //###################################### 读和写操作相关的方法 ##############################################################

  /**
   * 更新 or 插入 or 删除，由传入的 MappedStatement 的 SQL 所决定
   * @param ms MappedStatement对象
   * @param parameter 参数对象
   * @return 更新条目数
   * @throws SQLException
   */
  int update(MappedStatement ms, Object parameter) throws SQLException;

  /**
   * 查询，带 ResultHandler、CacheKey、BoundSql
   */
  <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey cacheKey, BoundSql boundSql) throws SQLException;

  /**
   * 查询，带 ResultHandler
   */
  <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException;

  /**
   * 查询，返回值为 Cursor 游标对象
   */
  <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException;

  /**
   * 批量执行SQL语句
   * @return
   * @throws SQLException
   */
  List<BatchResult> flushStatements() throws SQLException;

  //###################################### 事务相关的方法 #############################################################

  /**
   * 提交事务
   * @param required 标志是否需要提交事务
   * @throws SQLException
   */
  void commit(boolean required) throws SQLException;

  /**
   * 回滚事务
   * @param required 标志是否需要回滚事务
   * @throws SQLException
   */
  void rollback(boolean required) throws SQLException;

  //######################################### 缓存相关的方法 #########################################################

  /**
   * 创建缓存中用到的 CacheKey 对象
   */
  CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql);

  /**
   * 根据 CacheKey对象 查找缓存，判断是否缓存（一级缓存）
   * @param ms MappedStatement对象
   * @param key CacheKey对象
   * @return
   */
  boolean isCached(MappedStatement ms, CacheKey key);

  /**
   * 清除本地缓存（即清除一级缓存）
   */
  void clearLocalCache();

  //################################## 设置延迟加载的方法 ################################################################

  /**
   * 延迟加载
   */
  void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType);

  //##################################### 事务相关的方法 #################################################################

  /**
   * 获取事务对象
   * @return
   */
  Transaction getTransaction();

  /**
   * 关闭事务和执行器
   * @param forceRollback 是否关闭前强制回滚
   */
  void close(boolean forceRollback);

  /**
   * 判断事务和执行器是否关闭
   * @return
   */
  boolean isClosed();

  /**
   * 设置包装的 Executor 对象
   * @param executor
   */
  void setExecutorWrapper(Executor executor);

}
