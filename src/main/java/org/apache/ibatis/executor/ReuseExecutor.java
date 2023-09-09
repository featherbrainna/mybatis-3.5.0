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
package org.apache.ibatis.executor;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 继承 BaseExecutor 抽象类，可重用的 Executor 实现类
 *
 * 1.每次开始读或写操作，优先从缓存中获取对应的 Statement 对象。如果不存在，才进行创建。
 * 2.执行完成后，不关闭该 Statement 对象。
 * 3.其它的，和 SimpleExecutor 是一致的。
 * @author Clinton Begin
 */
public class ReuseExecutor extends BaseExecutor {

  /**
   * Statement 的缓存
   * key：SQL语句
   * value：SQL语句对应的 Statement 对象
   */
  private final Map<String, Statement> statementMap = new HashMap<>();

  public ReuseExecutor(Configuration configuration, Transaction transaction) {
    super(configuration, transaction);
  }

  @Override
  public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
    Configuration configuration = ms.getConfiguration();
    StatementHandler handler = configuration.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null, null);
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    return handler.update(stmt);
  }

  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
    //1.获取配置对象
    Configuration configuration = ms.getConfiguration();
    //2.创建 RoutingStatementHandler 对象
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);
    //3.依赖 RoutingStatementHandler 创建 Statement 对象
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    //4.执行 Statement
    return handler.query(stmt, resultHandler);
  }

  @Override
  protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
    Configuration configuration = ms.getConfiguration();
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    return handler.queryCursor(stmt);
  }

  /**
   * 批量执行 sql 语句。由 commit\rollback\close 方法调用
   * @param isRollback 标记是否为批量回滚。false 表示执行， true 表示不执行
   * @return
   */
  @Override
  public List<BatchResult> doFlushStatements(boolean isRollback) {
    //1.遍历 statementMap 缓存属性集合，关闭缓存的 Statement 对象
    for (Statement stmt : statementMap.values()) {
      closeStatement(stmt);
    }
    //2.清空 statementMap 缓存属性集合
    statementMap.clear();
    //3.返回空集合
    return Collections.emptyList();
  }

  /**
   * 依赖 RoutingStatementHandler 对象创建 Statement 对象
   * @param handler RoutingStatementHandler对象
   * @param statementLog 日志对象
   * @return Statement对象
   * @throws SQLException
   */
  private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
    Statement stmt;
    //1.从 StatementHandler 获取 BoundSql 对象
    BoundSql boundSql = handler.getBoundSql();
    //2.获取 sql 语句
    String sql = boundSql.getSql();
    //3.根据 sql语句 查找是否缓存了相同模式的SQL语句对应的 Statement对象
    //3.存在可用缓存
    if (hasStatementFor(sql)) {
      //3.1从 statementMap 属性集合中获取sql对应的 Statement对象
      stmt = getStatement(sql);
      //3.2设置事务超时时间
      applyTransactionTimeout(stmt);
    } else {
      //3.未找到缓存
      //3.1获取数据库连接
      Connection connection = getConnection(statementLog);
      //3.2创建 Statement 对象
      stmt = handler.prepare(connection, transaction.getTimeout());
      //3.3缓存 Statement 对象
      putStatement(sql, stmt);
    }
    //4.设置 Statement对象 占位符
    handler.parameterize(stmt);
    return stmt;
  }

  /**
   * 根据 sql语句 判断是否存在对应的 Statement 对象，并且要求连接未关闭
   * @param sql sql语句
   * @return
   */
  private boolean hasStatementFor(String sql) {
    try {
      //查找是否缓存（存在性判断）
      //查找是否关闭连接（可用性判断）
      return statementMap.keySet().contains(sql) && !statementMap.get(sql).getConnection().isClosed();
    } catch (SQLException e) {
      return false;
    }
  }

  /**
   * 从 statementMap 属性集合中获取sql对应的 Statement对象
   * @param s sql语句
   * @return Statement对象
   */
  private Statement getStatement(String s) {
    return statementMap.get(s);
  }

  /**
   * 缓存 Statement对象 到 statementMap 属性集合中
   * @param sql sql语句
   * @param stmt Statement对象
   */
  private void putStatement(String sql, Statement stmt) {
    statementMap.put(sql, stmt);
  }

}
