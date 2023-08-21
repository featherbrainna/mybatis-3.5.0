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
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * PreparedStatement代理由连接代理生成，代理打印JDBC调试日志
 * PreparedStatement proxy to add logging
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 *
 */
public final class PreparedStatementLogger extends BaseJdbcLogger implements InvocationHandler {

  /**
   * 底层PreparedStatement对象
   */
  private final PreparedStatement statement;

  /**
   * 私有化构造器，静态工具类
   * @param stmt
   * @param statementLog
   * @param queryStack
   */
  private PreparedStatementLogger(PreparedStatement stmt, Log statementLog, int queryStack) {
    super(statementLog, queryStack);
    this.statement = stmt;
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
      //2.如果调用的是 EXECUTE_METHODS 集合中的方法
      if (EXECUTE_METHODS.contains(method.getName())) {
        //2.1如果可以debug日志，则打印=> Parameters: 参数值参数类型列表字符串（代理增强）
        if (isDebugEnabled()) {
          debug("Parameters: " + getParameterValueString(), true);
        }
        //2.2清空Column数据（代理增强）
        clearColumnInfo();
        //2.3如果是 executeQuery 方法
        if ("executeQuery".equals(method.getName())) {
          //2.3.1调用底层 statement 获取 ResultSet 对象
          ResultSet rs = (ResultSet) method.invoke(statement, params);
          //2.3.2创建 ResultSet 的代理对象（代理增强）
          return rs == null ? null : ResultSetLogger.newInstance(rs, statementLog, queryStack);
        } else {
          //2.4如果是 其他EXECUTE_METHODS集合中的方法，直接反射调用
          return method.invoke(statement, params);
        }
      } else if (SET_METHODS.contains(method.getName())) {
        //3.如果调用的是 SET_METHODS 集合中的方法
        //调用BaseJdbcLogger中的setColumn()方法，设置BaseJdbcLogger中定义的三个column*字段
        if ("setNull".equals(method.getName())) {
          setColumn(params[0], null);
        } else {
          setColumn(params[0], params[1]);
        }
        return method.invoke(statement, params);
      } else if ("getResultSet".equals(method.getName())) {
        //4.如果调用的是 getResultSet 方法，则为 ResultSet 创建代理对象
        ResultSet rs = (ResultSet) method.invoke(statement, params);
        return rs == null ? null : ResultSetLogger.newInstance(rs, statementLog, queryStack);
      } else if ("getUpdateCount".equals(method.getName())) {
        //5.如果调用的是 getUpdateCount 方法，则通过日志输出更新条数
        int updateCount = (Integer) method.invoke(statement, params);
        if (updateCount != -1) {
          debug("   Updates: " + updateCount, false);
        }
        return updateCount;
      } else {
        //6.其他方法，则直接反射调用
        return method.invoke(statement, params);
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
  }

  /**
   * 创建代理对象，由{@link ConnectionLogger}调用
   * Creates a logging version of a PreparedStatement
   *
   * @param stmt - the statement
   * @param statementLog - the statement log
   * @param queryStack - the query stack
   * @return - the proxy
   */
  public static PreparedStatement newInstance(PreparedStatement stmt, Log statementLog, int queryStack) {
    InvocationHandler handler = new PreparedStatementLogger(stmt, statementLog, queryStack);
    ClassLoader cl = PreparedStatement.class.getClassLoader();
    return (PreparedStatement) Proxy.newProxyInstance(cl, new Class[]{PreparedStatement.class, CallableStatement.class}, handler);
  }

  /**
   * Return the wrapped prepared statement
   *
   * @return the PreparedStatement
   */
  public PreparedStatement getPreparedStatement() {
    return statement;
  }

}
