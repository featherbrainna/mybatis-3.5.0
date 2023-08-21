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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 连接代理，代理打印JDBC调试日志
 * Connection proxy to add logging
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 *
 */
public final class ConnectionLogger extends BaseJdbcLogger implements InvocationHandler {

  /**
   * 被代理对象 Connection
   */
  private final Connection connection;

  /**
   * 私有化构造器，静态工具类
   * @param conn 被代理连接对象
   * @param statementLog 日志对象
   * @param queryStack
   */
  private ConnectionLogger(Connection conn, Log statementLog, int queryStack) {
    super(statementLog, queryStack);
    this.connection = conn;
  }

  /**
   * InvocationHandler 接口实现，代理扩展的任务方法
   * @param proxy 代理对象
   * @param method 反射底层方法对象
   * @param params 方法参数
   * @return 方法返回值
   * @throws Throwable 异常
   */
  @Override
  public Object invoke(Object proxy, Method method, Object[] params)
      throws Throwable {
    try {
      //1.如果调用的是从 Object 继承的方法，则直接调用，不做任何其他处理
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, params);
      }
      //2.如果调用的是 prepareStatement 方法
      if ("prepareStatement".equals(method.getName())) {
        //2.1如果可以debug日志，则打印=>  Preparing: 去掉多余空格的SQL（代理增强）
        if (isDebugEnabled()) {
          debug(" Preparing: " + removeBreakingWhitespace((String) params[0]), true);
        }
        //2.2通过反射执行底层创建 PreparedStatement 对象
        PreparedStatement stmt = (PreparedStatement) method.invoke(connection, params);
        //2.3为该 PreparedStatement 对象创建代理对象（代理增强）
        stmt = PreparedStatementLogger.newInstance(stmt, statementLog, queryStack);
        //2.4返回代理对象
        return stmt;
      } else if ("prepareCall".equals(method.getName())) {
        //3.如果调用的是 prepareCall 方法
        if (isDebugEnabled()) {
          debug(" Preparing: " + removeBreakingWhitespace((String) params[0]), true);
        }
        PreparedStatement stmt = (PreparedStatement) method.invoke(connection, params);
        stmt = PreparedStatementLogger.newInstance(stmt, statementLog, queryStack);
        return stmt;
      } else if ("createStatement".equals(method.getName())) {
        //4.如果调用的是 createStatement 方法
        //4.1通过反射执行底层创建Statement的方法
        Statement stmt = (Statement) method.invoke(connection, params);
        //4.2为该 Statement 创建代理对象
        stmt = StatementLogger.newInstance(stmt, statementLog, queryStack);
        //4.3返回代理对象
        return stmt;
      } else {
        //5.调用其他方法，则直接反射调用底层connection对象的相应方法，不做处理
        return method.invoke(connection, params);
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
  }

  /**
   * 创建JDK动态代理连接对象
   * Creates a logging version of a connection
   *
   * @param conn - the original connection
   * @return - the connection with logging
   */
  public static Connection newInstance(Connection conn, Log statementLog, int queryStack) {
    //1.传入被代理对象，创建代理（调用）处理器
    InvocationHandler handler = new ConnectionLogger(conn, statementLog, queryStack);
    //2.获取类加载器
    ClassLoader cl = Connection.class.getClassLoader();
    //3.创建代理对象
    return (Connection) Proxy.newProxyInstance(cl, new Class[]{Connection.class}, handler);
  }

  /**
   * 获取被代理的连接
   * return the wrapped connection
   *
   * @return the connection
   */
  public Connection getConnection() {
    return connection;
  }

}
