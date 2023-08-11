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
package org.apache.ibatis.datasource.jndi;

import org.apache.ibatis.datasource.DataSourceException;
import org.apache.ibatis.datasource.DataSourceFactory;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.sql.DataSource;
import java.util.Map.Entry;
import java.util.Properties;

/**
 * JNDI数据源工厂
 * @author Clinton Begin
 */
public class JndiDataSourceFactory implements DataSourceFactory {

  public static final String INITIAL_CONTEXT = "initial_context";
  public static final String DATA_SOURCE = "data_source";
  public static final String ENV_PREFIX = "env.";

  /**
   * dataSource不在构造方法中创建，而是在{@link JndiDataSourceFactory#setProperties(Properties)}中。
   */
  private DataSource dataSource;

  /**
   * 设置属性，根据属性初始化dataSource
   * @param properties 属性对象
   */
  @Override
  public void setProperties(Properties properties) {
    try {
      InitialContext initCtx;
      //1.获取环境属性
      Properties env = getEnvProperties(properties);
      //2.初始化InitialContext对象
      if (env == null) {
        initCtx = new InitialContext();
      } else {
        initCtx = new InitialContext(env);
      }

      //3.从InitialContext上下文中，初始化dataSource
      if (properties.containsKey(INITIAL_CONTEXT)
          && properties.containsKey(DATA_SOURCE)) {
        Context ctx = (Context) initCtx.lookup(properties.getProperty(INITIAL_CONTEXT));
        dataSource = (DataSource) ctx.lookup(properties.getProperty(DATA_SOURCE));
      } else if (properties.containsKey(DATA_SOURCE)) {
        dataSource = (DataSource) initCtx.lookup(properties.getProperty(DATA_SOURCE));
      }

    } catch (NamingException e) {
      throw new DataSourceException("There was an error configuring JndiDataSourceTransactionPool. Cause: " + e, e);
    }
  }

  @Override
  public DataSource getDataSource() {
    return dataSource;
  }

  /**
   * 获取环境属性
   * @param allProps 所有属性对象
   * @return 环境属性对象
   */
  private static Properties getEnvProperties(Properties allProps) {
    final String PREFIX = ENV_PREFIX;
    //声明环境属性对象
    Properties contextProperties = null;
    //1.遍历allProps所有属性
    for (Entry<Object, Object> entry : allProps.entrySet()) {
      String key = (String) entry.getKey();
      String value = (String) entry.getValue();
      //1.1以PREFIX开头，说明属于环境属性
      if (key.startsWith(PREFIX)) {
        if (contextProperties == null) {
          contextProperties = new Properties();
        }
        //1.2在contextProperties放入筛分后的环境属性
        contextProperties.put(key.substring(PREFIX.length()), value);
      }
    }
    //2.返回筛分后的环境属性
    return contextProperties;
  }

}
