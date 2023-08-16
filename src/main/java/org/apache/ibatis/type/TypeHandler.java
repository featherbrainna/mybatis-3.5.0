/**
 *    Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.type;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 类型转换器接口
 * @author Clinton Begin
 */
public interface TypeHandler<T> {

  /**
   * 设置 PreparedStatement 的指定参数，将数据由Java类型转换为JdbcType类型
   * @param ps PreparedStatement对象
   * @param i 参数占位符位置
   * @param parameter 参数
   * @param jdbcType JDBC类型
   * @throws SQLException 当发生SQL异常时
   */
  void setParameter(PreparedStatement ps, int i, T parameter, JdbcType jdbcType) throws SQLException;

  /**
   * 获取 ResultSet 的指定字段的值
   * JDBC Type => Java Type
   * @param rs ResultSet对象
   * @param columnName 字段名
   * @return 值
   * @throws SQLException 当发生SQL异常时
   */
  T getResult(ResultSet rs, String columnName) throws SQLException;

  /**
   * 获取 ResultSet 的指定字段的值
   * JDBC Type => Java Type
   * @param rs ResultSet对象
   * @param columnIndex 字段位置索引
   * @return 值
   * @throws SQLException 当发生SQL异常时
   */
  T getResult(ResultSet rs, int columnIndex) throws SQLException;

  /**
   * 获得 CallableStatement 的指定字段的值
   * JDBC Type => Java Type
   * @param cs CallableStatement对象，支持调用存储过程
   * @param columnIndex 字段位置
   * @return 值
   * @throws SQLException 当发生SQL异常时
   */
  T getResult(CallableStatement cs, int columnIndex) throws SQLException;

}
