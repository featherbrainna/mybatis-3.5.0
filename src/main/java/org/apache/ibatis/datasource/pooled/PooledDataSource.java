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
package org.apache.ibatis.datasource.pooled;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * PooledDataSource中管理的真正数据库连接对象是由 PooledDataSource 中封装的 UnpooledDataSource 对象创建的，
 * 并由PoolState管理所有连接的状态。
 * This is a simple, synchronous, thread-safe database connection pool.
 *
 * @author Clinton Begin
 */
public class PooledDataSource implements DataSource {

  private static final Log log = LogFactory.getLog(PooledDataSource.class);

  /**
   * 通过PoolState管理连接池的状态并记录统计信息
   */
  private final PoolState state = new PoolState(this);

  /**
   * 记录UnpooledDataSource对象，用于生成真实的数据库连接对象，构造函数中会初始化该字段
   */
  private final UnpooledDataSource dataSource;

  // OPTIONAL CONFIGURATION FIELDS
  /**
   * 最大活跃连接数
   */
  protected int poolMaximumActiveConnections = 10;
  /**
   * 最大空闲连接数
   */
  protected int poolMaximumIdleConnections = 5;
  /**
   * 最大checkout时长
   */
  protected int poolMaximumCheckoutTime = 20000;
  /**
   * 在无法获取连接时，线程需要等待的时间
   */
  protected int poolTimeToWait = 20000;
  /**
   * 最大坏连接数量
   */
  protected int poolMaximumLocalBadConnectionTolerance = 3;
  /**
   * 在检测一个数据库连接是否可用时，会给数据库发送一个测试sql语句
   */
  protected String poolPingQuery = "NO PING QUERY SET";
  /**
   * 是否允许发送测试SQL语句
   */
  protected boolean poolPingEnabled;
  /**
   * 当连接超过poolPingConnectionsNotUsedFor毫秒未使用时，会发送一次测试SQL语句，检测连接是否正常
   */
  protected int poolPingConnectionsNotUsedFor;
  /**
   * 根据数据库的URL、用户名和密码生成的一个hash值，该哈希值用于标志着当前的连接池，在构造器中初始化
   */
  private int expectedConnectionTypeCode;

  //#################################### 构造器 #######################################################

  public PooledDataSource() {
    dataSource = new UnpooledDataSource();
  }

  public PooledDataSource(UnpooledDataSource dataSource) {
    this.dataSource = dataSource;
  }

  public PooledDataSource(String driver, String url, String username, String password) {
    dataSource = new UnpooledDataSource(driver, url, username, password);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(String driver, String url, Properties driverProperties) {
    dataSource = new UnpooledDataSource(driver, url, driverProperties);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, String username, String password) {
    dataSource = new UnpooledDataSource(driverClassLoader, driver, url, username, password);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, Properties driverProperties) {
    dataSource = new UnpooledDataSource(driverClassLoader, driver, url, driverProperties);
    expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
  }

  //####################################### 实现DataSource的核心方法 #####################################################

  @Override
  public Connection getConnection() throws SQLException {
    return popConnection(dataSource.getUsername(), dataSource.getPassword()).getProxyConnection();
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return popConnection(username, password).getProxyConnection();
  }

  @Override
  public void setLoginTimeout(int loginTimeout) {
    DriverManager.setLoginTimeout(loginTimeout);
  }

  @Override
  public int getLoginTimeout() {
    return DriverManager.getLoginTimeout();
  }

  @Override
  public void setLogWriter(PrintWriter logWriter) {
    DriverManager.setLogWriter(logWriter);
  }

  @Override
  public PrintWriter getLogWriter() {
    return DriverManager.getLogWriter();
  }

  public void setDriver(String driver) {
    dataSource.setDriver(driver);
    forceCloseAll();
  }

  public void setUrl(String url) {
    dataSource.setUrl(url);
    forceCloseAll();
  }

  public void setUsername(String username) {
    dataSource.setUsername(username);
    forceCloseAll();
  }

  public void setPassword(String password) {
    dataSource.setPassword(password);
    forceCloseAll();
  }

  public void setDefaultAutoCommit(boolean defaultAutoCommit) {
    dataSource.setAutoCommit(defaultAutoCommit);
    forceCloseAll();
  }

  public void setDefaultTransactionIsolationLevel(Integer defaultTransactionIsolationLevel) {
    dataSource.setDefaultTransactionIsolationLevel(defaultTransactionIsolationLevel);
    forceCloseAll();
  }

  public void setDriverProperties(Properties driverProps) {
    dataSource.setDriverProperties(driverProps);
    forceCloseAll();
  }

  /**
   * The maximum number of active connections
   *
   * @param poolMaximumActiveConnections The maximum number of active connections
   */
  public void setPoolMaximumActiveConnections(int poolMaximumActiveConnections) {
    this.poolMaximumActiveConnections = poolMaximumActiveConnections;
    forceCloseAll();
  }

  /**
   * The maximum number of idle connections
   *
   * @param poolMaximumIdleConnections The maximum number of idle connections
   */
  public void setPoolMaximumIdleConnections(int poolMaximumIdleConnections) {
    this.poolMaximumIdleConnections = poolMaximumIdleConnections;
    forceCloseAll();
  }

  /**
   * The maximum number of tolerance for bad connection happens in one thread
    * which are applying for new {@link PooledConnection}
   *
   * @param poolMaximumLocalBadConnectionTolerance
   * max tolerance for bad connection happens in one thread
   *
   * @since 3.4.5
   */
  public void setPoolMaximumLocalBadConnectionTolerance(
      int poolMaximumLocalBadConnectionTolerance) {
    this.poolMaximumLocalBadConnectionTolerance = poolMaximumLocalBadConnectionTolerance;
  }

  /**
   * The maximum time a connection can be used before it *may* be
   * given away again.
   *
   * @param poolMaximumCheckoutTime The maximum time
   */
  public void setPoolMaximumCheckoutTime(int poolMaximumCheckoutTime) {
    this.poolMaximumCheckoutTime = poolMaximumCheckoutTime;
    forceCloseAll();
  }

  /**
   * The time to wait before retrying to get a connection
   *
   * @param poolTimeToWait The time to wait
   */
  public void setPoolTimeToWait(int poolTimeToWait) {
    this.poolTimeToWait = poolTimeToWait;
    forceCloseAll();
  }

  /**
   * The query to be used to check a connection
   *
   * @param poolPingQuery The query
   */
  public void setPoolPingQuery(String poolPingQuery) {
    this.poolPingQuery = poolPingQuery;
    forceCloseAll();
  }

  /**
   * Determines if the ping query should be used.
   *
   * @param poolPingEnabled True if we need to check a connection before using it
   */
  public void setPoolPingEnabled(boolean poolPingEnabled) {
    this.poolPingEnabled = poolPingEnabled;
    forceCloseAll();
  }

  /**
   * If a connection has not been used in this many milliseconds, ping the
   * database to make sure the connection is still good.
   *
   * @param milliseconds the number of milliseconds of inactivity that will trigger a ping
   */
  public void setPoolPingConnectionsNotUsedFor(int milliseconds) {
    this.poolPingConnectionsNotUsedFor = milliseconds;
    forceCloseAll();
  }

  public String getDriver() {
    return dataSource.getDriver();
  }

  public String getUrl() {
    return dataSource.getUrl();
  }

  public String getUsername() {
    return dataSource.getUsername();
  }

  public String getPassword() {
    return dataSource.getPassword();
  }

  public boolean isAutoCommit() {
    return dataSource.isAutoCommit();
  }

  public Integer getDefaultTransactionIsolationLevel() {
    return dataSource.getDefaultTransactionIsolationLevel();
  }

  public Properties getDriverProperties() {
    return dataSource.getDriverProperties();
  }

  public int getPoolMaximumActiveConnections() {
    return poolMaximumActiveConnections;
  }

  public int getPoolMaximumIdleConnections() {
    return poolMaximumIdleConnections;
  }

  public int getPoolMaximumLocalBadConnectionTolerance() {
    return poolMaximumLocalBadConnectionTolerance;
  }

  public int getPoolMaximumCheckoutTime() {
    return poolMaximumCheckoutTime;
  }

  public int getPoolTimeToWait() {
    return poolTimeToWait;
  }

  public String getPoolPingQuery() {
    return poolPingQuery;
  }

  public boolean isPoolPingEnabled() {
    return poolPingEnabled;
  }

  public int getPoolPingConnectionsNotUsedFor() {
    return poolPingConnectionsNotUsedFor;
  }

  //############ PooledDataSource底层核心实现（forceCloseAll、push/pop/pingConnection） ####################################################

  /*
   * 强制关闭所有连接
   * Closes all active and idle connections in the pool
   */
  public void forceCloseAll() {
    synchronized (state) {//同步
      //1.更新当前连接池标识
      expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
      //2.处理全部的活跃连接
      for (int i = state.activeConnections.size(); i > 0; i--) {
        try {
          //2.1从集合activeConnections中获取PooledConnection并移除
          PooledConnection conn = state.activeConnections.remove(i - 1);
          //2.2将PooledConnection对象设置为无效
          conn.invalidate();

          //2.3获取真正的数据库连接对象
          Connection realConn = conn.getRealConnection();
          //回滚未提交的事务
          if (!realConn.getAutoCommit()) {
            realConn.rollback();
          }
          //2.4关闭真正的数据库连接
          realConn.close();
        } catch (Exception e) {
          // ignore
        }
      }
      //3.处理全部的空闲连接
      for (int i = state.idleConnections.size(); i > 0; i--) {
        try {
          //3.1从集合idleConnections中获取PooledConnection并移除
          PooledConnection conn = state.idleConnections.remove(i - 1);
          //3.2将PooledConnection对象设置为无效
          conn.invalidate();

          //3.3获取真正的数据库连接对象
          Connection realConn = conn.getRealConnection();
          //回滚未提交的事务
          if (!realConn.getAutoCommit()) {
            realConn.rollback();
          }
          //3.4关闭真正的数据库连接
          realConn.close();
        } catch (Exception e) {
          // ignore
        }
      }
    }
    if (log.isDebugEnabled()) {
      log.debug("PooledDataSource forcefully closed/removed all connections.");
    }
  }

  public PoolState getPoolState() {
    return state;
  }

  /**
   * 构造连接池的hash值
   * @param url
   * @param username
   * @param password
   * @return
   */
  private int assembleConnectionTypeCode(String url, String username, String password) {
    return ("" + url + username + password).hashCode();
  }

  /**
   * 底层放回数据库连接的方法
   * @param conn 数据库连接
   * @throws SQLException
   */
  protected void pushConnection(PooledConnection conn) throws SQLException {
    //同步
    synchronized (state) {
      //1.从activeConnections集合中移除PooledConnection对象
      state.activeConnections.remove(conn);
      //2.连接有效
      if (conn.isValid()) {
        //3.空闲连接数小于最大空闲连接数 且 连接类型匹配当前连接池
        if (state.idleConnections.size() < poolMaximumIdleConnections && conn.getConnectionTypeCode() == expectedConnectionTypeCode) {
          //3.1累积checkout时长
          state.accumulatedCheckoutTime += conn.getCheckoutTime();
          //3.2回滚未提交的事务
          if (!conn.getRealConnection().getAutoCommit()) {
            conn.getRealConnection().rollback();
          }
          //为返还连接创建新的PooledConnection对象
          PooledConnection newConn = new PooledConnection(conn.getRealConnection(), this);
          //3.3将连接添加到idleConnections集合中
          state.idleConnections.add(newConn);
          newConn.setCreatedTimestamp(conn.getCreatedTimestamp());
          newConn.setLastUsedTimestamp(conn.getLastUsedTimestamp());
          //3.4将原PooledConnection设置为无效
          conn.invalidate();
          if (log.isDebugEnabled()) {
            log.debug("Returned connection " + newConn.getRealHashCode() + " to pool.");
          }
          //3.4唤醒在 popConnection(..) 方法阻塞等待的线程
          state.notifyAll();
        } else {
          //3.空闲连接数已达到上限 或 PooledConnection对象并不属于该连接池
          //3.1累积checkout时长
          state.accumulatedCheckoutTime += conn.getCheckoutTime();
          //3.2回滚未提交的事务
          if (!conn.getRealConnection().getAutoCommit()) {
            conn.getRealConnection().rollback();
          }
          //3.3关闭真正的数据库连接
          conn.getRealConnection().close();
          if (log.isDebugEnabled()) {
            log.debug("Closed connection " + conn.getRealHashCode() + ".");
          }
          //3.4将原PooledConnection设置为无效
          conn.invalidate();
        }
      } else {
        //2.连接无效
        if (log.isDebugEnabled()) {
          log.debug("A bad connection (" + conn.getRealHashCode() + ") attempted to return to the pool, discarding connection.");
        }
        //2.1poolstate统计无效连接对象个数
        state.badConnectionCount++;
      }
    }
  }

  /**
   * 底层获取数据库连接的方法
   * 拉模式更新数据库连接池PoolState的状态
   * @param username 数据库用户名
   * @param password 数据库密码
   * @return 数据库连接
   * @throws SQLException
   */
  private PooledConnection popConnection(String username, String password) throws SQLException {
    //标志位，是否计数等待
    boolean countedWait = false;
    //记录要返回的连接对象
    PooledConnection conn = null;
    //记录时间戳
    long t = System.currentTimeMillis();
    //记录坏连接数
    int localBadConnectionCount = 0;

    //1.循环处理直到获取到连接对象
    while (conn == null) {
      synchronized (state) {//同步
        //2.有空闲连接
        if (!state.idleConnections.isEmpty()) {
          // Pool has available connection
          //获取空闲连接
          conn = state.idleConnections.remove(0);
          if (log.isDebugEnabled()) {
            log.debug("Checked out connection " + conn.getRealHashCode() + " from pool.");
          }
        } else {
          //2.无空闲连接
          // Pool does not have available connection
          //3.活跃连接数未到最大值
          if (state.activeConnections.size() < poolMaximumActiveConnections) {
            // Can create new connection
            //创建新连接
            conn = new PooledConnection(dataSource.getConnection(), this);
            if (log.isDebugEnabled()) {
              log.debug("Created connection " + conn.getRealHashCode() + ".");
            }
          } else {
            //3.活跃连接数到最大值
            // Cannot create new connection
            //检查激活连接是否超时连接
            PooledConnection oldestActiveConnection = state.activeConnections.get(0);
            long longestCheckoutTime = oldestActiveConnection.getCheckoutTime();
            //4.连接超时
            if (longestCheckoutTime > poolMaximumCheckoutTime) {
              // Can claim overdue connection
              //4.1对超时连接的信息进行统计
              state.claimedOverdueConnectionCount++;
              state.accumulatedCheckoutTimeOfOverdueConnections += longestCheckoutTime;
              state.accumulatedCheckoutTime += longestCheckoutTime;
              //4.2将超时连接移出activeConnections
              state.activeConnections.remove(oldestActiveConnection);
              //4.3如果超时连接未提交，则自动回滚（前提未设置自动提交）
              if (!oldestActiveConnection.getRealConnection().getAutoCommit()) {
                try {
                  //回滚
                  oldestActiveConnection.getRealConnection().rollback();
                } catch (SQLException e) {
                  /*
                     Just log a message for debug and continue to execute the following
                     statement like nothing happened.
                     Wrap the bad connection with a new PooledConnection, this will help
                     to not interrupt current executing thread and give current thread a
                     chance to join the next competition for another valid/good database
                     connection. At the end of this loop, bad {@link @conn} will be set as null.
                   */
                  log.debug("Bad connection. Could not roll back");
                }
              }
              //4.4创建新的PooledConnection对象，但是真正的数据库连接并未创建新的
              conn = new PooledConnection(oldestActiveConnection.getRealConnection(), this);
              conn.setCreatedTimestamp(oldestActiveConnection.getCreatedTimestamp());
              conn.setLastUsedTimestamp(oldestActiveConnection.getLastUsedTimestamp());
              //4.5将超时PooledConnection设置为无效
              oldestActiveConnection.invalidate();
              if (log.isDebugEnabled()) {
                log.debug("Claimed overdue connection " + conn.getRealHashCode() + ".");
              }
            } else {
              //4.未超时等待（无空闲连接、无法创建新连接，则只能阻塞等待）
              // Must wait
              try {
                if (!countedWait) {
                  //统计等待次数
                  state.hadToWaitCount++;
                  countedWait = true;
                }
                if (log.isDebugEnabled()) {
                  log.debug("Waiting as long as " + poolTimeToWait + " milliseconds for connection.");
                }
                long wt = System.currentTimeMillis();
                //4.1阻塞等待
                state.wait(poolTimeToWait);
                //统计累积等待时间
                state.accumulatedWaitTime += System.currentTimeMillis() - wt;
              } catch (InterruptedException e) {
                break;
              }
            }
          }
        }
        //连接不为空（连接为空时说明是阻塞等待情况，继续下一循环获取连接）
        if (conn != null) {
          // ping to server and check the connection is valid or not
          //5.PooledConnection连接有效
          if (conn.isValid()) {
            //得到连接前先回滚到安全状态
            if (!conn.getRealConnection().getAutoCommit()) {
              conn.getRealConnection().rollback();
            }
            //5.1配置PooledConnection的相关属性，设置ConnectionTypeCode、CheckoutTimestamp、LastUsedTimestamp
            conn.setConnectionTypeCode(assembleConnectionTypeCode(dataSource.getUrl(), username, password));
            conn.setCheckoutTimestamp(System.currentTimeMillis());
            conn.setLastUsedTimestamp(System.currentTimeMillis());
            //5.2加入activeConnections集合，进行相关统计
            state.activeConnections.add(conn);//⭐
            state.requestCount++;
            state.accumulatedRequestTime += System.currentTimeMillis() - t;
          } else {
            //5.PooledConnection连接无效
            if (log.isDebugEnabled()) {
              log.debug("A bad connection (" + conn.getRealHashCode() + ") was returned from the pool, getting another connection.");
            }
            //5.1统计连接池信息
            state.badConnectionCount++;
            localBadConnectionCount++;
            //5.2将conn重置为null，下一循环继续获取连接
            conn = null;
            if (localBadConnectionCount > (poolMaximumIdleConnections + poolMaximumLocalBadConnectionTolerance)) {
              if (log.isDebugEnabled()) {
                log.debug("PooledDataSource: Could not get a good connection to the database.");
              }
              throw new SQLException("PooledDataSource: Could not get a good connection to the database.");
            }
          }
        }
      }

    }

    //返回前判断连接是否为null，为null抛出异常
    if (conn == null) {
      if (log.isDebugEnabled()) {
        log.debug("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
      }
      throw new SQLException("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
    }

    //返回连接
    return conn;
  }

  /**
   * ping检测连接是否可用
   * Method to check to see if a connection is still usable
   *
   * @param conn - the connection to check
   * @return True if the connection is still usable
   */
  protected boolean pingConnection(PooledConnection conn) {
    //记录ping操作是否成功
    boolean result = true;

    try {
      //1.检测真正的数据库连接是否已经关闭
      result = !conn.getRealConnection().isClosed();
    } catch (SQLException e) {
      if (log.isDebugEnabled()) {
        log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
      }
      result = false;
    }

    if (result) {
      //2.检测poolPingEnabled设置，是否运行执行测试SQL语句
      if (poolPingEnabled) {
        //3.长时间(超过poolPingConnectionsNotUsedFor指定的时长)未使用的连接，才需要ping操作来检测数据库连接是否正常
        if (poolPingConnectionsNotUsedFor >= 0 && conn.getTimeElapsedSinceLastUse() > poolPingConnectionsNotUsedFor) {
          try {
            if (log.isDebugEnabled()) {
              log.debug("Testing connection " + conn.getRealHashCode() + " ...");
            }
            //3.1获取底层真实连接
            Connection realConn = conn.getRealConnection();
            //3.2获取Statement对象，并执行poolPingQuery sql语句。sql执行出错会抛出异常
            try (Statement statement = realConn.createStatement()) {
              statement.executeQuery(poolPingQuery).close();
            }
            if (!realConn.getAutoCommit()) {
              realConn.rollback();
            }
            //3.3ping操作成功
            result = true;
            if (log.isDebugEnabled()) {
              log.debug("Connection " + conn.getRealHashCode() + " is GOOD!");
            }
          } catch (Exception e) {
            log.warn("Execution of ping query '" + poolPingQuery + "' failed: " + e.getMessage());
            try {
              //3.1关闭连接
              conn.getRealConnection().close();
            } catch (Exception e2) {
              //ignore
            }
            //3.2ping操作失败
            result = false;
            if (log.isDebugEnabled()) {
              log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
            }
          }
        }
      }
    }
    //4.返回ping操作结果
    return result;
  }

  /**
   * 返回数据库连接底层真实连接对象
   * Unwraps a pooled connection to get to the 'real' connection
   *
   * @param conn - the pooled connection to unwrap
   * @return The 'real' connection
   */
  public static Connection unwrapConnection(Connection conn) {
    if (Proxy.isProxyClass(conn.getClass())) {
      InvocationHandler handler = Proxy.getInvocationHandler(conn);
      if (handler instanceof PooledConnection) {
        return ((PooledConnection) handler).getRealConnection();
      }
    }
    return conn;
  }

  protected void finalize() throws Throwable {
    forceCloseAll();
    super.finalize();
  }

  //###################################### Wrapper接口实现 ######################################################

  public <T> T unwrap(Class<T> iface) throws SQLException {
    throw new SQLException(getClass().getName() + " is not a wrapper.");
  }

  public boolean isWrapperFor(Class<?> iface) {
    return false;
  }

  public Logger getParentLogger() {
    return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME); // requires JDK version 1.6
  }

}
