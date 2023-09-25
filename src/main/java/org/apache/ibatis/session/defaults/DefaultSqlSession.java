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
package org.apache.ibatis.session.defaults;

import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.exceptions.ExceptionFactory;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.result.DefaultMapResultHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * 实现 SqlSession 接口，默认的 SqlSession 实现类
 * The default implementation for {@link SqlSession}.
 * Note that this class is not Thread-Safe.
 *
 * @author Clinton Begin
 */
public class DefaultSqlSession implements SqlSession {

  /**
   * 配置对象。构造器初始化
   */
  private final Configuration configuration;
  /**
   * 执行器。构造器初始化
   */
  private final Executor executor;

  /**
   * 标志是否自动提交。构造器初始化
   */
  private final boolean autoCommit;
  /**
   * 是否发生数据变更，即当前缓存是否有脏数据。【该参数，会在事务的提交和回滚，产生其用途。】
   */
  private boolean dirty;
  /**
   * Cursor列表。由 {@link #registerCursor(Cursor)} 进行初始化空列表
   * 为防止用户忘记关闭已打开的游标对象，会通过cursorList字段记录由该SqlSession对象生成的游标对象
   */
  private List<Cursor<?>> cursorList;

  /**
   * 构造器。初始化属性
   * @param configuration
   * @param executor
   * @param autoCommit
   */
  public DefaultSqlSession(Configuration configuration, Executor executor, boolean autoCommit) {
    this.configuration = configuration;
    this.executor = executor;
    this.dirty = false;
    this.autoCommit = autoCommit;
  }

  public DefaultSqlSession(Configuration configuration, Executor executor) {
    this(configuration, executor, false);
  }

  @Override
  public <T> T selectOne(String statement) {
    return this.<T>selectOne(statement, null);
  }

  /**
   * 底层调用 {@link #selectList(String, Object)} 实现
   * @param statement Unique identifier matching the statement to use.sql节点的编号
   * @param parameter A parameter object to pass to the statement.用户传入的实参
   * @param <T>
   * @return
   */
  @Override
  public <T> T selectOne(String statement, Object parameter) {
    // Popular vote was to return null on 0 results and throw exception on too many.
    //1.查询获取结果对象集合
    List<T> list = this.selectList(statement, parameter);
    //2.集合一个元素，返回第一个元素
    if (list.size() == 1) {
      return list.get(0);
    } else if (list.size() > 1) {
      //2.集合多个元素，抛出异常
      throw new TooManyResultsException("Expected one result (or null) to be returned by selectOne(), but found: " + list.size());
    } else {
      //2.集合零个元素，返回null
      return null;
    }
  }

  @Override
  public <K, V> Map<K, V> selectMap(String statement, String mapKey) {
    return this.selectMap(statement, null, mapKey, RowBounds.DEFAULT);
  }

  @Override
  public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey) {
    return this.selectMap(statement, parameter, mapKey, RowBounds.DEFAULT);
  }

  /**
   * 底层调用 {@link #selectList(String, Object, RowBounds)} 实现
   * @param statement Unique identifier matching the statement to use.
   * @param parameter A parameter object to pass to the statement.
   * @param mapKey The property to use as key for each value in the list.
   * @param rowBounds  Bounds to limit object retrieval
   * @param <K>
   * @param <V>
   * @return
   */
  @Override
  public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey, RowBounds rowBounds) {
    //1.调用 selectList 执行查询
    final List<? extends V> list = selectList(statement, parameter, rowBounds);
    //2.传入mapKey等创建 DefaultMapResultHandler 对象
    final DefaultMapResultHandler<K, V> mapResultHandler = new DefaultMapResultHandler<>(mapKey,
            configuration.getObjectFactory(), configuration.getObjectWrapperFactory(), configuration.getReflectorFactory());
    //3.创建 DefaultResultContext 对象
    final DefaultResultContext<V> context = new DefaultResultContext<>();
    //4.遍历查询结果对象集合
    for (V o : list) {
      //5.在 结果上下文 设置 当前结果对象
      context.nextResultObject(o);
      //6.使用 DefaultMapResultHandler 处理当前结果上下文，将结果的当前结果元素聚合成 Map
      mapResultHandler.handleResult(context);
    }
    //7.从 DefaultMapResultHandler 获取map结果
    return mapResultHandler.getMappedResults();
  }

  @Override
  public <T> Cursor<T> selectCursor(String statement) {
    return selectCursor(statement, null);
  }

  @Override
  public <T> Cursor<T> selectCursor(String statement, Object parameter) {
    return selectCursor(statement, parameter, RowBounds.DEFAULT);
  }

  /**
   * 底层调用 executor.queryCursor(...) 实现
   * @param statement Unique identifier matching the statement to use.
   * @param parameter A parameter object to pass to the statement.
   * @param rowBounds  Bounds to limit object retrieval
   * @param <T>
   * @return
   */
  @Override
  public <T> Cursor<T> selectCursor(String statement, Object parameter, RowBounds rowBounds) {
    try {
      //1.从configuration获取 MappedStatement 对象
      MappedStatement ms = configuration.getMappedStatement(statement);
      //2.调用executor.queryCursor(...)执行查询
      Cursor<T> cursor = executor.queryCursor(ms, wrapCollection(parameter), rowBounds);
      //3.注册游标对象，将游标对象添加到cursorList中
      registerCursor(cursor);
      //4.返回游标对象
      return cursor;
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error querying database.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public <E> List<E> selectList(String statement) {
    return this.selectList(statement, null);
  }

  @Override
  public <E> List<E> selectList(String statement, Object parameter) {
    return this.selectList(statement, parameter, RowBounds.DEFAULT);
  }

  /**
   * 底层调用 {@link Executor#query(MappedStatement, Object, RowBounds, ResultHandler)} 实现
   * @param statement Unique identifier matching the statement to use.sql节点的编号
   * @param parameter A parameter object to pass to the statement.用户传入的实参
   * @param rowBounds  Bounds to limit object retrieval行限制
   * @param <E>
   * @return
   */
  @Override
  public <E> List<E> selectList(String statement, Object parameter, RowBounds rowBounds) {
    try {
      //1.从配置对象获取 MappedStatement 对象
      MappedStatement ms = configuration.getMappedStatement(statement);
      //2.使用执行器调用 query 方法
      return executor.query(ms, wrapCollection(parameter), rowBounds, Executor.NO_RESULT_HANDLER);
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error querying database.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public void select(String statement, Object parameter, ResultHandler handler) {
    select(statement, parameter, RowBounds.DEFAULT, handler);
  }

  @Override
  public void select(String statement, ResultHandler handler) {
    select(statement, null, RowBounds.DEFAULT, handler);
  }

  /**
   * 执行查询，使用传入的 handler 方法参数，对结果进行处理
   * @param statement Unique identifier matching the statement to use.
   * @param parameter
   * @param rowBounds RowBound instance to limit the query results
   * @param handler ResultHandler that will handle each retrieved row
   */
  @Override
  public void select(String statement, Object parameter, RowBounds rowBounds, ResultHandler handler) {
    try {
      //1.从configuration获取 MappedStatement 对象
      MappedStatement ms = configuration.getMappedStatement(statement);
      //2.调用executor.query执行查询
      executor.query(ms, wrapCollection(parameter), rowBounds, handler);
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error querying database.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public int insert(String statement) {
    return insert(statement, null);
  }

  @Override
  public int insert(String statement, Object parameter) {
    return update(statement, parameter);
  }

  @Override
  public int update(String statement) {
    return update(statement, null);
  }

  /**
   * 执行更新
   * @param statement Unique identifier matching the statement to execute.
   * @param parameter A parameter object to pass to the statement.
   * @return
   */
  @Override
  public int update(String statement, Object parameter) {
    try {
      //1.标记 dirty 为true，表示执行过写操作。该参数，会在事务的提交和回滚，产生其用途。
      dirty = true;
      //2.从configuration获取 MappedStatement 对象
      MappedStatement ms = configuration.getMappedStatement(statement);
      //3.调用executor.update执行更新操作
      return executor.update(ms, wrapCollection(parameter));
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error updating database.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public int delete(String statement) {
    return update(statement, null);
  }

  @Override
  public int delete(String statement, Object parameter) {
    return update(statement, parameter);
  }

  @Override
  public void commit() {
    commit(false);
  }

  /**
   * 提交事务。底层调用 executor.commit 实现
   * @param force forces connection commit
   */
  @Override
  public void commit(boolean force) {
    try {
      //1.执行提交事务
      executor.commit(isCommitOrRollbackRequired(force));
      //2.标记 dirty 为 false
      dirty = false;
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error committing transaction.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public void rollback() {
    rollback(false);
  }

  /**
   * 回滚事务
   * @param force forces connection rollback
   */
  @Override
  public void rollback(boolean force) {
    try {
      //1.执行回滚事务
      executor.rollback(isCommitOrRollbackRequired(force));
      //2.标记 dirty 为 false
      dirty = false;
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error rolling back transaction.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  /**
   * 提交批处理
   * 调用 executor.flushStatements() 实现
   * @return
   */
  @Override
  public List<BatchResult> flushStatements() {
    try {
      return executor.flushStatements();
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error flushing statements.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  /**
   * 关闭会话
   */
  @Override
  public void close() {
    try {
      //1.关闭执行器。只有 没有自动提交且存在写操作时 才会在关闭前强制回滚
      executor.close(isCommitOrRollbackRequired(false));
      //2.关闭所有游标对象
      closeCursors();
      //3.标志 dirty 为 false
      dirty = false;
    } finally {
      ErrorContext.instance().reset();
    }
  }

  /**
   * 关闭所有注册过的游标对象（selectCursor种注册）
   */
  private void closeCursors() {
    //1.如果 cursorList 非空，且cursorList大小非0
    if (cursorList != null && cursorList.size() != 0) {
      //1.1遍历 cursorList 中的游标对象
      for (Cursor<?> cursor : cursorList) {
        try {
          //1.2关闭游标
          cursor.close();
        } catch (IOException e) {
          throw ExceptionFactory.wrapException("Error closing cursor.  Cause: " + e, e);
        }
      }
      //1.3清空 cursorList 元素
      cursorList.clear();
    }
  }

  /**
   * 获取全局配置对象。由构造器初始化
   * @return
   */
  @Override
  public Configuration getConfiguration() {
    return configuration;
  }

  /**
   * 获取 Mapper 接口对应的 Mapper 对象
   * @param type Mapper interface class
   * @param <T> Mapper类型
   * @return Mapper 对象
   */
  @Override
  public <T> T getMapper(Class<T> type) {
    return configuration.<T>getMapper(type, this);
  }

  /**
   * 获取数据库连接对象
   * @return
   */
  @Override
  public Connection getConnection() {
    try {
      return executor.getTransaction().getConnection();
    } catch (SQLException e) {
      throw ExceptionFactory.wrapException("Error getting a new connection.  Cause: " + e, e);
    }
  }

  /**
   * 清除一级缓存
   */
  @Override
  public void clearCache() {
    executor.clearLocalCache();
  }

  /**
   * 注册游标对象，将游标对象添加到 cursorList
   * @param cursor
   * @param <T>
   */
  private <T> void registerCursor(Cursor<T> cursor) {
    if (cursorList == null) {
      cursorList = new ArrayList<>();
    }
    cursorList.add(cursor);
  }

  /**
   * 判断是否执行提交或回滚
   * 方法返回true的两种情况
   * 1.未开启自动提交，且数据发送写操作
   * 2.强制提交
   * @param force 是否强制提交。即true则方法一定返回true，false则方法返回不确定
   * @return
   */
  private boolean isCommitOrRollbackRequired(boolean force) {
    return (!autoCommit && dirty) || force;
  }

  /**
   * 封装参数对象。由读写方法调用
   * 1.若参数 object 是 Collection、Array参数类型的情况下，包装成 Map 返回
   * 2.若参数为 普通类型 和 Map类型，则直接返回
   * @param object 原始参数对象
   * @return 被封装的参数对象
   */
  private Object wrapCollection(final Object object) {
    //1.如果参数类型为 集合类型
    if (object instanceof Collection) {
      //1.1创建 StrictMap
      StrictMap<Object> map = new StrictMap<>();
      //1.2将collection:object添加到 map 映射
      map.put("collection", object);
      //1.3如果结果对象是集合类型中的 List类型，再将list:object添加到 map 映射
      if (object instanceof List) {
        map.put("list", object);
      }
      //1.4返回map对象
      return map;
    } else if (object != null && object.getClass().isArray()) {
      //2.如果参数类型不为空，且参数类型为 数组
      //2.1创建 StrictMap
      StrictMap<Object> map = new StrictMap<>();
      //2.2将array:object添加到 map 映射
      map.put("array", object);
      //2.3返回map对象
      return map;
    }
    //3.如果参数类型为 普通类型 或 Map类型，则直接返回
    return object;
  }

  public static class StrictMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = -5741767162221585340L;

    @Override
    public V get(Object key) {
      if (!super.containsKey(key)) {
        throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + this.keySet());
      }
      return super.get(key);
    }

  }

}
