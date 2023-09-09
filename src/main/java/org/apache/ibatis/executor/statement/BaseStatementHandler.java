/**
 *    Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.executor.statement;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 实现 StatementHandler 接口，StatementHandler 接口的抽象类
 * 提供骨架方法，从而使子类只要实现指定的几个抽象方法即可
 * @author Clinton Begin
 */
public abstract class BaseStatementHandler implements StatementHandler {

  /**
   * 配置对象
   */
  protected final Configuration configuration;
  protected final ObjectFactory objectFactory;
  protected final TypeHandlerRegistry typeHandlerRegistry;
  /**
   * 结果集处理器。将结果集映射成结果对象
   */
  protected final ResultSetHandler resultSetHandler;
  /**
   * 参数处理器。为SQL语句绑定实参，即给statement设置参数
   */
  protected final ParameterHandler parameterHandler;

  /**
   * sql语句的执行器对象
   */
  protected final Executor executor;
  /**
   * sql语句对应的 MappedStatement 对象
   */
  protected final MappedStatement mappedStatement;
  /**
   * sql语句的行限制
   */
  protected final RowBounds rowBounds;

  /**
   * sql语句对应的 BoundSql 对象
   */
  protected BoundSql boundSql;

  /**
   * 构造器
   */
  protected BaseStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    //1.获取 Configuration 对象初始化 configuration 属性
    this.configuration = mappedStatement.getConfiguration();
    //2.初始化 executor、mappedStatement、rowBounds 属性
    this.executor = executor;
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;

    //3.从configuration获取初始化 typeHandlerRegistry、objectFactory 属性
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();

    //4.如果 boundSql 为空，则表示为写操作。先获取自增主键后创建boundSql对象
    if (boundSql == null) { // issue #435, get the key before calculating the statement
      //4.1.获取自增主键
      generateKeys(parameterObject);
      //4.2.从mappedStatement创建 boundSql 对象
      boundSql = mappedStatement.getBoundSql(parameterObject);
    }

    this.boundSql = boundSql;

    //5.创建 parameterHandler 对象并初始化属性
    this.parameterHandler = configuration.newParameterHandler(mappedStatement, parameterObject, boundSql);
    //6.创建 resultSetHandler 对象并初始化属性
    this.resultSetHandler = configuration.newResultSetHandler(executor, mappedStatement, rowBounds, parameterHandler, resultHandler, boundSql);
  }

  @Override
  public BoundSql getBoundSql() {
    return boundSql;
  }

  @Override
  public ParameterHandler getParameterHandler() {
    return parameterHandler;
  }

  /**
   * 从数据库连接创建Statement对象（模板方法模式）
   * @param connection 数据库连接对象
   * @param transactionTimeout 事务超时时间
   * @return Statement对象
   * @throws SQLException
   */
  @Override
  public Statement prepare(Connection connection, Integer transactionTimeout) throws SQLException {
    ErrorContext.instance().sql(boundSql.getSql());
    Statement statement = null;
    try {
      //1.底层子类创建 statement 对象
      statement = instantiateStatement(connection);
      //2.设置 statement 对象超时时间
      setStatementTimeout(statement, transactionTimeout);
      //3.设置 statement 对象fetchSize
      setFetchSize(statement);
      //4.返回
      return statement;
    } catch (SQLException e) {
      //4.发送异常关闭 statement 对象
      closeStatement(statement);
      throw e;
    } catch (Exception e) {
      //4.发送异常关闭 statement 对象
      closeStatement(statement);
      throw new ExecutorException("Error preparing statement.  Cause: " + e, e);
    }
  }

  /**
   * 创建 java.sql.Statement 对象
   * @param connection 数据库连接对象
   * @return Statement 对象
   * @throws SQLException
   */
  protected abstract Statement instantiateStatement(Connection connection) throws SQLException;

  /**
   * 设置 statement 对象超时时间（模板方法中固定不变方法）
   * @param stmt
   * @param transactionTimeout
   * @throws SQLException
   */
  protected void setStatementTimeout(Statement stmt, Integer transactionTimeout) throws SQLException {
    //1.先从SQL节点获取 timeout 属性，再从configuration获取 defaultStatementTimeout 配置初始化sql执行超时时间
    // 优先SQL节点配置，后configuration配置，且只能生效一个
    Integer queryTimeout = null;
    if (mappedStatement.getTimeout() != null) {
      queryTimeout = mappedStatement.getTimeout();
    } else if (configuration.getDefaultStatementTimeout() != null) {
      queryTimeout = configuration.getDefaultStatementTimeout();
    }
    //2.如果sql执行超时不为空，则设置 Statement 执行超时时间
    if (queryTimeout != null) {
      stmt.setQueryTimeout(queryTimeout);
    }
    //3.设置事务超时时间，依据事务超时时间调整Statement执行超时时间
    StatementUtil.applyTransactionTimeout(stmt, queryTimeout, transactionTimeout);
  }

  /**
   * 设置 statement 对象fetchSize（模板方法中固定不变方法）
   * @param stmt
   * @throws SQLException
   */
  protected void setFetchSize(Statement stmt) throws SQLException {
    //1.从SQL节点获取 fetchSize 属性，当不为空时设置statement
    Integer fetchSize = mappedStatement.getFetchSize();
    if (fetchSize != null) {
      stmt.setFetchSize(fetchSize);
      return;
    }
    //2.从configuration获取 defaultFetchSize 配置，当不为空时覆盖设置statement
    Integer defaultFetchSize = configuration.getDefaultFetchSize();
    if (defaultFetchSize != null) {
      stmt.setFetchSize(defaultFetchSize);
    }
  }

  protected void closeStatement(Statement statement) {
    try {
      if (statement != null) {
        statement.close();
      }
    } catch (SQLException e) {
      //ignore
    }
  }

  /**
   * 获得自增主键
   * 通过 KeyGenerator 对象，创建自增编号到 parameter 中
   * @param parameter 用户传入参数对象
   */
  protected void generateKeys(Object parameter) {
    //1.从 mappedStatement 获取主键生成器 KeyGenerator 对象
    KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
    ErrorContext.instance().store();
    //2.前置处理，生成自增编号到 parameter 中
    // Jdbc3KeyGenerator、NoKeyGenerator 中此方法什么都没做
    // SelectKeyGenerator 根据 selectKey 节点的 order 属性来决定是否先生成后生成
    keyGenerator.processBefore(executor, mappedStatement, null, parameter);
    ErrorContext.instance().recall();
  }

}
