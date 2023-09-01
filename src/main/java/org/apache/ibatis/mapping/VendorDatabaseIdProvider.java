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
package org.apache.ibatis.mapping;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;

/**
 * Vendor DatabaseId provider
 *
 * It returns database product name as a databaseId
 * If the user provides a properties it uses it to translate database product name
 * key="Microsoft SQL Server", value="ms" will return "ms"
 * It can return null, if no database product name or
 * a properties was specified and no translation was found
 *
 * @author Eduardo Macarron
 */
public class VendorDatabaseIdProvider implements DatabaseIdProvider {

  /**
   * 所有支持的 数据库名 和 数据库id标识 映射
   */
  private Properties properties;

  /**
   * 根据 DataSource 对象获取对应的 DatabaseId 字符串标识
   * @param dataSource 数据源
   * @return
   */
  @Override
  public String getDatabaseId(DataSource dataSource) {
    //1.如果dataSource为null抛出异常
    if (dataSource == null) {
      throw new NullPointerException("dataSource cannot be null");
    }
    try {
      //2.调用 getDatabaseName(dataSource) 方法获取数据库id标识
      return getDatabaseName(dataSource);
    } catch (Exception e) {
      LogHolder.log.error("Could not get a databaseId from dataSource", e);
    }
    return null;
  }

  @Override
  public void setProperties(Properties p) {
    this.properties = p;
  }

  /**
   * 根据 DataSource 对象获取对应的 DatabaseId 字符串标识底层实现，由 {@link #getDatabaseId(DataSource)} 调用
   * @param dataSource 数据源
   * @return
   * @throws SQLException
   */
  private String getDatabaseName(DataSource dataSource) throws SQLException {
    //1.获取 dataSource 中指定的数据库产品名
    String productName = getDatabaseProductName(dataSource);
    //2.如果 properties 属性不为空
    if (this.properties != null) {
      //2.1遍历properties属性
      for (Map.Entry<Object, Object> property : properties.entrySet()) {
        //2.2如果 数据库产品名 包含 properties属性的键，返回properties属性的值，即为 DatabaseId 字符串标识
        if (productName.contains((String) property.getKey())) {
          return (String) property.getValue();
        }
      }
      // no match, return null
      //2.3没有匹配的则返回null
      return null;
    }
    //2.如果 properties 属性为空则直接返回产品名
    return productName;
  }

  /**
   * 根据 DataSource 对象获取 数据库产品名
   * @param dataSource DataSource 对象
   * @return 数据库产品名
   * @throws SQLException
   */
  private String getDatabaseProductName(DataSource dataSource) throws SQLException {
    Connection con = null;
    try {
      //1.获取数据库连接
      con = dataSource.getConnection();
      //2.获取连接的元数据
      DatabaseMetaData metaData = con.getMetaData();
      //3.从元数据获取数据库产品名
      return metaData.getDatabaseProductName();
    } finally {
      if (con != null) {
        try {
          con.close();
        } catch (SQLException e) {
          // ignored
        }
      }
    }
  }

  /**
   * 内部类实现的单例模式
   */
  private static class LogHolder {
    private static final Log log = LogFactory.getLog(VendorDatabaseIdProvider.class);
  }

}
