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
package org.apache.ibatis.executor.statement;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * 继承 BaseStatementHandler 抽象类，java.sql.Statement 的 StatementHandler 实现类
 * SQL 语句中不存在占位符
 * 底层依赖于 **java.sql.Statement** 对象来完成数据库的相关操作
 * @author Clinton Begin
 */
public class SimpleStatementHandler extends BaseStatementHandler {

  public SimpleStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    super(executor, mappedStatement, parameter, rowBounds, resultHandler, boundSql);
  }

  /**
   * 执行sql写操作
   * @param statement Statement对象
   * @return 影响条目数
   * @throws SQLException
   */
  @Override
  public int update(Statement statement) throws SQLException {
    //1.获取 sql 语句
    String sql = boundSql.getSql();
    //2.获取用户传入的参数
    Object parameterObject = boundSql.getParameterObject();
    //3.从mappedStatement获取配置的 KeyGenerator 对象
    KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
    int rows;
    //4.如果KeyGenerator是 Jdbc3KeyGenerator 类型
    if (keyGenerator instanceof Jdbc3KeyGenerator) {
      //4.1执行写操作
      statement.execute(sql, Statement.RETURN_GENERATED_KEYS);
      //4.2获取受影响条目数
      rows = statement.getUpdateCount();
      //4.3执行 keyGenerator 的后置处理逻辑，将数据库生成的主键添加到 parameterObject 中
      keyGenerator.processAfter(executor, mappedStatement, statement, parameterObject);
    } else if (keyGenerator instanceof SelectKeyGenerator) {
      //4.如果KeyGenerator是 SelectKeyGenerator 类型
      //4.1执行写操作
      statement.execute(sql);
      //4.2获取受影响条目数
      rows = statement.getUpdateCount();
      //4.3执行 keyGenerator 的后置处理逻辑，将数据库生成的主键添加到 parameterObject 中
      keyGenerator.processAfter(executor, mappedStatement, statement, parameterObject);
    } else {
      //4.如果KeyGenerator是 其它 类型
      //4.1执行写操作
      statement.execute(sql);
      //4.2获取受影响条目数
      rows = statement.getUpdateCount();
    }
    //5.返回受影响条目数
    return rows;
  }

  @Override
  public void batch(Statement statement) throws SQLException {
    //1.获取 sql 语句
    String sql = boundSql.getSql();
    //2.添加到批处理
    statement.addBatch(sql);
  }

  /**
   * 执行读操作
   * @param statement Statement对象
   * @param resultHandler ResultHandler对象，处理结果
   * @param <E>
   * @return
   * @throws SQLException
   */
  @Override
  public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {
    //1.获取 sql 语句
    String sql = boundSql.getSql();
    //2.使用java.sql.Statement执行sql读操作
    statement.execute(sql);
    //3.处理返回结果，映射结果集
    return resultSetHandler.handleResultSets(statement);
  }

  @Override
  public <E> Cursor<E> queryCursor(Statement statement) throws SQLException {
    //1.获取 sql 语句
    String sql = boundSql.getSql();
    //2.使用java.sql.Statement执行sql读操作
    statement.execute(sql);
    //3.处理返回结果，映射结果集
    return resultSetHandler.handleCursorResultSets(statement);
  }

  /**
   * 通过数据库连接创建 java.sql.Statement 对象
   * @param connection 数据库连接对象
   * @return Statement对象
   * @throws SQLException
   */
  @Override
  protected Statement instantiateStatement(Connection connection) throws SQLException {
    if (mappedStatement.getResultSetType() == ResultSetType.DEFAULT) {
      return connection.createStatement();
    } else {
      //设置结果集是否可以滚动及其游标是否可以移动，设置结果集是否可更新
      return connection.createStatement(mappedStatement.getResultSetType().getValue(), ResultSet.CONCUR_READ_ONLY);
    }
  }

  /**
   * 设置参数，本类不支持设置Statement对象参数
   * @param statement Statement对象
   */
  @Override
  public void parameterize(Statement statement) {
    // N/A
  }

}
