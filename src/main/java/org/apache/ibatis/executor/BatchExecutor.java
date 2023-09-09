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

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 继承 BaseExecutor 抽象类，批量执行的 Executor 实现类
 * @author Jeff Butler
 */
public class BatchExecutor extends BaseExecutor {

  public static final int BATCH_UPDATE_RETURN_VALUE = Integer.MIN_VALUE + 1002;

  /**
   * Statement集合（类似多个桶）。缓存多个 Statement 对象，其中每个 Statement 对象中都缓存了多条 SQL 语句
   */
  private final List<Statement> statementList = new ArrayList<>();
  /**
   * 批处理的结果。BatchResult 中通过 updateCounts 字段记录每个 Statement 执行批处理结果
   */
  private final List<BatchResult> batchResultList = new ArrayList<>();
  /**
   * 当前执行的 sql语句
   */
  private String currentSql;
  /**
   * 当前执行的 MappedStatement对象
   */
  private MappedStatement currentStatement;

  public BatchExecutor(Configuration configuration, Transaction transaction) {
    super(configuration, transaction);
  }

  /**
   * 执行更新。添加到批处理
   * 1.如果当前 sql 和 MappedStatement 匹配，则更新参数到集合中的对象，即添加参数到桶中
   * 2.如果当前 sql 和 MappedStatement 不匹配，则创建新的集合对象，即创建新的桶
   * @param ms MappedStatement对象
   * @param parameterObject 参数对象
   * @return
   * @throws SQLException
   */
  @Override
  public int doUpdate(MappedStatement ms, Object parameterObject) throws SQLException {
    //1.获取配置对象
    final Configuration configuration = ms.getConfiguration();
    //2.创建 RoutingStatementHandler 对象
    final StatementHandler handler = configuration.newStatementHandler(this, ms, parameterObject, RowBounds.DEFAULT, null, null);
    //3.获取 BoundSql 对象
    final BoundSql boundSql = handler.getBoundSql();
    //4.获取 sql 语句（带?占位符）
    final String sql = boundSql.getSql();
    final Statement stmt;
    //5.如果 currentSql 和 currentStatement 属性与当前的 sql 和 ms 匹配
    if (sql.equals(currentSql) && ms.equals(currentStatement)) {
      //5.1获取 statementList 集合中最后一个 Statement 对象
      int last = statementList.size() - 1;
      stmt = statementList.get(last);
      //5.2设置事务超时时间
      applyTransactionTimeout(stmt);
      //5.3设置 Statement 参数
      handler.parameterize(stmt);//fix Issues 322
      //5.4获取对应的 BatchResult 对象
      BatchResult batchResult = batchResultList.get(last);
      //5.5给 batchResult对象 添加参数
      batchResult.addParameterObject(parameterObject);
    } else {
      //5.不匹配，创建新的 Statement 对象。
      //5.1获取数据库连接对象
      Connection connection = getConnection(ms.getStatementLog());
      //5.2创建新的 Statement 对象
      stmt = handler.prepare(connection, transaction.getTimeout());
      //5.3设置 Statement 对象参数
      handler.parameterize(stmt);    //fix Issues 322
      //5.4更新 currentSql 和 currentStatement 属性
      currentSql = sql;
      currentStatement = ms;
      //5.5将新的 Statement 对象添加到 statementList 属性集合中
      statementList.add(stmt);
      //5.6创建新的 BatchResult 对象添加到 batchResultList 属性集合中
      batchResultList.add(new BatchResult(ms, sql, parameterObject));
    }
    //6.将 Statement 对象添加到批处理。底层通过调用 Statement.addBatch() 方法添加 sql 语句
    handler.batch(stmt);
    return BATCH_UPDATE_RETURN_VALUE;
  }

  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
      throws SQLException {
    Statement stmt = null;
    try {
      //1.批处理sql语句(先处理缓存的Statement集合statementList)
      flushStatements();
      //2.获取配置对象
      Configuration configuration = ms.getConfiguration();
      //3.创建 RoutingStatementHandler 对象
      StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameterObject, rowBounds, resultHandler, boundSql);
      //4.获取数据库连接
      Connection connection = getConnection(ms.getStatementLog());
      //5.创建 Statement 对象
      stmt = handler.prepare(connection, transaction.getTimeout());
      //6.设置 Statement 对象参数
      handler.parameterize(stmt);
      //7.执行 Statement 对象
      return handler.query(stmt, resultHandler);
    } finally {
      //8.关闭 Statement 对象
      closeStatement(stmt);
    }
  }

  @Override
  protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
    flushStatements();
    Configuration configuration = ms.getConfiguration();
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
    Connection connection = getConnection(ms.getStatementLog());
    Statement stmt = handler.prepare(connection, transaction.getTimeout());
    stmt.closeOnCompletion();
    handler.parameterize(stmt);
    return handler.queryCursor(stmt);
  }

  /**
   * 批量执行sql语句
   * @param isRollback 标记是否为批量回滚。false 表示执行， true 表示不执行
   * @return
   * @throws SQLException
   */
  @Override
  public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
    try {
      //results集合用于存储批处理结果
      List<BatchResult> results = new ArrayList<>();
      //1.如果传入的isRollback为 true，即指定了要回滚事务，则直接返回空集合，忽略 statementList 集合中记录的 SQL 语句
      if (isRollback) {
        return Collections.emptyList();
      }
      //2.如果isRollback为 false标志批量执行，遍历 statementList 和 batchResultList 集合，逐个提交批处理
      for (int i = 0, n = statementList.size(); i < n; i++) {
        //3.获取 Statement对象
        Statement stmt = statementList.get(i);
        //4.设置 Statement对象 超时时间
        applyTransactionTimeout(stmt);
        //5.获取对应的 BatchResult对象
        BatchResult batchResult = batchResultList.get(i);
        try {
          //6.批量执行并设置结果。（核心）
          // 调用 Statement.executeBatch() 批量执行其中的sql语句，并使用返回的int数组更新 batchResult.updateCounts 字段
          batchResult.setUpdateCounts(stmt.executeBatch());
          //从batchResult获取 MappedStatement
          MappedStatement ms = batchResult.getMappedStatement();
          //从batchResult获取 参数集合
          List<Object> parameterObjects = batchResult.getParameterObjects();
          //7.从MappedStatement获取配置的主键生成器
          KeyGenerator keyGenerator = ms.getKeyGenerator();
          if (Jdbc3KeyGenerator.class.equals(keyGenerator.getClass())) {
            Jdbc3KeyGenerator jdbc3KeyGenerator = (Jdbc3KeyGenerator) keyGenerator;
            //8.获取数据库生成的主键，并设置到 parameterObjects 中
            jdbc3KeyGenerator.processBatch(ms, stmt, parameterObjects);
          } else if (!NoKeyGenerator.class.equals(keyGenerator.getClass())) { //issue #141
            //8.对于其他类型的 keyGenerator ，会调用 processAfter() 方法设置主键
            for (Object parameter : parameterObjects) {
              keyGenerator.processAfter(this, ms, stmt, parameter);
            }
          }
          // Close statement to close cursor #1109
          //9.关闭 Statement 对象
          closeStatement(stmt);
        } catch (BatchUpdateException e) {
          //如果反生异常，抛出异常
          StringBuilder message = new StringBuilder();
          message.append(batchResult.getMappedStatement().getId())
              .append(" (batch index #")
              .append(i + 1)
              .append(")")
              .append(" failed.");
          if (i > 0) {
            message.append(" ")
                .append(i)
                .append(" prior sub executor(s) completed successfully, but will be rolled back.");
          }
          throw new BatchExecutorException(message.toString(), e, results, batchResult);
        }
        //10.添加到结果集
        results.add(batchResult);
      }
      return results;
    } finally {
      //11.关闭 statementList 中的 Statement 对象
      for (Statement stmt : statementList) {
        closeStatement(stmt);
      }
      //12.置空属性
      currentSql = null;
      statementList.clear();
      batchResultList.clear();
    }
  }

}
