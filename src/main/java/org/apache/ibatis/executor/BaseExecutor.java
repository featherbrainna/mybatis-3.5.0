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

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

/**
 * 实现 Executor 接口，提供骨架方法，从而使子类只要实现指定的几个抽象方法即可
 *
 * BaseExecutor的本地缓存属性就是 一级缓存
 * @author Clinton Begin
 */
public abstract class BaseExecutor implements Executor {

  private static final Log log = LogFactory.getLog(BaseExecutor.class);

  /**
   * 事务对象。实现事务的提交、回滚和关闭操作
   */
  protected Transaction transaction;
  /**
   * 其中封装的Executor对象
   */
  protected Executor wrapper;

  /**
   * DeferredLoad（延迟加载）队列
   */
  protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
  /**
   * 本地缓存即一级缓存。用于缓存该 Executor 对象查询结果集映射得到的结果对象。
   */
  protected PerpetualCache localCache;
  /**
   * 一级缓存，用于缓存输出类型的参数
   */
  protected PerpetualCache localOutputParameterCache;
  /**
   * mybatis配置对象
   */
  protected Configuration configuration;

  /**
   * 记录嵌套查询的层数
   */
  protected int queryStack;
  /**
   * 是否关闭
   */
  private boolean closed;

  /**
   * 构造器。初始化所有属性
   * @param configuration mybtais配置对象
   * @param transaction 事务对象
   */
  protected BaseExecutor(Configuration configuration, Transaction transaction) {
    this.transaction = transaction;
    this.deferredLoads = new ConcurrentLinkedQueue<>();
    this.localCache = new PerpetualCache("LocalCache");
    this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
    this.closed = false;
    this.configuration = configuration;
    this.wrapper = this;
  }

  /**
   * 获得事务对象（构造对象时初始化的对象）
   * @return
   */
  @Override
  public Transaction getTransaction() {
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    return transaction;
  }

  /**
   * 关闭事务和执行器（执行器和事务同时关闭）
   * @param forceRollback 是否关闭前强制回滚
   */
  @Override
  public void close(boolean forceRollback) {
    try {
      try {
        //1.依据 forceRollback 执行回滚事务
        rollback(forceRollback);
      } finally {
        //2.如果事务对象不为空，则关闭事务
        if (transaction != null) {
          transaction.close();
        }
      }
    } catch (SQLException e) {
      // Ignore.  There's nothing that can be done at this point.
      log.warn("Unexpected exception on closing transaction.  Cause: " + e);
    } finally {
      //3.最终置空执行器的属性
      transaction = null;
      deferredLoads = null;
      localCache = null;
      localOutputParameterCache = null;
      closed = true;
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  /**
   * 执行写操作
   * @param ms MappedStatement对象
   * @param parameter 参数对象
   * @return 影响条目数
   * @throws SQLException
   */
  @Override
  public int update(MappedStatement ms, Object parameter) throws SQLException {
    ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
    //1.已经关闭，则抛出异常
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    //2.清空一级（本地）缓存
    clearLocalCache();
    //3.执行写操作
    return doUpdate(ms, parameter);
  }

  /**
   * 批量执行SQL语句
   * @return
   * @throws SQLException
   */
  @Override
  public List<BatchResult> flushStatements() throws SQLException {
    return flushStatements(false);
  }

  /**
   * 批量 执行或回滚 SQL语句
   * @param isRollBack 标志是回滚还是执行。true回滚（不执行）,false执行
   * @return
   * @throws SQLException
   */
  public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
    //1.关闭，抛出异常
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    //2.执行批处理SQL语句
    return doFlushStatements(isRollBack);
  }

  /**
   * 执行MappedStatement的查询（模板方法）
   * @param ms MappedStatement对象
   * @param parameter 参数对象
   * @param rowBounds 行限制对象
   * @param resultHandler 结果处理器
   * @param <E> 结果类型
   * @return 结果集合
   * @throws SQLException
   */
  @Override
  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
    //1.获取 BoundSql 对象
    BoundSql boundSql = ms.getBoundSql(parameter);
    //2.创建 CacheKey 对象
    CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
    //3.查询，调用query()的另一个重载
    return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
 }

  /**
   * 执行MappedStatement的查询（模板方法）
   * @param ms MappedStatement对象
   * @param parameter 参数对象
   * @param rowBounds 行限制对象
   * @param resultHandler 结果处理器
   * @param key 缓存键对象。CacheKey对象
   * @param boundSql BoundSql对象
   * @param <E> 结果类型
   * @return 结果集合
   * @throws SQLException
   */
  @SuppressWarnings("unchecked")
  @Override
  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
    ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
    //1.如果 Executor 关闭，则抛出异常
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    //2.如果 queryStack 为0并且MappedStatement对应的 select节点flushcache属性 为true
    if (queryStack == 0 && ms.isFlushCacheRequired()) {
      //清空一级缓存
      clearLocalCache();
    }
    List<E> list;
    try {
      //queryStack查询嵌套层数+1
      queryStack++;
      //3.当传入的 resultHandler 为空时，从一级缓存中获取查询结果
      list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
      if (list != null) {
        //4.获取到结果，则进行处理。获取缓存的输出类型参数设置到用户参数对象中（针对存储过程的处理）
        handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
      } else {
        //4.获取不到结果，则从数据库查询。其中调用 doQuery() 方法完成数据库查询
        list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
      }
    } finally {
      //queryStack查询嵌套层数-1
      queryStack--;
    }
    //5.如果 queryStack 查询嵌套层数为0，则查询结束
    if (queryStack == 0) {
      //5.1遍历延迟加载队列，执行延迟加载。触发 DeferredLoad 加载一级缓存中记录的嵌套查询的结果对象
      for (DeferredLoad deferredLoad : deferredLoads) {
        deferredLoad.load();
      }
      // issue #601
      //5.2清空 deferredLoads 延迟加载队列
      deferredLoads.clear();
      //5.3如果缓存级别是 LocalCacheScope.STATEMENT，则清空一级缓存（默认情况下，缓存级别是 LocalCacheScope.SESSION）
      if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
        // issue #482
        clearLocalCache();
      }
    }
    return list;
  }

  /**
   * 执行查询，返回的结果为 Cursor 游标对象（此查询没有一级缓存）
   * @param ms MappedStatement对象
   * @param parameter 查询参数
   * @param rowBounds 行限制对象
   * @param <E> 结果类型
   * @return 游标对象
   * @throws SQLException
   */
  @Override
  public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
    //1.从 MappedStatement 对象获取 BoundSql 对象
    BoundSql boundSql = ms.getBoundSql(parameter);
    //2.执行查询
    return doQueryCursor(ms, parameter, rowBounds, boundSql);
  }

  /**
   * 延迟加载。创建 DeferredLoad 对象并将其添加到 deferredLoads 集合中
   * @param ms MappedStatement对象
   * @param resultObject 外层结果对象的MetaObject
   * @param property 外层结果对象的属性名
   * @param key CacheKey对象
   * @param targetType 目标类型
   */
  @Override
  public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
    //1.如果执行器关闭，抛出异常
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    //2.创建 DeferredLoad 对象
    DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration, targetType);
    //3.如果可加载，则执行加载（一级缓存已记录了指定查询的结果对象，则直接缓存获取）
    if (deferredLoad.canLoad()) {
      deferredLoad.load();
    } else {
      //3.如果无法加载，则添加到 deferredLoads 队列中，待外层查询结束后，再加载该结果对象
      deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
    }
  }

  /**
   * 创建 CacheKey 对象。（固定不变方法）CacheKey对象的updateList集合包含：
   * 1.xml文件select节点 id 属性
   * 2.RowBounds的 offset、limit 属性
   * 3.从MappedStatement获取的boundSql对象中的 sql语句
   * 4.sql传入参数值
   * 5.mybatis-config.xml文件中environment节点的 id 属性
   * @param ms MappedStatement对象
   * @param parameterObject 用户传入参数对象
   * @param rowBounds 行限制RowBounds对象
   * @param boundSql BoundSql对象
   * @return CacheKey对象
   */
  @Override
  public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
    //1.检测是否关闭，关闭抛出异常
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    //2.创建 CacheKey 对象
    CacheKey cacheKey = new CacheKey();
    //3.将 MappedStatement的id 添加到CacheKey对象
    cacheKey.update(ms.getId());
    //4.将 offset、limit 添加到CacheKey对象
    cacheKey.update(rowBounds.getOffset());
    cacheKey.update(rowBounds.getLimit());
    //5.将 boundSql中的sql语句 添加到CacheKey对象
    cacheKey.update(boundSql.getSql());
    //6.获取 boundSql中的ParameterMapping集合
    List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
    // mimic DefaultParameterHandler logic这块逻辑，和 DefaultParameterHandler 获取 value 是一致的
    //7.遍历 ParameterMapping集合。获取用户传入的参数，并添加到CacheKey对象
    for (ParameterMapping parameterMapping : parameterMappings) {
      //过滤掉输出类型的参数
      if (parameterMapping.getMode() != ParameterMode.OUT) {
        Object value;
        //获取参数名
        String propertyName = parameterMapping.getProperty();
        if (boundSql.hasAdditionalParameter(propertyName)) {
          //从 additionalParameters 映射中获取参数值
          value = boundSql.getAdditionalParameter(propertyName);
        } else if (parameterObject == null) {
          //参数对象为空值为空
          value = null;
        } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
          //单个参数值
          value = parameterObject;
        } else {
          //多个参数值从parameterObject中获取
          MetaObject metaObject = configuration.newMetaObject(parameterObject);
          value = metaObject.getValue(propertyName);
        }
        //依次将参数值添加到CacheKey对象
        cacheKey.update(value);
      }
    }
    //8.设置 Environment.id 到 CacheKey 对象中
    if (configuration.getEnvironment() != null) {
      // issue #176
      cacheKey.update(configuration.getEnvironment().getId());
    }
    return cacheKey;
  }

  /**
   * 判断指定的 CacheKey对象 一级缓存是否存在
   * @param ms MappedStatement对象
   * @param key CacheKey对象
   * @return
   */
  @Override
  public boolean isCached(MappedStatement ms, CacheKey key) {
    return localCache.getObject(key) != null;
  }

  /**
   * 提交事务
   * @param required 标志是否需要提交事务
   * @throws SQLException
   */
  @Override
  public void commit(boolean required) throws SQLException {
    //1.关闭，则抛出异常
    if (closed) {
      throw new ExecutorException("Cannot commit, transaction is already closed");
    }
    //2.清空一级（本地）缓存
    clearLocalCache();
    //3.批量执行SQL语句
    flushStatements();
    //4.需要提交事务，则依赖 transaction 对象提交事务
    if (required) {
      transaction.commit();
    }
  }

  /**
   * 回滚事务
   * @param required 标志是否需要回滚事务
   * @throws SQLException
   */
  @Override
  public void rollback(boolean required) throws SQLException {
    //1.没有关闭，进行回滚处理
    if (!closed) {
      try {
        //2.清空一级（本地）缓存
        clearLocalCache();
        //3.批量执行SQL语句
        flushStatements(true);
      } finally {
        //4.需要回滚事务，则依赖 transaction 对象回滚事务
        if (required) {
          transaction.rollback();
        }
      }
    }
  }

  /**
   * 清理一级（本地）缓存
   * 1.select等节点flushCache设置 和 config的localCacheScope设置会清空缓存
   * 2.update\delete\insert操作会清空缓存
   * 3.事务操作会清空缓存
   */
  @Override
  public void clearLocalCache() {
    if (!closed) {
      //1.本地缓存清空
      localCache.clear();
      //2.本地输出参数缓存清空
      localOutputParameterCache.clear();
    }
  }

  /**
   * 执行 update\insert\delete 语句（核心执行sql修改语句）
   * @param ms MappedStatement对象
   * @param parameter 用户传入参数
   * @return 影响条目数
   * @throws SQLException
   */
  protected abstract int doUpdate(MappedStatement ms, Object parameter)
      throws SQLException;

  /**
   * 执行批量sql语句
   * @param isRollback 标记是否为批量回滚。false 表示执行， true 表示不执行
   * @return
   * @throws SQLException
   */
  protected abstract List<BatchResult> doFlushStatements(boolean isRollback)
      throws SQLException;

  protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
      throws SQLException;

  protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
      throws SQLException;

  /**
   * 关闭 Statement 对象
   * @param statement
   */
  protected void closeStatement(Statement statement) {
    if (statement != null) {
      try {
        statement.close();
      } catch (SQLException e) {
        // ignore
      }
    }
  }

  /**
   * 设置事务超时时间
   * Apply a transaction timeout.
   * @param statement a current statement
   * @throws SQLException if a database access error occurs, this method is called on a closed <code>Statement</code>
   * @since 3.4.0
   * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
   */
  protected void applyTransactionTimeout(Statement statement) throws SQLException {
    StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(), transaction.getTimeout());
  }

  private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter, BoundSql boundSql) {
    if (ms.getStatementType() == StatementType.CALLABLE) {
      final Object cachedParameter = localOutputParameterCache.getObject(key);
      if (cachedParameter != null && parameter != null) {
        final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
        final MetaObject metaParameter = configuration.newMetaObject(parameter);
        for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
          if (parameterMapping.getMode() != ParameterMode.IN) {
            final String parameterName = parameterMapping.getProperty();
            final Object cachedValue = metaCachedParameter.getValue(parameterName);
            metaParameter.setValue(parameterName, cachedValue);
          }
        }
      }
    }
  }

  /**
   * 从数据库查询数据，并缓存查询得到的数据
   */
  private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
    List<E> list;
    //1.在缓存中，添加占位对象。和延迟加载有关，可见`DeferredLoad#canLoad()`方法
    localCache.putObject(key, EXECUTION_PLACEHOLDER);
    try {
      //2.执行查询操作
      list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
    } finally {
      //3.从缓存中，移除占位对象
      localCache.removeObject(key);
    }
    //4.将结果添加到缓存中
    localCache.putObject(key, list);
    //5.存储过程相关，缓存输出参数
    if (ms.getStatementType() == StatementType.CALLABLE) {
      localOutputParameterCache.putObject(key, parameter);
    }
    return list;
  }

  /**
   * 获得 Connection 数据库连接对象
   * @param statementLog 日志对象
   * @return 数据库连接对象
   * @throws SQLException
   */
  protected Connection getConnection(Log statementLog) throws SQLException {
    //1.从事务对象获取 Connection 数据库连接对象
    Connection connection = transaction.getConnection();
    //2.如果 debug 日志级别，则依赖 ConnectionLogger 创建 Connection 的代理对象（代理实现日志记录）
    if (statementLog.isDebugEnabled()) {
      return ConnectionLogger.newInstance(connection, statementLog, queryStack);
    } else {
      return connection;
    }
  }

  /**
   * 设置包装器
   * @param wrapper
   */
  @Override
  public void setExecutorWrapper(Executor wrapper) {
    this.wrapper = wrapper;
  }

  /**
   * 内部静态类
   * 延迟加载对象。它负责从 localCache 一级缓存中延迟加载结果对象
   */
  private static class DeferredLoad {

    /**
     * 外层对象对应的 MetaObject 对象
     */
    private final MetaObject resultObject;
    /**
     * 外层对象的延迟加载的属性名称
     */
    private final String property;
    /**
     * 延迟加载的属性的类型
     */
    private final Class<?> targetType;
    /**
     * 延迟加载的结果对象在一级缓存中相应的CacheKey对象
     */
    private final CacheKey key;
    /**
     * 一级缓存，与 BaseExecutor.localCache 字段指向同一 PerpetualCache 对象
     */
    private final PerpetualCache localCache;
    /**
     * 对象工厂
     */
    private final ObjectFactory objectFactory;
    /**
     * 负责结果对象的类型转换
     */
    private final ResultExtractor resultExtractor;

    // issue #781
    public DeferredLoad(MetaObject resultObject,
                        String property,
                        CacheKey key,
                        PerpetualCache localCache,
                        Configuration configuration,
                        Class<?> targetType) {
      this.resultObject = resultObject;
      this.property = property;
      this.key = key;
      this.localCache = localCache;
      //初始化工厂对象
      this.objectFactory = configuration.getObjectFactory();
      //初始化resultExtractor
      this.resultExtractor = new ResultExtractor(configuration, objectFactory);
      this.targetType = targetType;
    }

    /**
     * 判断 一级缓存项 是否加载完全到缓存中
     * @return
     */
    public boolean canLoad() {
      //检测缓存是否存在指定的结果对象
      //检测是否为占位符
      return localCache.getObject(key) != null && localCache.getObject(key) != EXECUTION_PLACEHOLDER;
    }

    /**
     * 执行延迟加载
     * 与 {@link ResultLoader#loadResult()} 类似
     */
    public void load() {
      @SuppressWarnings( "unchecked" )
      // we suppose we get back a List
      //1.从 localCache 一级缓存获取指定的内嵌的select查询结果对象
      List<Object> list = (List<Object>) localCache.getObject(key);
      //2.将缓存的结果对象转成指定类型
      Object value = resultExtractor.extractObjectFromList(list, targetType);
      //3.将内嵌的结果设置到 resultObject 外层对象的对应属性中
      resultObject.setValue(property, value);
    }

  }

}
