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
import java.util.List;

/**
 * 继承 BaseExecutor 抽象类，简单的 Executor 实现类
 *
 * 1.每次开始读或写操作，都创建对应的 Statement 对象。
 * 2.执行完成后，关闭该 Statement 对象。
 * @author Clinton Begin
 */
public class SimpleExecutor extends BaseExecutor {

  public SimpleExecutor(Configuration configuration, Transaction transaction) {
    super(configuration, transaction);
  }

  /**
   * 执行 update\insert\delete 语句（核心执行sql修改语句）
   * @param ms MappedStatement对象
   * @param parameter 用户传入参数
   * @return 影响条目数
   * @throws SQLException
   */
  @Override
  public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
    Statement stmt = null;
    try {
      //1.获取配置对象
      Configuration configuration = ms.getConfiguration();
      //2.创建 RoutingStatementHandler 对象
      StatementHandler handler = configuration.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null, null);
      //3.依赖 RoutingStatementHandler对象 创建和初始化 Statement 对象
      stmt = prepareStatement(handler, ms.getStatementLog());
      //4.执行 Statement
      return handler.update(stmt);
    } finally {
      //5.关闭 Statement
      closeStatement(stmt);
    }
  }

  /**
   * 执行查询（核心执行sql查询语句）
   * 1.获取数据库连接。{@link #prepareStatement(StatementHandler, Log)} 调用 {@link #getConnection(Log)}
   * 2.创建 Statement 对象。{@link #prepareStatement(StatementHandler, Log)} 中 {@link StatementHandler#prepare(Connection, Integer)} 创建
   * 3.设置 Statement 占位符参数。{@link #prepareStatement(StatementHandler, Log)}
   * 4.执行 Statement
   * 2.关闭 Statement
   */
  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
    Statement stmt = null;
    try {
      //1.获取配置对象
      Configuration configuration = ms.getConfiguration();
      //2.创建 StatementHandler 对象。实际返回的是 RoutingStatementHandler 对象
      //RoutingStatementHandler对象中构造器根据 MappedStatement.statementType 选择具体的 StatementHandler 代理对象初始化属性delegate
      StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);
      //3.依赖 RoutingStatementHandler对象 创建和初始化 Statement 对象。
      // 先调用 handler.prepare() 创建Statement对象(具体类型与statementType相关)；
      // 后调用 handler.parameterize() 方法处理占位符
      stmt = prepareStatement(handler, ms.getStatementLog());
      //4.执行 sql 语句，并通过 resultHandler 完成结果集的映射
      return handler.query(stmt, resultHandler);
    } finally {
      //5.关闭 Statement 对象
      closeStatement(stmt);
    }
  }

  /**
   * 执行查询，返回游标对象
   */
  @Override
  protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
    //1.获取配置对象
    Configuration configuration = ms.getConfiguration();
    //2.创建 RoutingStatementHandler 对象
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
    //3.依赖 RoutingStatementHandler对象 创建和初始化 Statement 对象
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    //4.设置 Statement 执行完成则自动关闭
    stmt.closeOnCompletion();
    //5.执行 Statement
    return handler.queryCursor(stmt);
  }

  /**
   * 不提供批量操作sql，直接返回空数组
   * @param isRollback 标记是否为批量回滚。false 表示执行， true 表示不执行
   * @return
   */
  @Override
  public List<BatchResult> doFlushStatements(boolean isRollback) {
    return Collections.emptyList();
  }

  /**
   * 通过 StatementHandler 对象获取 Statement 对象
   * @param handler StatementHandler对象
   * @param statementLog 日志对象
   * @return Statement对象
   * @throws SQLException
   */
  private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
    Statement stmt;
    //1.获取数据库连接
    Connection connection = getConnection(statementLog);
    //2.通过数据库连接获取 Statement 对象
    stmt = handler.prepare(connection, transaction.getTimeout());
    //3.设置 Statement 语句占位符参数
    handler.parameterize(stmt);
    return stmt;
  }

}
