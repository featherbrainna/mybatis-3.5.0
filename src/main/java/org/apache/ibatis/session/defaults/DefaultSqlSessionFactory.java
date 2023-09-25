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
package org.apache.ibatis.session.defaults;

import org.apache.ibatis.exceptions.ExceptionFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.transaction.managed.ManagedTransactionFactory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * 实现 SqlSessionFactory 接口，默认的 SqlSessionFactory 实现类
 * @author Clinton Begin
 */
public class DefaultSqlSessionFactory implements SqlSessionFactory {

  /**
   * 配置对象。构造器初始化
   */
  private final Configuration configuration;

  /**
   * 构造器
   * @param configuration 配置对象
   */
  public DefaultSqlSessionFactory(Configuration configuration) {
    this.configuration = configuration;
  }

  @Override
  public SqlSession openSession() {
    return openSessionFromDataSource(configuration.getDefaultExecutorType(), null, false);
  }

  @Override
  public SqlSession openSession(boolean autoCommit) {
    return openSessionFromDataSource(configuration.getDefaultExecutorType(), null, autoCommit);
  }

  @Override
  public SqlSession openSession(ExecutorType execType) {
    return openSessionFromDataSource(execType, null, false);
  }

  @Override
  public SqlSession openSession(TransactionIsolationLevel level) {
    return openSessionFromDataSource(configuration.getDefaultExecutorType(), level, false);
  }

  @Override
  public SqlSession openSession(ExecutorType execType, TransactionIsolationLevel level) {
    return openSessionFromDataSource(execType, level, false);
  }

  @Override
  public SqlSession openSession(ExecutorType execType, boolean autoCommit) {
    return openSessionFromDataSource(execType, null, autoCommit);
  }

  @Override
  public SqlSession openSession(Connection connection) {
    return openSessionFromConnection(configuration.getDefaultExecutorType(), connection);
  }

  @Override
  public SqlSession openSession(ExecutorType execType, Connection connection) {
    return openSessionFromConnection(execType, connection);
  }

  @Override
  public Configuration getConfiguration() {
    return configuration;
  }

  /**
   * 从数据源（配置文件）获取 SqlSession 对象
   * @param execType 执行器类型枚举
   * @param level 事务隔离级别枚举
   * @param autoCommit 是否自动提交
   * @return DefaultSqlSession对象
   */
  private SqlSession openSessionFromDataSource(ExecutorType execType, TransactionIsolationLevel level, boolean autoCommit) {
    Transaction tx = null;
    try {
      //1.从configuration获取 Environment 对象
      final Environment environment = configuration.getEnvironment();
      //2.创建 TransactionFactory 事务工厂对象
      final TransactionFactory transactionFactory = getTransactionFactoryFromEnvironment(environment);
      //3.通过事务工厂创建 Transaction 事务对象
      tx = transactionFactory.newTransaction(environment.getDataSource(), level, autoCommit);
      //4.从configuration调用newExecutor方法创建 Executor 执行器对象
      final Executor executor = configuration.newExecutor(tx, execType);
      //5.返回创建的 DefaultSqlSession 对象
      return new DefaultSqlSession(configuration, executor, autoCommit);
    } catch (Exception e) {
      //如果发生异常，则关闭事务对象
      closeTransaction(tx); // may have fetched a connection so lets call close()
      throw ExceptionFactory.wrapException("Error opening session.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  /**
   * 从数据库连接对象获取 SqlSession 对象
   * @param execType 执行器类型枚举
   * @param connection 数据库连接对象
   * @return DefaultSqlSession对象
   */
  private SqlSession openSessionFromConnection(ExecutorType execType, Connection connection) {
    try {
      boolean autoCommit;
      try {
        //1.从数据库连接获取是否自动提交
        autoCommit = connection.getAutoCommit();
      } catch (SQLException e) {
        // Failover to true, as most poor drivers
        // or databases won't support transactions
        autoCommit = true;
      }
      //2.从配置对象获取 Environment 对象
      final Environment environment = configuration.getEnvironment();
      //3.创建 TransactionFactory 事务工厂对象
      final TransactionFactory transactionFactory = getTransactionFactoryFromEnvironment(environment);
      //4.传入数据库连接对象，通过事务工厂创建事务对象
      final Transaction tx = transactionFactory.newTransaction(connection);
      //5.从配置对象创建 执行器对象
      final Executor executor = configuration.newExecutor(tx, execType);
      //6.返回创建的 DefaultSqlSession对象
      return new DefaultSqlSession(configuration, executor, autoCommit);
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error opening session.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  /**
   * 从环境对象获取 TransactionFactory 事务工厂对象
   * @param environment 环境对象
   * @return 事务工厂对象
   */
  private TransactionFactory getTransactionFactoryFromEnvironment(Environment environment) {
    //1.如果环境对象为空，或者环境对象中获取不到事务工厂对象，则返回 ManagedTransactionFactory 对象
    if (environment == null || environment.getTransactionFactory() == null) {
      return new ManagedTransactionFactory();
    }
    //2.从环境对象中获取 事务工厂对象
    return environment.getTransactionFactory();
  }

  /**
   * 关闭事务对象
   * @param tx 事务对象
   */
  private void closeTransaction(Transaction tx) {
    if (tx != null) {
      try {
        tx.close();
      } catch (SQLException ignore) {
        // Intentionally ignore. Prefer previous error.
      }
    }
  }

}
