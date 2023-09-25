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
package org.apache.ibatis.executor.loader;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.ResultExtractor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.List;

/**
 * 结果加载器
 * @author Clinton Begin
 */
public class ResultLoader {

  /**
   * 配置对象
   */
  protected final Configuration configuration;
  /**
   * 执行器。用于执行延迟加载操作的Executor对象
   */
  protected final Executor executor;
  /**
   * 记录延迟加载的SQL节点对应的 MappedStatement对象
   */
  protected final MappedStatement mappedStatement;
  /**
   * 记录延迟加载执行sql语句的实参
   */
  protected final Object parameterObject;
  /**
   * 记录延迟加载得到的结果对象类型
   */
  protected final Class<?> targetType;
  /**
   * 对象工厂。通过反射创建延迟加载的Java对象
   */
  protected final ObjectFactory objectFactory;
  /**
   * 记录延迟加载执行的sql对应的cacheKey
   */
  protected final CacheKey cacheKey;
  /**
   * 记录延迟加载的SQL语句
   */
  protected final BoundSql boundSql;
  /**
   * 负责将延迟加载得到的结果对象转换为targetType类型的对象
   */
  protected final ResultExtractor resultExtractor;
  /**
   * 创建 ResultLoader 所在的线程id
   */
  protected final long creatorThreadId;

  /**
   * 标志是否加载。默认false
   */
  protected boolean loaded;
  /**
   * 延迟加载得到的结果对象，默认null
   */
  protected Object resultObject;

  /**
   * 构造器。初始化对象属性
   */
  public ResultLoader(Configuration config, Executor executor, MappedStatement mappedStatement, Object parameterObject, Class<?> targetType, CacheKey cacheKey, BoundSql boundSql) {
    this.configuration = config;
    this.executor = executor;
    this.mappedStatement = mappedStatement;
    this.parameterObject = parameterObject;
    this.targetType = targetType;
    this.objectFactory = configuration.getObjectFactory();
    this.cacheKey = cacheKey;
    this.boundSql = boundSql;
    //初始化 resultExtractor
    this.resultExtractor = new ResultExtractor(configuration, objectFactory);
    //初始化 creatorThreadId
    this.creatorThreadId = Thread.currentThread().getId();
  }

  /**
   * 执行延迟加载。底层通过 {@link #selectList()} 实现
   * @return 结果对象
   * @throws SQLException
   */
  public Object loadResult() throws SQLException {
    //1.执行延迟加载，得到结果对象，并以List的形式返回
    List<Object> list = selectList();
    //2.将 List 集合转换成targetType指定类型的对象
    resultObject = resultExtractor.extractObjectFromList(list, targetType);
    //3.返回结果对象
    return resultObject;
  }

  /**
   * 查询结果
   * @param <E>
   * @return 结果列表
   * @throws SQLException
   */
  private <E> List<E> selectList() throws SQLException {
    //1.记录获取 executor 对象
    Executor localExecutor = executor;
    //2.如果当前线程不是创建ResultLoader对象的线程 或 执行器已关闭
    if (Thread.currentThread().getId() != this.creatorThreadId || localExecutor.isClosed()) {
      //2.则重新获取执行器
      localExecutor = newExecutor();
    }
    try {
      //3.执行器执行查询操作，得到延迟加载的对象。
      return localExecutor.<E> query(mappedStatement, parameterObject, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER, cacheKey, boundSql);
    } finally {
      //4.如果是新创建的执行器，则在当前方法关闭执行器
      if (localExecutor != executor) {
        localExecutor.close(false);
      }
    }
  }

  /**
   * 创建 Executor 对象
   * @return Executor对象
   */
  private Executor newExecutor() {
    //1.从配置对象获取Environment对象
    final Environment environment = configuration.getEnvironment();
    //2.如果 Environment对象 为空，则抛出异常
    if (environment == null) {
      throw new ExecutorException("ResultLoader could not load lazily.  Environment was not configured.");
    }
    //3.从环境对象获取 DataSource对象
    final DataSource ds = environment.getDataSource();
    //4.如果 DataSource对象 为空，则抛出异常
    if (ds == null) {
      throw new ExecutorException("ResultLoader could not load lazily.  DataSource was not configured.");
    }
    //5.创建 Transaction 对象
    final TransactionFactory transactionFactory = environment.getTransactionFactory();
    final Transaction tx = transactionFactory.newTransaction(ds, null, false);
    //6.创建 Executor 对象
    return configuration.newExecutor(tx, ExecutorType.SIMPLE);
  }

  /**
   * 判断结果是否为空
   * @return
   */
  public boolean wasNull() {
    return resultObject == null;
  }

}
