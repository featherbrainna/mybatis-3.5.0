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
import org.apache.ibatis.reflection.ArrayUtil;

import java.sql.Array;
import java.sql.SQLException;
import java.util.*;

/**
 * Base class for proxies to do logging
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public abstract class BaseJdbcLogger {

  /**
   * 记录了 PreparedStatement 接口中常用的 set*()方法
   */
  protected static final Set<String> SET_METHODS = new HashSet<>();
  /**
   * 记录了 Statement 接口和 PreparedStatement 接口中与执行SQL语句相关的方法
   */
  protected static final Set<String> EXECUTE_METHODS = new HashSet<>();

  /**
   * 记录了 PreparedStatement.set*() 方法设置的键值对
   */
  private final Map<Object, Object> columnMap = new HashMap<>();

  /**
   * 记录了 PreparedStatement.set*() 方法设置的key值
   */
  private final List<Object> columnNames = new ArrayList<>();
  /**
   * 记录了 PreparedStatement.set*() 方法设置的value值
   */
  private final List<Object> columnValues = new ArrayList<>();

  /**
   * 用于输出日志的Log对象
   */
  protected Log statementLog;
  /**
   * 记录了SQL的层数，用于格式化输出SQL
   */
  protected int queryStack;

  /*
   * Default constructor
   */
  public BaseJdbcLogger(Log log, int queryStack) {
    this.statementLog = log;
    if (queryStack == 0) {
      this.queryStack = 1;
    } else {
      this.queryStack = queryStack;
    }
  }

  /**
   * 静态代码块，初始化 SET_METHODS 和 EXECUTE_METHODS 集合
   */
  static {
    SET_METHODS.add("setString");
    SET_METHODS.add("setNString");
    SET_METHODS.add("setInt");
    SET_METHODS.add("setByte");
    SET_METHODS.add("setShort");
    SET_METHODS.add("setLong");
    SET_METHODS.add("setDouble");
    SET_METHODS.add("setFloat");
    SET_METHODS.add("setTimestamp");
    SET_METHODS.add("setDate");
    SET_METHODS.add("setTime");
    SET_METHODS.add("setArray");
    SET_METHODS.add("setBigDecimal");
    SET_METHODS.add("setAsciiStream");
    SET_METHODS.add("setBinaryStream");
    SET_METHODS.add("setBlob");
    SET_METHODS.add("setBoolean");
    SET_METHODS.add("setBytes");
    SET_METHODS.add("setCharacterStream");
    SET_METHODS.add("setNCharacterStream");
    SET_METHODS.add("setClob");
    SET_METHODS.add("setNClob");
    SET_METHODS.add("setObject");
    SET_METHODS.add("setNull");

    EXECUTE_METHODS.add("execute");
    EXECUTE_METHODS.add("executeUpdate");
    EXECUTE_METHODS.add("executeQuery");
    EXECUTE_METHODS.add("addBatch");
  }

  protected void setColumn(Object key, Object value) {
    columnMap.put(key, value);
    columnNames.add(key);
    columnValues.add(value);
  }

  protected Object getColumn(Object key) {
    return columnMap.get(key);
  }

  /**
   * 构建(设置到 PreparedStatement 的) 参数值和类型 字符串
   * 字符串格式： 值（值全类名）， 值（值全类名）...
   * @return
   */
  protected String getParameterValueString() {
    List<Object> typeList = new ArrayList<>(columnValues.size());
    //1.遍历参数值
    for (Object value : columnValues) {
      if (value == null) {
        typeList.add("null");
      } else {
        //2.获取参数值和餐宿类型
        typeList.add(objectValueString(value) + "(" + value.getClass().getSimpleName() + ")");
      }
    }
    //3.通过数组的 toString 方法转化为字符串返回
    final String parameters = typeList.toString();
    return parameters.substring(1, parameters.length() - 1);
  }

  /**
   * 构建参数值字符串
   * @param value 参数值
   * @return
   */
  protected String objectValueString(Object value) {
    if (value instanceof Array) {
      try {
        return ArrayUtil.toString(((Array) value).getArray());
      } catch (SQLException e) {
        return value.toString();
      }
    }
    return value.toString();
  }

  protected String getColumnString() {
    return columnNames.toString();
  }

  /**
   * 清空 Column 数据
   */
  protected void clearColumnInfo() {
    columnMap.clear();
    columnNames.clear();
    columnValues.clear();
  }

  /**
   * 格式化 original 字符串，去掉多余的空白符。
   * @param original
   * @return
   */
  protected String removeBreakingWhitespace(String original) {
    StringTokenizer whitespaceStripper = new StringTokenizer(original);
    StringBuilder builder = new StringBuilder();
    while (whitespaceStripper.hasMoreTokens()) {
      builder.append(whitespaceStripper.nextToken());
      builder.append(" ");
    }
    return builder.toString();
  }

  protected boolean isDebugEnabled() {
    return statementLog.isDebugEnabled();
  }

  protected boolean isTraceEnabled() {
    return statementLog.isTraceEnabled();
  }

  /**
   * debug日志输出
   * @param text 输出日志内容
   * @param input 标志sql输入输出
   */
  protected void debug(String text, boolean input) {
    if (statementLog.isDebugEnabled()) {
      statementLog.debug(prefix(input) + text);
    }
  }

  /**
   * trace日志输出
   * @param text 输出日志内容
   * @param input 标志sql输入输出
   */
  protected void trace(String text, boolean input) {
    if (statementLog.isTraceEnabled()) {
      statementLog.trace(prefix(input) + text);
    }
  }

  /**
   * 格式化的日志输出前缀
   * @param isInput 输入标识，true代表输入，以=>开头；false代表输出，以<=开头
   * @return 日志前缀字符串
   */
  private String prefix(boolean isInput) {
    char[] buffer = new char[queryStack * 2 + 2];
    Arrays.fill(buffer, '=');
    buffer[queryStack * 2 + 1] = ' ';
    if (isInput) {
      buffer[queryStack * 2] = '>';
    } else {
      buffer[0] = '<';
    }
    return new String(buffer);
  }

}
