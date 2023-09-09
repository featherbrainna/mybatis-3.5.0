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
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.*;
import java.util.List;

/**
 * 继承 BaseStatementHandler 抽象类，java.sql.PreparedStatement 的 StatementHandler 实现类
 * 底层依赖于 **java.sql.PreparedStatement** 对象来完成数据库的相关操作
 * @author Clinton Begin
 */
public class PreparedStatementHandler extends BaseStatementHandler {

  public PreparedStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    super(executor, mappedStatement, parameter, rowBounds, resultHandler, boundSql);
  }

  @Override
  public int update(Statement statement) throws SQLException {
    //1.强制转型PreparedStatement
    PreparedStatement ps = (PreparedStatement) statement;
    //2.执行写操作
    ps.execute();
    //3.获取更新条目数
    int rows = ps.getUpdateCount();
    //4.获取用户传入参数
    Object parameterObject = boundSql.getParameterObject();
    //5.获取 KeyGenerator 对象
    KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
    //6.执行 keyGenerator 的后置处理逻辑，将数据库生成的主键添加到 parameterObject 中
    keyGenerator.processAfter(executor, mappedStatement, ps, parameterObject);
    //7.返回影响条目数
    return rows;
  }

  @Override
  public void batch(Statement statement) throws SQLException {
    //1.强制转型PreparedStatement
    PreparedStatement ps = (PreparedStatement) statement;
    //2.添加到批处理
    ps.addBatch();
  }

  @Override
  public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {
    //1.强制转型PreparedStatement
    PreparedStatement ps = (PreparedStatement) statement;
    //2.执行读操作
    ps.execute();
    //3.结果集处理
    return resultSetHandler.handleResultSets(ps);
  }

  @Override
  public <E> Cursor<E> queryCursor(Statement statement) throws SQLException {
    //1.强制转型PreparedStatement
    PreparedStatement ps = (PreparedStatement) statement;
    //2.执行读操作
    ps.execute();
    //3.结果集处理
    return resultSetHandler.handleCursorResultSets(ps);
  }

  /**
   * 创建 java.sql.PreparedStatement 对象
   * @param connection 数据库连接对象
   * @return PreparedStatement对象
   * @throws SQLException
   */
  @Override
  protected Statement instantiateStatement(Connection connection) throws SQLException {
    //1.获取sql语句
    String sql = boundSql.getSql();
    //2.如果mappedStatement配置的 KeyGenerator 类型为 Jdbc3KeyGenerator
    if (mappedStatement.getKeyGenerator() instanceof Jdbc3KeyGenerator) {
      //2.1从mappedStatement获取主键的 表头字段数组
      String[] keyColumnNames = mappedStatement.getKeyColumns();
      //2.2如果keyColumnNames为空，即没有设置 keyColumn 属性，则调用 prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS)自动确定主键字段
      //同时创建 PreparedStatement 对象（支持主键生成返回）
      if (keyColumnNames == null) {
        return connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);
      } else {
        //2.2如果keyColumnNames不为空，则传入 keyColumnNames 调用，同时创建 PreparedStatement 对象（支持主键生成返回）
        return connection.prepareStatement(sql, keyColumnNames);
      }
    } else if (mappedStatement.getResultSetType() == ResultSetType.DEFAULT) {
      //2.如果ResultSetType类型为defult，创建普通的 PreparedStatement 对象
      return connection.prepareStatement(sql);
    } else {
      //2.如果ResultSetType类型为其它，设置结果集是否可以滚动及其游标是否可以移动，设置结果集是否可更新
      return connection.prepareStatement(sql, mappedStatement.getResultSetType().getValue(), ResultSet.CONCUR_READ_ONLY);
    }
  }

  @Override
  public void parameterize(Statement statement) throws SQLException {
    //使用 parameterHandler 关键组件设置参数
    parameterHandler.setParameters((PreparedStatement) statement);
  }

}
