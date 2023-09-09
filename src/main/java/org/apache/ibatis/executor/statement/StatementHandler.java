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

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.ResultHandler;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Statement 处理器接口
 * 1.prepare方法，从连接创建 Statement 对象
 * 2.parameterize方法，设置 Statement 对象参数
 * 3.query、update方法，执行 Statement 对象
 * @author Clinton Begin
 */
public interface StatementHandler {

  /**
   * 从连接中获取创建一个 Statement 对象
   * @param connection 数据库连接对象
   * @param transactionTimeout 事务超时时间
   * @return Statement对象
   * @throws SQLException
   */
  Statement prepare(Connection connection, Integer transactionTimeout)
      throws SQLException;

  /**
   * 设置绑定 Statement 对象执行所需的参数
   * @param statement Statement对象
   * @throws SQLException
   */
  void parameterize(Statement statement)
      throws SQLException;

  /**
   * 添加 Statement 对象的批量操作
   * @param statement Statement对象
   * @throws SQLException
   */
  void batch(Statement statement)
      throws SQLException;

  /**
   * 执行写操作
   * @param statement Statement对象
   * @return 影响的条目数
   * @throws SQLException
   */
  int update(Statement statement)
      throws SQLException;

  /**
   * 执行读操作
   * @param statement Statement对象
   * @param resultHandler ResultHandler对象，处理结果
   * @param <E> 结果类型
   * @return 读取的结果集合
   * @throws SQLException
   */
  <E> List<E> query(Statement statement, ResultHandler resultHandler)
      throws SQLException;

  /**
   * 执行读操作，返回 Cursor 游标对象
   * @param statement Statement对象
   * @param <E> 结果类型
   * @return 游标对象
   * @throws SQLException
   */
  <E> Cursor<E> queryCursor(Statement statement)
      throws SQLException;

  /**
   * 获取已绑定参数的 BoundSql 对象
   * @return BoundSql对象
   */
  BoundSql getBoundSql();

  /**
   * 获取其中封装的 ParameterHandler 对象
   * @return ParameterHandler对象
   */
  ParameterHandler getParameterHandler();

}
