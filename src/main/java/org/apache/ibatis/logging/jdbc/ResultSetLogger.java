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
package org.apache.ibatis.logging.jdbc;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ExceptionUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashSet;
import java.util.Set;

/**
 * ResultSet代理由Statement或PreparedStatement代理生成，代理打印JDBC调试日志
 * ResultSet proxy to add logging
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 *
 */
public final class ResultSetLogger extends BaseJdbcLogger implements InvocationHandler {

  /**
   * 记录了超大长度的sql jdbc类型
   */
  private static Set<Integer> BLOB_TYPES = new HashSet<>();
  /**
   * 是否是 ResultSet 结果集的第一行
   */
  private boolean first = true;
  /**
   * 统计行数
   */
  private int rows;
  /**
   * 底层ResultSet对象
   */
  private final ResultSet rs;
  /**
   * 记录了超大字段的列编号
   */
  private final Set<Integer> blobColumns = new HashSet<>();

  /**
   * 静态代码块，初始化集合 BLOB_TYPES
   */
  static {
    //添加类型
    BLOB_TYPES.add(Types.BINARY);
    BLOB_TYPES.add(Types.BLOB);
    BLOB_TYPES.add(Types.CLOB);
    BLOB_TYPES.add(Types.LONGNVARCHAR);
    BLOB_TYPES.add(Types.LONGVARBINARY);
    BLOB_TYPES.add(Types.LONGVARCHAR);
    BLOB_TYPES.add(Types.NCLOB);
    BLOB_TYPES.add(Types.VARBINARY);
  }

  /**
   * 私有化构造器
   * @param rs
   * @param statementLog
   * @param queryStack
   */
  private ResultSetLogger(ResultSet rs, Log statementLog, int queryStack) {
    super(statementLog, queryStack);
    this.rs = rs;
  }

  /**
   * InvocationHandler 接口实现，代理扩展的任务方法
   * @param proxy
   * @param method
   * @param params
   * @return
   * @throws Throwable
   */
  @Override
  public Object invoke(Object proxy, Method method, Object[] params) throws Throwable {
    try {
      //1.如果调用的是从 Object 继承的方法，则直接调用，不做任何其他处理
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, params);
      }
      //2.直接执行底层 rs 的方法
      Object o = method.invoke(rs, params);
      //3.如果调用的是 next 方法（代理增强）
      if ("next".equals(method.getName())) {
        //强制转型结果，是否还存在下一行数据
        if ((Boolean) o) {
          //3.1存在下一行
          rows++;
          //3.2是否可以trace日志记录
          if (isTraceEnabled()) {
            //3.2.1从 rs 获取元数据
            ResultSetMetaData rsmd = rs.getMetaData();
            //3.2.2获取数据集的列数
            final int columnCount = rsmd.getColumnCount();
            //3.2.3如果是第一行数据，则输出表头
            if (first) {
              first = false;
              //除了输出表头，还会填充 blobColumns 集合，记录超大型的列
              printColumnHeaders(rsmd, columnCount);
            }
            //3.2.4输出该行的记录，注意会过滤掉blob中列的记录，这些列的数据较大，不会输出到日志
            printColumnValues(columnCount);
          }
        } else {
          //3.1不存在下一行，遍历完 ResultSet 之后，会输出总函数
          debug("     Total: " + rows, false);
        }
      }
      //4.清空 BaseJdbcLogger 中的column*集合
      clearColumnInfo();
      //5.返回结果
      return o;
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
  }

  /**
   * 日志打印结果集表头
   * 表头row格式：   Columns: colname, colname
   * @param rsmd 结果集元数据
   * @param columnCount 列数
   * @throws SQLException sql异常
   */
  private void printColumnHeaders(ResultSetMetaData rsmd, int columnCount) throws SQLException {
    //1.行字符串构建器
    StringBuilder row = new StringBuilder();
    row.append("   Columns: ");
    //2.遍历表头的每列
    for (int i = 1; i <= columnCount; i++) {
      //3.如果 BLOB_TYPES 包含此列的类型（从结果集元数据获取），将列编号添加到 blobColumns 集合
      if (BLOB_TYPES.contains(rsmd.getColumnType(i))) {
        blobColumns.add(i);
      }
      //4.从结果集元数据获取该列的标签
      String colname = rsmd.getColumnLabel(i);
      //5.添加列标签名
      row.append(colname);
      //6.添加标签之间的分隔符,
      if (i != columnCount) {
        row.append(", ");
      }
    }
    //7.trace日志打印 <= row
    trace(row.toString(), false);
  }

  /**
   * 日志打印结果集行数据
   * 行数据格式：       Row: colval, colval
   * @param columnCount 列数
   */
  private void printColumnValues(int columnCount) {
    //1.行字符串构建器
    StringBuilder row = new StringBuilder();
    row.append("       Row: ");
    //2.遍历一行数据的每列
    for (int i = 1; i <= columnCount; i++) {
      String colname;
      try {
        //3.1在 blobColumns 集合中的列编号，数据为<<BLOB>>
        if (blobColumns.contains(i)) {
          colname = "<<BLOB>>";
        } else {
          //3.1不在 blobColumns 集合中的列编号，获取真实结果集数据
          colname = rs.getString(i);
        }
      } catch (SQLException e) {
        // generally can't call getString() on a BLOB column
        colname = "<<Cannot Display>>";
      }
      //4.添加数据到行字符串
      row.append(colname);
      //5.添加数据分隔符
      if (i != columnCount) {
        row.append(", ");
      }
    }
    //6.trace日志打印 <= row
    trace(row.toString(), false);
  }

  /**
   * 创建代理对象
   * Creates a logging version of a ResultSet
   *
   * @param rs - the ResultSet to proxy
   * @return - the ResultSet with logging
   */
  public static ResultSet newInstance(ResultSet rs, Log statementLog, int queryStack) {
    InvocationHandler handler = new ResultSetLogger(rs, statementLog, queryStack);
    ClassLoader cl = ResultSet.class.getClassLoader();
    return (ResultSet) Proxy.newProxyInstance(cl, new Class[]{ResultSet.class}, handler);
  }

  /**
   * Get the wrapped result set
   *
   * @return the resultSet
   */
  public ResultSet getRs() {
    return rs;
  }

}
